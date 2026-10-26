package se.swahnchat.app.diagnostics

import se.swahnchat.core.client.FailureReason
import se.swahnchat.core.client.call.CallState
import se.swahnchat.core.client.call.EndReason
import se.swahnchat.core.network.ApiException
import se.swahnchat.core.network.RealtimeState
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * The troubleshooting log is redacting by construction: the logger accepts only the events below, and an event is made of
 * numbers, flags, enums and [Ref]s. There is no field that takes free text, so a message text, a name, a token, a key, a push
 * endpoint, a web address or an IP address cannot be written, whatever a caller passes. The one thing that starts as text is an id;
 * [Ref] keeps eight hex digits of a salted hash of it, which tells two lines about the same conversation apart and nothing else.
 */
sealed interface LogEvent {
    /** One short word that names the kind of event. */
    val tag: String

    /** The values of the event, rendered. Keys and values contain only `[A-Za-z0-9_.-]`, see [EventLog.sanitize]. */
    fun fields(): List<Pair<String, String>>
}

/** An id as it appears in the log: eight hex digits of SHA-256(salt + id). Created only through [of]. */
class Ref private constructor(val shown: String) {
    override fun equals(other: Any?) = other is Ref && other.shown == shown
    override fun hashCode() = shown.hashCode()
    override fun toString() = shown

    companion object {
        fun of(id: String?, salt: ByteArray = DEFAULT_SALT): Ref {
            if (id.isNullOrEmpty()) return Ref("none")
            val digest = MessageDigest.getInstance("SHA-256").run {
                update(salt)
                update(id.toByteArray(Charsets.UTF_8))
                digest()
            }
            return Ref(digest.take(4).joinToString("") { "%02x".format(it) })
        }

        /** Replaced per install by [EventLog] (kept in its private folder), so two phones never produce the same reference. */
        val DEFAULT_SALT = ByteArray(8)
    }
}

/** What kind of failure it was; never the message of the exception, which can contain addresses or content. */
enum class ErrorClass {
    NONE, OFFLINE, TIMEOUT, TLS, REFUSED_BY_SERVER, SESSION_ENDED, RATE_LIMITED, SERVER_ERROR, CRYPTO, INVALID_STATE, UNKNOWN;

    companion object {
        fun of(e: Throwable?): ErrorClass = when (e) {
            null -> NONE
            is ApiException.SessionEnded -> SESSION_ENDED
            is ApiException.RateLimited -> RATE_LIMITED
            is ApiException.Server -> SERVER_ERROR
            is ApiException -> REFUSED_BY_SERVER
            is SSLException -> TLS
            is TimeoutException, is java.net.SocketTimeoutException -> TIMEOUT
            is IOException -> OFFLINE
            is IllegalStateException, is IllegalArgumentException -> INVALID_STATE
            is java.security.GeneralSecurityException -> CRYPTO
            else -> UNKNOWN
        }

        /** The HTTP status of a server answer (a number), or 0. */
        fun statusOf(e: Throwable?): Int = (e as? ApiException)?.httpStatus ?: 0
    }
}

enum class SyncTrigger { PUSH, PERIODIC, REALTIME, FOREGROUND, MANUAL }

enum class WorkerKind { SYNC, PERIODIC_SYNC, OUTBOX, SCHEDULED_SEND, EXPIRY }

enum class SessionChange { SIGNED_IN, SIGNED_IN_WITH_RESET_CODE, SIGNED_OUT, SESSION_ENDED, LOG_EXPORTED }

enum class CallPhase { IDLE, RINGING_OUT, RINGING_IN, CONNECTING, CONNECTED, ENDED }

/** The phase of a call and, once it ended, why. Never who the other person is, the call id or any media detail. */
fun CallState.toPhase(): Pair<CallPhase, EndReason?> = when (this) {
    CallState.Idle -> CallPhase.IDLE to null
    is CallState.Outgoing -> CallPhase.RINGING_OUT to null
    is CallState.Incoming -> CallPhase.RINGING_IN to null
    is CallState.Connecting -> CallPhase.CONNECTING to null
    is CallState.Connected -> CallPhase.CONNECTED to null
    is CallState.Ended -> CallPhase.ENDED to reason
}

data class AppStarted(val versionCode: Int, val sdk: Int) : LogEvent {
    override val tag get() = "app_start"
    override fun fields() = listOf("version" to versionCode.toString(), "sdk" to sdk.toString())
}

data class SyncCycle(val trigger: SyncTrigger, val applied: Int, val error: ErrorClass, val status: Int = 0) : LogEvent {
    override val tag get() = "sync"
    override fun fields() = listOf(
        "trigger" to trigger.name, "applied" to applied.toString(), "result" to if (error == ErrorClass.NONE) "ok" else error.name,
        "status" to status.toString(),
    )
}

data class PushReceived(val handedToWorker: Boolean = true) : LogEvent {
    override val tag get() = "push_received"
    override fun fields() = listOf("worker" to handedToWorker.toString())
}

/** The distributor gave us an endpoint, or the server took it. The endpoint itself is never recorded. */
data class PushRegistration(val uploaded: Boolean, val error: ErrorClass = ErrorClass.NONE) : LogEvent {
    override val tag get() = "push_endpoint"
    override fun fields() = listOf("uploaded" to uploaded.toString(), "result" to if (error == ErrorClass.NONE) "ok" else error.name)
}

data class PushTest(val httpStatus: Int, val error: ErrorClass = ErrorClass.NONE) : LogEvent {
    override val tag get() = "push_test"
    override fun fields() = listOf("status" to httpStatus.toString(), "result" to if (error == ErrorClass.NONE) "ok" else error.name)
}

data class RealtimeChange(val state: RealtimeState) : LogEvent {
    override val tag get() = "realtime"
    override fun fields() = listOf("state" to state.name)
}

/** A message that could not be sent: where (a shortened hash of the conversation id) and what kind of failure. */
data class SendFailed(val conversation: Ref, val reason: FailureReason?, val error: ErrorClass = ErrorClass.NONE, val status: Int = 0) : LogEvent {
    override val tag get() = "send_failed"
    override fun fields() = listOf(
        "chat" to conversation.shown, "reason" to (reason?.name ?: "none"), "error" to error.name, "status" to status.toString(),
    )
}

data class OutboxPass(val sent: Int, val failed: Int, val pending: Int, val offline: Boolean, val error: ErrorClass = ErrorClass.NONE) : LogEvent {
    override val tag get() = "outbox"
    override fun fields() = listOf(
        "sent" to sent.toString(), "failed" to failed.toString(), "pending" to pending.toString(), "offline" to offline.toString(),
        "error" to error.name,
    )
}

data class CallChange(val phase: CallPhase, val reason: EndReason? = null, val video: Boolean = false) : LogEvent {
    override val tag get() = "call"
    override fun fields() = listOf("phase" to phase.name, "reason" to (reason?.name ?: "none"), "video" to video.toString())
}

data class WorkerRun(val kind: WorkerKind, val retry: Boolean, val millis: Long) : LogEvent {
    override val tag get() = "worker"
    override fun fields() = listOf("kind" to kind.name, "retry" to retry.toString(), "ms" to millis.coerceAtLeast(0).toString())
}

data class SessionEvent(val change: SessionChange) : LogEvent {
    override val tag get() = "session"
    override fun fields() = listOf("change" to change.name)
}

/**
 * The words a log line can consist of. A line read back from the file must be built from exactly these (a tag, then `key=value` pairs
 * with identifier-like values), so a file that somebody edited cannot add sentences, addresses or names to an export.
 */
object LogVocabulary {
    val tags = setOf("app_start", "sync", "push_received", "push_endpoint", "push_test", "realtime", "send_failed", "outbox", "call", "worker", "session")
    val keys = setOf(
        "version", "sdk", "trigger", "applied", "result", "status", "worker", "uploaded", "state", "chat", "reason", "error", "permanent",
        "sent", "failed", "pending", "offline", "phase", "video", "kind", "retry", "ms", "change",
    )
    private val value = Regex("[A-Za-z0-9_.-]{0,40}")

    /** [line] if it is made of known words only, else null. */
    fun accept(line: String): String? {
        val parts = line.trim().split(' ')
        if (parts.isEmpty() || parts.first() !in tags) return null
        for (pair in parts.drop(1)) {
            val key = pair.substringBefore('=', "")
            val v = pair.substringAfter('=', "\u0000")
            if (key !in keys || !value.matches(v)) return null
        }
        return parts.joinToString(" ")
    }
}
