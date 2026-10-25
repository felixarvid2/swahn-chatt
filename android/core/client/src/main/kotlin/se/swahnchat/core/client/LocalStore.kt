package se.swahnchat.core.client

import se.swahnchat.core.model.Conversation
import se.swahnchat.core.model.Device
import se.swahnchat.core.model.User

/** The signed-in account of this installation. Tokens are NOT here; they live behind `TokenProvider`. */
data class Account(
    val serverUrl: String,
    val userId: String,
    val deviceId: String,
    /** Device number assigned by the server (libsignal device id). */
    val deviceNumber: Int,
    val username: String,
    val displayName: String,
    val isAdmin: Boolean,
    val deviceName: String,
)

/** Delivery state of an outgoing message; incoming messages are [RECEIVED]. */
enum class MessageStatus {
    /** Written locally, not yet accepted by the server (in the outbox). */
    PENDING,

    /** The server stored it (it has a `seq`). */
    SENT,

    /** Every other member's device(s) acknowledged it. */
    DELIVERED,

    /** Every other member read up to it. */
    READ,

    /** Permanently not sent; see [StoredMessage.failureReason]. The user may retry. */
    FAILED,

    /** Incoming. */
    RECEIVED,
}

/** Why an incoming message shows "could not decrypt". */
enum class DecryptError {
    /** Corrupt or forged ciphertext, or bad padding/JSON. Not recoverable. */
    CORRUPT,

    /** Already processed earlier but its plaintext was not stored (crash window). Not recoverable. */
    DUPLICATE,

    /** No session for a non-prekey message. Not recoverable (ask the sender to resend). */
    NO_SESSION,

    /** The sender's identity key changed and has not been accepted. Recoverable via `acceptIdentity`. */
    UNTRUSTED_IDENTITY,

    /** The sender's device is not known (yet), or not listed for the user the message claims to come from. */
    UNKNOWN_SENDER_DEVICE,
}

enum class DownloadState { NONE, DOWNLOADING, DONE, FAILED }

/** Reason an outgoing message ended in [MessageStatus.FAILED]. */
enum class FailureReason { IDENTITY_CHANGED, REJECTED, NOT_A_MEMBER, TOO_LARGE, ATTACHMENT_MISSING, TOO_MANY_RETRIES }

/**
 * An attachment of a message. For outgoing messages [id], [key] and [sha256] are filled once the upload finished
 * (until then [id] is a local placeholder starting with `local-`).
 */
data class LocalAttachment(
    val id: String,
    /** base64 of the raw 32-byte key; null until uploaded for outgoing attachments. */
    val key: String?,
    /** hex SHA-256 of the ciphertext; null until uploaded. */
    val sha256: String?,
    /** Plaintext size in bytes. */
    val size: Long,
    val mime: String,
    val name: String?,
    /** `image|video|voice|file` */
    val kind: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    /** base64 small JPEG, optional. */
    val thumbnail: String?,
    /** Decrypted file (or, for outgoing, the source file) on this device. */
    val localPath: String?,
    val downloadState: DownloadState,
)

/** How a call ended, for the history notice ([SystemEvent.Call]). */
enum class CallOutcome {
    /** The call connected; the notice carries its duration. */
    COMPLETED,

    /** Incoming: not answered here. */
    MISSED,

    /** Outgoing: nobody answered. */
    NO_ANSWER,

    /** Outgoing: the other side was in another call. */
    BUSY,

    /** Declined, by the other side (outgoing) or here (incoming). */
    REJECTED,
}

/**
 * A notice inserted into a conversation by the engine instead of a real message. Devices are named by their immutable
 * server UUID ([deviceId]); the server's device number is never used.
 */
sealed interface SystemEvent {
    /**
     * [userId] (possibly this account itself) has a new device ([deviceName]). With [needsApproval] the user has verified
     * devices of [userId], so the new one is blocked until the user accepts it in the safety screen.
     */
    data class NewDevice(
        val userId: String,
        val deviceId: String,
        val deviceName: String,
        val needsApproval: Boolean = false,
    ) : SystemEvent

    /** The identity key (or owner) of a device changed and is not trusted until accepted. */
    data class IdentityChanged(val userId: String, val deviceId: String) : SystemEvent

    /** [userId] appeared in the member list of a group. */
    data class MemberAdded(val userId: String) : SystemEvent

    /** [userId] disappeared from the member list of a group (left or was removed). */
    data class MemberRemoved(val userId: String) : SystemEvent

    /**
     * [userId] set the disappearing-messages timer to [seconds] (0: off). Only ever created from encrypted `timer` content
     * of a member (or by this device for its own change), never from the server's `disappearing_seconds`.
     */
    data class TimerChanged(val userId: String, val seconds: Long) : SystemEvent

    /**
     * A call with [peerUserId] ended: [outcome], and for [CallOutcome.COMPLETED] how long it lasted ([durationSeconds]).
     * Written by this phone alone when the call ends (never sent), one per call.
     */
    data class Call(val peerUserId: String, val outcome: CallOutcome, val video: Boolean, val durationSeconds: Long = 0) : SystemEvent

    /** Content of a newer app version was received and ignored: "Update the app to see everything". At most once per conversation. */
    data object UpdateRequired : SystemEvent
}

/**
 * One member's reaction to one message. A member has at most one per message; the one with the highest [seq] wins.
 * [emoji] is null when the reaction was taken back (the row stays so an older event cannot bring it back). [seq] is null
 * while our own change waits in the outbox (it then wins over everything stored).
 */
data class Reaction(
    val messageId: String,
    val conversationId: String,
    val userId: String,
    val emoji: String?,
    val seq: Long?,
    val at: Long,
)

/**
 * The disappearing-messages timer of a conversation as this phone knows it: [seconds] (0: off), set by [setBy] with the
 * encrypted `timer` message numbered [seq] (null while ours is not sent yet). Never derived from the server's field.
 */
data class ConversationTimer(val conversationId: String, val seconds: Long, val setBy: String, val seq: Long?, val at: Long)

/**
 * A message in a conversation as the UI sees it. Ordering is by [seq] (server order), pending messages (null seq)
 * last by [clientTs].
 */
data class StoredMessage(
    /** Client UUID, also the server's idempotency key. System notices use `sys-<uuid>`. */
    val id: String,
    val conversationId: String,
    val senderUserId: String,
    /** The sending device (server id); null for system notices. */
    val senderDeviceId: String?,
    /** True when sent by this account (from this or another of its devices). */
    val outgoing: Boolean,
    val status: MessageStatus,
    val seq: Long?,
    val clientTs: Long,
    val serverTs: Long?,
    val text: String?,
    val replyTo: String?,
    val attachments: List<LocalAttachment> = emptyList(),
    val edited: Boolean = false,
    val editedAt: Long? = null,
    /** Content wiped (by the sender's delete). */
    val deleted: Boolean = false,
    val decryptError: DecryptError? = null,
    val failureReason: FailureReason? = null,
    /** Users whose devices acknowledged delivery (outgoing messages). */
    val deliveredTo: Set<String> = emptySet(),
    /** Users who read it (outgoing messages). */
    val readBy: Set<String> = emptySet(),
    val systemEvent: SystemEvent? = null,
    /**
     * The undecrypted envelope kept for [DecryptError.UNTRUSTED_IDENTITY] so decryption can be retried after the
     * user accepted the new identity. Cleared once decrypted.
     */
    val pendingEnvelopeType: String? = null,
    val pendingEnvelopeBody: String? = null,
    /** Sent on to this conversation from another one ("Vidarebefordrat"); set by the sender inside the encrypted content. */
    val forwarded: Boolean = false,
    /** Merged in from an exported history file ("Importerad"); never sent anywhere. */
    val imported: Boolean = false,
    /** Seconds this message lives once displayed, from the sender's encrypted content; null: it stays. */
    val expiresIn: Long? = null,
    /** When it is removed from this phone (epoch ms): set when first displayed here, `displayed + expiresIn`. Null until then. */
    val expireAt: Long? = null,
) {
    val isSystem: Boolean get() = systemEvent != null
}

/**
 * When [userId] acknowledged delivery of / read the outgoing message [messageId], as far as this device knows: the
 * `created_at` of the receipt event (server time, milliseconds). Null where no receipt carried a time (messages from before
 * the times were recorded, or a read receipt, which also implies delivery, that arrived without a delivered one).
 * Receipts the server merged while this device was offline carry the time of the merge, so a time is "not before".
 */
data class ReceiptTimes(val messageId: String, val userId: String, val deliveredAt: Long?, val readAt: Long?)

/** An attachment waiting to be uploaded as part of an outgoing message. */
data class OutboxAttachment(
    val localPath: String,
    val mime: String,
    val name: String?,
    val kind: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val thumbnail: String?,
    /** Filled after a successful upload, so a retry does not upload again. */
    val uploadedId: String? = null,
    val uploadedKey: String? = null,
    val uploadedSha256: String? = null,
    val plaintextSize: Long? = null,
)

/**
 * A message (or edit/delete) waiting to be sent. The id is final from the first moment so every retry carries the same
 * idempotency key.
 */
data class OutboxEntry(
    val messageId: String,
    val conversationId: String,
    /** `message|edit|delete|reaction`, or [OutboxKind.TIMER] (sent as a `message` whose content is a timer change). */
    val kind: String,
    val targetMessageId: String?,
    val text: String?,
    val replyTo: String?,
    val attachments: List<OutboxAttachment>,
    val clientTs: Long,
    /** Ciphertexts already produced for this message, reused on retry so the ratchet is not burnt again. */
    val envelopes: List<se.swahnchat.core.model.Envelope>? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    /** Content `forwarded`. */
    val forwarded: Boolean = false,
    /**
     * `expires_in` of the content of a message, copied from the conversation's timer when it was written; for [OutboxKind.TIMER]
     * the new timer in seconds. Fixed at that moment so a retry sends the same content.
     */
    val expiresIn: Long? = null,
    /** Reaction ([text] is the emoji): true when it is taken back. */
    val remove: Boolean = false,
)

/** Outbox kinds that are not a server message kind. */
object OutboxKind {
    /** A change of the disappearing-messages timer: encrypted `timer` content in a `message`. */
    const val TIMER = "timer"
}

/**
 * Persistence for the messaging engine; the app implements it on Room (SQLCipher). All functions are `suspend` so they
 * can use Room's coroutine support. [InMemoryLocalStore] is the reference implementation and test double.
 *
 * Implementation notes:
 * - Functions that take a lambda ([updateMessage], [transaction]) must run it atomically with respect to other store
 *   calls (Room: `withTransaction`).
 * - The engine never decrypts inside [transaction]. Where decryption and the message write must be atomic (so a crash
 *   cannot leave an advanced ratchet without its plaintext) it uses [blockingTransaction] instead.
 * - Message order: by `seq` ascending, rows without `seq` after them by `clientTs`, then `id`.
 */
interface LocalStore {
    // --- account ------------------------------------------------------------------------------------------------------
    suspend fun getAccount(): Account?
    suspend fun saveAccount(account: Account)

    /** Deletes every row of every table (logout). */
    suspend fun clearAll()

    /** Small key/value bag for engine bookkeeping. */
    suspend fun getMeta(key: String): String?
    suspend fun setMeta(key: String, value: String?)

    /** Runs [block] atomically. */
    suspend fun <T> transaction(block: suspend () -> T): T

    /**
     * Runs the **blocking** [block] on the calling thread inside one database transaction, which is committed when the
     * block returns and rolled back when it throws.
     *
     * An implementation whose `ProtocolStorage` lives in the same database makes the blocking `ProtocolStorage` calls
     * (libsignal persists its ratchet through them) and the store calls made from the block on this thread part of that
     * transaction. The block may call `runBlocking { store.xyz() }`: the store must then run the call inline on this
     * thread, without switching to another one, and without starting a second transaction.
     * The engine uses this to decrypt a message and write it as one atomic step; the caller holds
     * `MessageCrypto.exclusive` around the call (lock order: crypto lock, then transaction).
     *
     * The default does not provide atomicity (it just runs the block); [InMemoryLocalStore] keeps it.
     */
    fun <T> blockingTransaction(block: () -> T): T = block()

    // --- directory ----------------------------------------------------------------------------------------------------
    suspend fun upsertUsers(users: List<User>)
    suspend fun getUser(id: String): User?
    suspend fun getUsers(): List<User>

    /**
     * Replaces the device list of [userId] (devices not listed are gone). Only `DeviceDirectory.refreshDevices` calls
     * this: it is the one place that pins identities and posts "new device" notices.
     */
    suspend fun replaceDevices(userId: String, devices: List<Device>)
    suspend fun getDevices(userId: String): List<Device>

    /** The device with server id [deviceId], looked up across all users. */
    suspend fun findDevice(deviceId: String): Device?

    // --- conversations ------------------------------------------------------------------------------------------------
    /** Stores the server's view. Unread counts in the DTO are ignored; use [unreadCount]. */
    suspend fun upsertConversation(conversation: Conversation)
    suspend fun getConversation(id: String): Conversation?

    /** Newest activity first (`last_message_seq`/`updated_at`). */
    suspend fun getConversations(): List<Conversation>

    /** Removes the conversation, its messages and its outbox entries. */
    suspend fun removeConversation(id: String)

    /** Incoming, non-deleted, non-system messages with `seq` greater than the conversation's `myReadSeq`. */
    suspend fun unreadCount(conversationId: String): Int
    suspend fun setMyReadSeq(conversationId: String, seq: Long)

    // --- messages -----------------------------------------------------------------------------------------------------
    suspend fun getMessage(id: String): StoredMessage?

    /** Insert or replace by id. */
    suspend fun upsertMessage(message: StoredMessage)

    /** Atomic read-modify-write; returns the new value, or null if the message does not exist. */
    suspend fun updateMessage(id: String, update: (StoredMessage) -> StoredMessage): StoredMessage?

    /** Newest [limit] messages (optionally only those with seq below [beforeSeq]), returned oldest first. */
    suspend fun getMessages(conversationId: String, limit: Int = 50, beforeSeq: Long? = null): List<StoredMessage>

    /** Outgoing messages of the conversation with `seq <= upToSeq` not yet read by [userId]. */
    suspend fun outgoingNotReadBy(conversationId: String, userId: String, upToSeq: Long): List<StoredMessage>

    /**
     * Messages that have attachments (all of them, or only those of [conversationId]); for the storage screen and for
     * clearing downloaded media.
     */
    suspend fun messagesWithAttachments(conversationId: String? = null): List<StoredMessage>

    // --- reactions and timers -----------------------------------------------------------------------------------------
    suspend fun getReaction(messageId: String, userId: String): Reaction?

    /** Insert or replace the member's reaction row (the caller decided it is the newest). */
    suspend fun putReaction(reaction: Reaction)

    /** Forgets the member's row for [messageId] altogether (an unsent reaction that failed for good). */
    suspend fun removeReaction(messageId: String, userId: String)

    /** Forgets every reaction to [messageId] (the message was deleted). */
    suspend fun removeReactions(messageId: String)

    /** The reactions that stand (not taken back) on [messageId]. */
    suspend fun getReactions(messageId: String): List<Reaction>
    suspend fun getTimer(conversationId: String): ConversationTimer?
    suspend fun putTimer(timer: ConversationTimer)

    // --- disappearing messages ----------------------------------------------------------------------------------------
    /** Messages whose [StoredMessage.expireAt] is at or before [now], except those still waiting to be sent. */
    suspend fun expiredMessages(now: Long, limit: Int = 500): List<StoredMessage>

    /** The earliest [StoredMessage.expireAt] of any message that has one, or null. */
    suspend fun nextExpiry(): Long?

    /**
     * Called after messages were removed for good (disappearing messages): a store with a search index merges it so that the
     * words of the removed messages do not linger in the file. Nothing to do for a store without one.
     */
    suspend fun compactSearchIndex() {}

    // --- receipt times and local deletions ----------------------------------------------------------------------------
    /**
     * Remembers when [userId] acknowledged ([deliveredAt]) or read ([readAt]) [messageId]. A time already stored is kept
     * (the first receipt wins), null leaves the stored value alone.
     */
    suspend fun recordReceipt(conversationId: String, messageId: String, userId: String, deliveredAt: Long?, readAt: Long?)
    suspend fun getReceipts(messageId: String): List<ReceiptTimes>

    /**
     * "Delete for me": removes the message (and its receipt times) from this device and remembers that it was removed, so a
     * replayed event cannot bring it back ([isLocallyDeleted]). Returns the removed message, or null if there was none.
     * Purely local; nothing is sent. The tombstone is removed with the conversation. Also used to remember content that was
     * consumed without being stored (timer changes, content of a newer app), so a replay is skipped before decryption.
     * The message's reactions go with it.
     */
    suspend fun deleteMessageLocally(id: String, conversationId: String, at: Long): StoredMessage?
    suspend fun isLocallyDeleted(id: String): Boolean

    /** Messages whose `pendingEnvelopeBody` is set and whose sender is [userId] on [deviceId] (for retrying decryption). */
    suspend fun undecryptedFrom(userId: String, deviceId: String): List<StoredMessage>

    // --- outbox -------------------------------------------------------------------------------------------------------
    suspend fun putOutbox(entry: OutboxEntry)
    suspend fun getOutbox(): List<OutboxEntry>
    suspend fun getOutboxEntry(messageId: String): OutboxEntry?
    suspend fun removeOutbox(messageId: String)

    // --- sync ---------------------------------------------------------------------------------------------------------
    suspend fun getSyncCursor(): Long
    suspend fun setSyncCursor(cursor: Long)
}
