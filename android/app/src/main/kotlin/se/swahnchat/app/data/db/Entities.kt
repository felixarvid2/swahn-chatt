package se.swahnchat.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
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
    /** Added in version 5: sent on from another conversation. */
    @ColumnInfo(defaultValue = "0") val forwarded: Boolean = false,
    /** Added in version 5: merged in from an exported history file. */
    @ColumnInfo(defaultValue = "0") val imported: Boolean = false,
    /** Added in version 5: seconds the message lives once displayed (the sender's encrypted `expires_in`), null: no timer. */
    val expiresIn: Long? = null,
    /** Added in version 5: when it is removed from this phone (epoch ms); set when it is first displayed here. */
    val expireAt: Long? = null,
    /** Added in version 5: the number a system notice is about (the new timer in seconds for `timer_changed`). */
    val systemValue: Long? = null,
    /** Added in version 6: the users mentioned with `@`, as `,id1,id2,` (so a query can look for `,id,`), empty: none. */
    @ColumnInfo(defaultValue = "''") val mentions: String = "",
    /** Added in version 6: the poll this message is, as JSON ([PollJson]); null: not a poll. The question is [text]. */
    val poll: String? = null,
    /** Added in version 6: an outgoing message waiting for its time (epoch ms); null: not scheduled. */
    val scheduledAt: Long? = null,
    /** Added in version 7: the shared position this message is, as JSON ([LocationSpec]); null: not a position. */
    val location: String? = null,
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
    /** Added in version 5. */
    @ColumnInfo(defaultValue = "0") val forwarded: Boolean = false,
    /** Added in version 5: content `expires_in` of a message; the new timer of a `timer` entry. */
    val expiresIn: Long? = null,
    /** Added in version 5: a reaction that takes the emoji back. */
    @ColumnInfo(defaultValue = "0") val remove: Boolean = false,
    /** Added in version 6: the mentions of a group message, as in [MessageEntity.mentions]. */
    @ColumnInfo(defaultValue = "''") val mentions: String = "",
    /** Added in version 6: JSON of the poll spec of a poll message. */
    val poll: String? = null,
    /** Added in version 6: the option ids of a poll vote, comma separated (empty string: withdrawn); null: not a vote. */
    val optionIds: String? = null,
    /** Added in version 6: a poll vote that closes the poll. */
    @ColumnInfo(defaultValue = "0") val close: Boolean = false,
    /** Added in version 6: a scheduled message is not sent before this time (epoch ms). */
    val sendAt: Long? = null,
    /** Added in version 7: JSON of the [LocationSpec] of a position message. */
    val location: String? = null,
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

/**
 * Full-text index over the decrypted message text (added in database version 3). An external-content FTS4 table: the
 * text is not stored twice, the index only holds the tokens and refers to `messages` by rowid; Room keeps it in step
 * with triggers (so every insert, edit and delete of a message, however it is written, updates the index). It lives in
 * the same SQLCipher file as the messages. The `unicode61` tokenizer folds case for all letters (the default `simple`
 * one only for ASCII, which would make a search for "åsa" miss "Åsa"); diacritics are kept (`far` is not `får`).
 */
@Fts4(contentEntity = MessageEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=0"])
@Entity(tableName = "message_fts")
data class MessageFtsEntity(val text: String?)

/**
 * Local-only settings of a conversation (added in database version 3): pinned, muted, archived. A separate table, like
 * [DraftEntity], because the sync rewrites the `conversations` rows. No row means the defaults.
 */
@Entity(tableName = "conversation_settings")
data class ConversationSettingsEntity(
    @PrimaryKey val conversationId: String,
    /** When the conversation was pinned (null: not pinned). */
    val pinnedAt: Long?,
    /** Muted until this time in epoch milliseconds; [MUTED_ALWAYS] for good, 0 or in the past: not muted. */
    val mutedUntil: Long,
    val archived: Boolean,
) {
    companion object {
        const val MUTED_ALWAYS = Long.MAX_VALUE
    }
}

/**
 * When a member acknowledged delivery of / read one of our messages (added in database version 4), from the `created_at`
 * of the receipt event. Status itself stays in `messages.deliveredTo` / `readBy`; this table only adds the times, so rows
 * from before version 4 simply have none. [conversationId] lets a removed conversation take its rows along.
 */
@Entity(tableName = "message_receipts", primaryKeys = ["messageId", "userId"], indices = [Index("conversationId")])
data class MessageReceiptEntity(
    val messageId: String,
    val userId: String,
    val conversationId: String,
    val deliveredAt: Long?,
    val readAt: Long?,
)

/**
 * Tombstone of a message the user removed with "delete for me" (added in database version 4). The message row is gone; this
 * row stops a later sync from storing it again. Purely local, never sent anywhere, removed with the conversation.
 */
@Entity(tableName = "local_deletions", indices = [Index("conversationId")])
data class LocalDeletionEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val deletedAt: Long,
)

/**
 * A member's reaction to a message (added in database version 5). One row per member and message; the highest `seq` wins and
 * an [emoji] of null means it was taken back (the row stays so an older event cannot bring it back). [seq] is null while our
 * own reaction waits in the outbox. Removed with the message and with the conversation.
 */
@Entity(tableName = "reactions", primaryKeys = ["messageId", "userId"], indices = [Index("conversationId")])
data class ReactionEntity(
    val messageId: String,
    val userId: String,
    val conversationId: String,
    val emoji: String?,
    val seq: Long?,
    val at: Long,
)

/**
 * The disappearing-messages timer of a conversation as this phone applies it (added in database version 5). Written only
 * from encrypted `timer` content of a member (or by this phone for its own change), never from the server's
 * `disappearing_seconds`, which is not stored at all. A separate table, like [ConversationSettingsEntity], because the sync
 * rewrites the `conversations` rows.
 */
@Entity(tableName = "conversation_timers")
data class ConversationTimerEntity(
    @PrimaryKey val conversationId: String,
    val seconds: Long,
    val setBy: String,
    val seq: Long?,
    val at: Long,
)

/**
 * A member's vote on a poll (added in database version 6). One row per member and poll; the highest `seq` wins; [optionIds] is
 * the comma-separated list of chosen options (empty: withdrawn). [seq] is null while our own vote waits in the outbox. Removed
 * with the message and with the conversation.
 */
@Entity(tableName = "poll_votes", primaryKeys = ["messageId", "userId"], indices = [Index("conversationId")])
data class PollVoteEntity(
    val messageId: String,
    val userId: String,
    val conversationId: String,
    val optionIds: String,
    val seq: Long?,
    val at: Long,
)

/**
 * A starred message (added in database version 6). Purely local, never sent anywhere. The row goes with the message (deleted
 * for everyone, for me, expired) and with the conversation.
 */
@Entity(tableName = "starred_messages", indices = [Index("conversationId")])
data class StarredMessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val starredAt: Long,
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
