package se.swahnchat.app.call

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import se.swahnchat.core.client.CallSignalEvent
import se.swahnchat.core.client.SystemEvent
import se.swahnchat.core.client.call.CallSignalling
import se.swahnchat.core.client.call.IceServerConfig
import se.swahnchat.core.client.call.MediaConnectionState
import se.swahnchat.core.client.call.MediaSession
import se.swahnchat.core.client.call.MediaSessionFactory
import se.swahnchat.core.model.CallAction
import se.swahnchat.core.model.CallSignal
import se.swahnchat.core.model.IceCandidate
import se.swahnchat.core.model.TurnCredentials

/** The conversation the fakes treat as direct, and its other member. */
const val DIRECT = "direct"
const val CALLER = "bob"
private const val SDP = "v=0\r\na=fingerprint:sha-256 AA:BB\r\n"

/** A [CallSignalling] for app tests: signals are pushed in with [receive], what is sent is recorded. */
class TestSignalling : CallSignalling {
    val flow = MutableSharedFlow<CallSignalEvent>(extraBufferCapacity = 64)
    val sent = ArrayList<CallSignal>()
    val notices = ArrayList<SystemEvent.Call>()

    override val signals: Flow<CallSignalEvent> get() = flow

    override suspend fun send(conversationId: String, signal: CallSignal) {
        sent += signal
    }

    override suspend fun peerOf(conversationId: String): String? = if (conversationId == DIRECT) CALLER else null

    override suspend fun turnCredentials(): TurnCredentials = TurnCredentials(listOf("stun:s:3478"), "u", "p", 3600)

    override suspend fun recordCall(conversationId: String, callId: String, notice: SystemEvent.Call) {
        notices += notice
    }

    override fun serverNow(): Long = System.currentTimeMillis()

    /** The other member's phone calls (an offer), or acts on the call [callId] with [action]. */
    fun receive(signal: CallSignal, fromSelf: Boolean = false, user: String = CALLER, device: String = "dev-bob") {
        flow.tryEmit(CallSignalEvent(DIRECT, user, device, fromSelf, System.currentTimeMillis(), signal))
    }

    fun offer(callId: String, video: Boolean = false) = receive(CallSignal(callId, CallAction.OFFER, video, sdp = SDP))

    fun hangup(callId: String) = receive(CallSignal(callId, CallAction.HANGUP))

    /** Another device of this account answered. */
    fun answeredElsewhere(callId: String) =
        receive(CallSignal(callId, CallAction.ANSWER, sdp = SDP), fromSelf = true, user = "me", device = "dev-me-2")
}

class TestMedia : MediaSessionFactory {
    var last: TestSession? = null

    override fun create(iceServers: List<IceServerConfig>, relayOnly: Boolean, video: Boolean, listener: MediaSession.Listener): MediaSession =
        TestSession(relayOnly, video, listener).also { last = it }
}

class TestSession(val relayOnly: Boolean, val video: Boolean, val listener: MediaSession.Listener) : MediaSession {
    override suspend fun createOffer() = SDP + "offer"

    override suspend fun acceptOffer(sdp: String) = SDP + "answer"

    override suspend fun acceptAnswer(sdp: String) = Unit

    override fun addRemoteCandidates(candidates: List<IceCandidate>) = Unit

    override fun setMuted(muted: Boolean) = Unit

    override fun close() = Unit

    fun connected() = listener.onConnectionState(MediaConnectionState.CONNECTED)
}
