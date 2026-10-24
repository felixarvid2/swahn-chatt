package se.familychat.core.network

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import se.familychat.core.model.ClientFrame
import se.familychat.core.model.ServerFrame
import se.familychat.core.model.TypingState
import java.io.IOException
import kotlin.coroutines.resume

enum class RealtimeState {
    /** Not started, or waiting for the next reconnect attempt. */
    DISCONNECTED,
    CONNECTING,

    /** Socket open (the server's `hello` follows immediately). */
    CONNECTED,

    /** [RealtimeClient.stop] was called. */
    STOPPED,

    /** The session ended (token refresh rejected / no tokens): the client stopped for good. */
    LOGGED_OUT,
}

/** What the engine needs from the WebSocket; [RealtimeClient] is the real implementation, tests can fake it. */
interface RealtimeConnection {
    /** Frames from the server (hello, sync, typing, pong, unknown). Hot: collect before [start] to miss nothing. */
    val frames: SharedFlow<ServerFrame>
    val state: StateFlow<RealtimeState>
    fun start()
    fun stop()

    /** Relayed to the other members' connected devices; returns false if there is no open connection. */
    fun sendTyping(conversationId: String, state: TypingState): Boolean
    fun sendPing(): Boolean
}

/**
 * The `/ws` connection. It only carries hints (`hello`, `sync`) and ephemeral `typing`/`pong` frames; the engine
 * reacts to a hint by running a sync, never by trusting the frame's content.
 *
 * Lifecycle: [start] launches a reconnect loop in [scope]; [stop] (or cancelling the scope) closes the socket.
 * After any drop the loop reconnects with [BackoffPolicy] (1 s doubling to 60 s with jitter; the backoff resets once a
 * connection delivered a frame). Close code 4401 (or HTTP 401 on the upgrade) triggers a token refresh and an immediate
 * reconnect; if the refresh says the session ended, the client enters [RealtimeState.LOGGED_OUT] and stays down.
 *
 * Every (re)connect is followed by a `hello` frame on [frames], so a collector that syncs on `Hello` also catches up
 * after a reconnect.
 */
class RealtimeClient internal constructor(
    private val webSocketUrl: HttpUrl,
    private val okHttp: OkHttpClient,
    private val tokens: TokenProvider,
    /** Refresh the token given the stale one; false if the session ended. May throw IOException. */
    private val refresh: suspend (staleAccessToken: String?) -> Boolean,
    private val scope: CoroutineScope,
    private val backoff: BackoffPolicy,
) : RealtimeConnection {
    private val _frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 256)

    override val frames: SharedFlow<ServerFrame> = _frames.asSharedFlow()

    private val _state = MutableStateFlow(RealtimeState.DISCONNECTED)
    override val state: StateFlow<RealtimeState> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    private var job: Job? = null

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        _state.value = RealtimeState.DISCONNECTED
        job = scope.launch { runLoop() }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, "bye")
        socket = null
        if (_state.value != RealtimeState.LOGGED_OUT) _state.value = RealtimeState.STOPPED
    }

    override fun sendTyping(conversationId: String, state: TypingState): Boolean =
        send(ClientFrame.Typing(conversationId, state.wire))

    override fun sendPing(): Boolean = send(ClientFrame.Ping)

    private fun send(frame: ClientFrame): Boolean = socket?.send(frame.toJson()) ?: false

    private class Outcome(val closeCode: Int?, val unauthorizedUpgrade: Boolean, val gotFrame: Boolean)

    private suspend fun runLoop() {
        var attempt = 0
        var lastWasAuthFailure = false
        while (scope.isActive) {
            val current = tokens.getTokens()
            if (current == null) {
                _state.value = RealtimeState.LOGGED_OUT
                return
            }
            _state.value = RealtimeState.CONNECTING
            val outcome = connectOnce(current.accessToken)
            socket = null
            if (!scope.isActive) return
            _state.value = RealtimeState.DISCONNECTED

            if (outcome.closeCode == CLOSE_UNAUTHORIZED || outcome.unauthorizedUpgrade) {
                val ok = try {
                    refresh(current.accessToken)
                } catch (e: IOException) {
                    false.also { /* server unreachable: fall through to backoff below */ }
                }
                if (tokens.getTokens() == null) {
                    _state.value = RealtimeState.LOGGED_OUT
                    return
                }
                if (ok && !lastWasAuthFailure) { // reconnect at once, but not in a tight loop
                    lastWasAuthFailure = true
                    continue
                }
                lastWasAuthFailure = ok
            } else {
                lastWasAuthFailure = false
            }
            if (outcome.gotFrame) attempt = 0
            delay(backoff.delayMs(attempt))
            attempt++
        }
    }

    private suspend fun connectOnce(accessToken: String): Outcome = suspendCancellableCoroutine { cont ->
        var gotFrame = false
        val request = Request.Builder().url(webSocketUrl).header("Authorization", "Bearer $accessToken").build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                _state.value = RealtimeState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = ServerFrame.parse(text) ?: return
                gotFrame = true
                _frames.tryEmit(frame)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code.takeIf { it in 1000..4999 && it != 1005 } ?: 1000, null)
                finish(cont, Outcome(code, false, gotFrame))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(cont, Outcome(code, false, gotFrame))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(cont, Outcome(null, response?.code == 401, gotFrame))
            }
        }
        val ws = okHttp.newWebSocket(request, listener)
        cont.invokeOnCancellation { ws.cancel() }
    }

    private fun finish(cont: CancellableContinuation<Outcome>, outcome: Outcome) {
        if (cont.isActive) cont.resume(outcome)
    }

    companion object {
        /** Server close code for an invalid or expired token. */
        const val CLOSE_UNAUTHORIZED = 4401
    }
}
