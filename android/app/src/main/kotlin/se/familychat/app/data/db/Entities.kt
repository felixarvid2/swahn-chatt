package se.familychat.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Room tables. `LocalStore` rows are mapped by Mappers.kt; `signal_*` tables hold libsignal's opaque records for
// ProtocolStorage; `tokens` is read synchronously by OkHttp threads (TokenProvider).

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: Int = 1,
    val serverUrl: String,
    val userId: String,
    val deviceId: String,
    val deviceNumber: Int,
    val username: String,
    val displayName: String,
    val isAdmin: Boolean,
    val deviceName: String,
)

@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "tokens")
data class TokenEntity(
    @PrimaryKey val id: Int = 1,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val id: Int = 1, val cursor: Long)

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val avatarAttachmentId: String?,
    val isAdmin: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "devices", indices = [Index("userId")])
data class DeviceEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val number: Int,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val identityKey: String?,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String?,
    val avatarAttachmentId: String?,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessageAt: Long?,
    val lastMessageSeq: Long?,
    /** The server's value, but never moved backwards locally. */
    val myReadSeq: Long,
)

@Entity(tableName = "conversation_members", primaryKeys = ["conversationId", "userId"], indices = [Index("userId")])
data class MemberEntity(
    val conversationId: String,
    val userId: String,
    val role: String,
    val joinedAt: Long,
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId", "seq"]), Index(value = ["senderUserId", "senderDeviceId"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: String?,
    val outgoing: Boolean,
    val status: String,
    val seq: Long?,
    val clientTs: Long,
    val serverTs: Long?,
    val text: String?,
    val replyTo: String?,
    /** JSON list of attachments ([AttachmentJson]). */
    val attachments: String,
    val edited: Boolean,
    val editedAt: Long?,
    val deleted: Boolean,
    val decryptError: String?,
    val failureReason: String?,
    /** User ids, comma separated (UUIDs contain no commas). */
    val deliveredTo: String,
    val readBy: String,
    /** `new_device` / `new_device_blocked` / `identity_changed` / `member_added` / `member_removed` for system notices, else null. */
    val systemType: String?,
    val systemUserId: String?,
    /** The device UUID a device notice is about (never the server's device number). */
    val systemDeviceId: String?,
    val systemDeviceName: String?,
    val pendingEnvelopeType: String?,
    val pendingEnvelopeBody: String?,
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val kind: String,
    val targetMessageId: String?,
    val text: String?,
    val replyTo: String?,
    /** JSON list of [OutboxAttachmentJson]. */
    val attachments: String,
    val clientTs: Long,
    /** JSON list of envelopes, null when none were produced yet. */
    val envelopes: String?,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
)

/**
 * The unsent composer text of a conversation (added in database version 2). A separate table, not a column of
 * `conversations`: the sync rewrites conversation rows and must never touch what the user is typing.
 */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val conversationId: String,
    val text: String,
    val updatedAt: Long,
)

// --- libsignal state (ProtocolStorage) ---------------------------------------------------------------------------

@Entity(tableName = "signal_identity")
class SignalIdentityEntity(
    @PrimaryKey val id: Int = 1,
    val identityKeyPair: ByteArray,
    val registrationId: Int,
)

@Entity(tableName = "signal_prekeys")
class SignalPreKeyEntity(@PrimaryKey val id: Int, val record: ByteArray)

@Entity(tableName = "signal_signed_prekeys")
class SignalSignedPreKeyEntity(@PrimaryKey val id: Int, val record: ByteArray)

@Entity(tableName = "signal_kyber_prekeys")
class SignalKyberPreKeyEntity(@PrimaryKey val id: Int, val record: ByteArray, val lastResort: Boolean)

/** Uses of last-resort Kyber keys: the primary key makes "insert if absent" atomic. */
@Entity(tableName = "signal_last_resort_used", primaryKeys = ["kyberId", "signedId", "baseKeyHex"])
class SignalLastResortUsedEntity(val kyberId: Int, val signedId: Int, val baseKeyHex: String)

/** A Signal session; [name] is `"<userId>/<deviceUuid>"` (see `DeviceAddress`). */
@Entity(tableName = "signal_sessions")
class SignalSessionEntity(@PrimaryKey val name: String, val record: ByteArray)

/** The pinned identity of a remote device, keyed by the device UUID; [userId] is the owner it was pinned for. */
@Entity(tableName = "signal_identities", indices = [Index("userId")])
class SignalRemoteIdentityEntity(
    @PrimaryKey val deviceId: String,
    val userId: String,
    val identityKey: ByteArray,
    val verified: Boolean,
    val approved: Boolean,
)

@Entity(tableName = "signal_counters")
data class SignalCounterEntity(@PrimaryKey val kind: String, val next: Int)
