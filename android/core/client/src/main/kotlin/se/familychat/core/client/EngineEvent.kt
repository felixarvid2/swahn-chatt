package se.familychat.core.client

import se.familychat.core.network.LogoutReason

/**
 * Things the UI layer may want to react to. The authoritative state is always in [LocalStore]; events are only
 * prompts to re-query it (and to raise notifications).
 */
sealed interface EngineEvent {
    /** A new incoming message (or decrypt-error placeholder) was stored. */
    data class MessageReceived(val conversationId: String, val messageId: String, val fromSelfOtherDevice: Boolean) : EngineEvent

    /** An existing message changed (edit, delete, status, attachment state). */
    data class MessageChanged(val conversationId: String, val messageId: String) : EngineEvent

    data class ConversationChanged(val conversationId: String) : EngineEvent
    data class ConversationRemoved(val conversationId: String) : EngineEvent

    /** Users/devices directory changed. */
    data class DirectoryChanged(val userId: String?) : EngineEvent

    /**
     * A device is not trusted: its identity key (or owner) differs from the pinned one, or it is a new device next to
     * verified ones and waits for approval. Messages from/to it are blocked until the user calls
     * `FamilyChatEngine.acceptIdentity`. [newIdentityKey] is the serialized key that was presented.
     */
    class IdentityChanged(val userId: String, val deviceId: String, val newIdentityKey: ByteArray?) : EngineEvent

    /** A sync run finished; [applied] events were processed. */
    data class SyncCompleted(val applied: Int) : EngineEvent

    /** The server rejected our session: show the login screen (then call `logout()` to wipe local data). */
    data class SessionEnded(val reason: LogoutReason) : EngineEvent
}

/** Who is typing where (cleared automatically after a few seconds without refresh). */
typealias TypingMap = Map<String, Set<String>>
