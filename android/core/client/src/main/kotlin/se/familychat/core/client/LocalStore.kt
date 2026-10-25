package se.familychat.core.client

import se.familychat.core.model.Conversation
import se.familychat.core.model.Device
import se.familychat.core.model.User

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
}

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
    /** `message|edit|delete` */
    val kind: String,
    val targetMessageId: String?,
    val text: String?,
    val replyTo: String?,
    val attachments: List<OutboxAttachment>,
    val clientTs: Long,
    /** Ciphertexts already produced for this message, reused on retry so the ratchet is not burnt again. */
    val envelopes: List<se.familychat.core.model.Envelope>? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
)

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
     * Purely local; nothing is sent. The tombstone is removed with the conversation.
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
