package se.swahnchat.core.client.call

/** Why a call ended; shown by the UI and written to the call log by part B. */
enum class EndReason {
    /** This device hung up (or rejected on another own device, see [REJECTED]). */
    HANGUP_LOCAL,

    /** The other side hung up while the call was connecting or running. */
    HANGUP_REMOTE,

    /** The callee rejected the call, or this account rejected it (here or on another own device). */
    REJECTED,

    /** The callee was in another call. */
    BUSY,

    /** Outgoing: nobody answered within the ring time. */
    NO_ANSWER,

    /** Incoming: not answered within the ring time, or the caller gave up first. */
    MISSED,

    /** Incoming: another device of this account answered. */
    ANSWERED_ELSEWHERE,

    /** The media connection could not be set up, or broke for good. */
    FAILED,
}

/** The state of the one call a [CallManager] can have. [Ended] is shown briefly and then replaced by [Idle]. */
sealed interface CallState {
    data object Idle : CallState

    /** A call in progress; [peerUserId] is the other member of the direct conversation. */
    sealed interface InCall : CallState {
        val callId: String
        val conversationId: String
        val peerUserId: String
        val video: Boolean
    }

    /** We called and the callee's devices ring. */
    data class Outgoing(
        override val callId: String,
        override val conversationId: String,
        override val peerUserId: String,
        override val video: Boolean,
    ) : InCall

    /** Someone called us; accept with [CallManager.accept] or [CallManager.reject]. */
    data class Incoming(
        override val callId: String,
        override val conversationId: String,
        override val peerUserId: String,
        override val video: Boolean,
    ) : InCall

    /** Answered, the media connection is being established. [outgoing] is true for the caller. */
    data class Connecting(
        override val callId: String,
        override val conversationId: String,
        override val peerUserId: String,
        override val video: Boolean,
        val outgoing: Boolean,
    ) : InCall

    /** Media flows; [since] is the clock time (ms) it connected. */
    data class Connected(
        override val callId: String,
        override val conversationId: String,
        override val peerUserId: String,
        override val video: Boolean,
        val outgoing: Boolean,
        val since: Long,
    ) : InCall

    data class Ended(val callId: String, val reason: EndReason) : CallState
}
