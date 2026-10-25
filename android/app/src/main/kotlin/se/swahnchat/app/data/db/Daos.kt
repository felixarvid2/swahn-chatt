package se.swahnchat.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val MAX_LONG = "9223372036854775807"

/** Everything `LocalStore` needs (suspending). */
@Dao
interface StoreDao {
    // --- account / meta / cursor ----------------------------------------------------------------------------------
    @Query("SELECT * FROM account WHERE id = 1") suspend fun account(): AccountEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccount(a: AccountEntity)

    @Query("SELECT value FROM meta WHERE `key` = :key") suspend fun meta(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(m: MetaEntity)
    @Query("DELETE FROM meta WHERE `key` = :key") suspend fun deleteMeta(key: String)

    @Query("SELECT cursor FROM sync_state WHERE id = 1") suspend fun cursor(): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCursor(s: SyncStateEntity)

    // --- directory ------------------------------------------------------------------------------------------------
    @Upsert suspend fun upsertUsers(users: List<UserEntity>)
    @Query("SELECT * FROM users WHERE id = :id") suspend fun user(id: String): UserEntity?
    @Query("SELECT * FROM users ORDER BY rowid") suspend fun users(): List<UserEntity>

    @Query("DELETE FROM devices WHERE userId = :userId") suspend fun deleteDevices(userId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDevices(devices: List<DeviceEntity>)
    @Query("SELECT * FROM devices WHERE userId = :userId ORDER BY number") suspend fun devices(userId: String): List<DeviceEntity>
    @Query("SELECT * FROM devices WHERE id = :id") suspend fun device(id: String): DeviceEntity?

    // --- conversations --------------------------------------------------------------------------------------------
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun conversation(id: String): ConversationEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConversation(c: ConversationEntity)
    @Query("DELETE FROM conversation_members WHERE conversationId = :id") suspend fun deleteMembers(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMembers(members: List<MemberEntity>)
    @Query("SELECT * FROM conversation_members WHERE conversationId = :id ORDER BY joinedAt, userId")
    suspend fun members(id: String): List<MemberEntity>

    @Query("SELECT * FROM conversation_members ORDER BY conversationId, joinedAt, userId")
    suspend fun allMembers(): List<MemberEntity>

    @Query("SELECT * FROM conversations ORDER BY COALESCE(lastMessageSeq, -$MAX_LONG - 1) DESC, updatedAt DESC, id")
    suspend fun conversations(): List<ConversationEntity>

    @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteConversation(id: String)
    @Query("DELETE FROM messages WHERE conversationId = :id") suspend fun deleteMessagesOf(id: String)
    @Query("DELETE FROM outbox WHERE conversationId = :id") suspend fun deleteOutboxOf(id: String)

    // --- drafts ---------------------------------------------------------------------------------------------------
    @Query("SELECT text FROM drafts WHERE conversationId = :id") suspend fun draft(id: String): String?
    @Upsert suspend fun putDraft(d: DraftEntity)
    @Query("DELETE FROM drafts WHERE conversationId = :id") suspend fun deleteDraft(id: String)

    // --- pinned / muted / archived (local only) -------------------------------------------------------------------
    @Query("SELECT * FROM conversation_settings WHERE conversationId = :id") suspend fun settings(id: String): ConversationSettingsEntity?
    @Upsert suspend fun putSettings(s: ConversationSettingsEntity)
    @Query("DELETE FROM conversation_settings WHERE conversationId = :id") suspend fun deleteSettings(id: String)

    /** Drops the row when nothing is set any more (the defaults need no row). */
    @Query("DELETE FROM conversation_settings WHERE conversationId = :id AND pinnedAt IS NULL AND mutedUntil = 0 AND archived = 0")
    suspend fun deleteSettingsIfDefault(id: String)

    @Query("SELECT COUNT(*) FROM conversation_settings WHERE pinnedAt IS NOT NULL AND conversationId != :except")
    suspend fun pinnedCountExcept(except: String): Int

    /** A new incoming message brings an archived conversation back, unless it is muted. */
    @Query("UPDATE conversation_settings SET archived = 0 WHERE conversationId = :id AND archived = 1 AND mutedUntil <= :now")
    suspend fun unarchiveUnlessMuted(id: String, now: Long)

    @Query(
        "SELECT COUNT(*) FROM messages m WHERE m.conversationId = :id AND m.outgoing = 0 AND m.deleted = 0 " +
            "AND m.systemType IS NULL AND m.imported = 0 AND COALESCE(m.seq, 0) > COALESCE((SELECT myReadSeq FROM conversations WHERE id = :id), 0)",
    )
    suspend fun unreadCount(id: String): Int

    @Query("UPDATE conversations SET myReadSeq = :seq WHERE id = :id AND myReadSeq < :seq")
    suspend fun raiseReadSeq(id: String, seq: Long)

    // --- messages -------------------------------------------------------------------------------------------------
    @Query("SELECT * FROM messages WHERE id = :id") suspend fun message(id: String): MessageEntity?
    /**
     * Insert or update by id. Not `REPLACE`: SQLite's REPLACE deletes the old row without firing the delete trigger and
     * gives the new row another rowid, which would leave the old text in the search index (`message_fts`).
     */
    @Upsert suspend fun putMessage(m: MessageEntity)

    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId " +
            "AND (:beforeSeq IS NULL OR COALESCE(seq, $MAX_LONG) < :beforeSeq) " +
            "ORDER BY COALESCE(seq, $MAX_LONG) DESC, clientTs DESC, id DESC LIMIT :limit) " +
            "ORDER BY COALESCE(seq, $MAX_LONG), clientTs, id",
    )
    suspend fun messages(conversationId: String, limit: Int, beforeSeq: Long?): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND outgoing = 1 AND seq IS NOT NULL AND seq <= :upToSeq " +
            "ORDER BY seq, clientTs, id",
    )
    suspend fun outgoingUpTo(conversationId: String, upToSeq: Long): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE pendingEnvelopeBody IS NOT NULL AND senderUserId = :userId AND senderDeviceId = :deviceId " +
            "ORDER BY COALESCE(seq, $MAX_LONG), clientTs, id",
    )
    suspend fun undecryptedFrom(userId: String, deviceId: String): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE attachments != '[]' AND (:conversationId IS NULL OR conversationId = :conversationId) " +
            "ORDER BY COALESCE(seq, $MAX_LONG), clientTs, id",
    )
    suspend fun messagesWithAttachments(conversationId: String?): List<MessageEntity>

    // --- reactions, timers and expiry -------------------------------------------------------------------------------
    @Query("SELECT * FROM reactions WHERE messageId = :messageId AND userId = :userId")
    suspend fun reaction(messageId: String, userId: String): ReactionEntity?
    @Query("SELECT * FROM reactions WHERE messageId = :messageId AND emoji IS NOT NULL ORDER BY at, userId")
    suspend fun reactions(messageId: String): List<ReactionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReaction(r: ReactionEntity)
    @Query("DELETE FROM reactions WHERE messageId = :messageId AND userId = :userId")
    suspend fun deleteReaction(messageId: String, userId: String)
    @Query("DELETE FROM reactions WHERE messageId = :messageId") suspend fun deleteReactionsOfMessage(messageId: String)
    @Query("DELETE FROM reactions WHERE conversationId = :id") suspend fun deleteReactionsOf(id: String)

    @Query("SELECT * FROM conversation_timers WHERE conversationId = :id") suspend fun timer(id: String): ConversationTimerEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTimer(t: ConversationTimerEntity)
    @Query("DELETE FROM conversation_timers WHERE conversationId = :id") suspend fun deleteTimer(id: String)

    @Query(
        "SELECT * FROM messages WHERE expireAt IS NOT NULL AND expireAt <= :now AND status != 'PENDING' " +
            "ORDER BY expireAt, id LIMIT :limit",
    )
    suspend fun expired(now: Long, limit: Int): List<MessageEntity>

    @Query("SELECT MIN(expireAt) FROM messages WHERE expireAt IS NOT NULL") suspend fun nextExpiry(): Long?

    // --- receipt times and local deletions --------------------------------------------------------------------------
    @Query("SELECT * FROM message_receipts WHERE messageId = :messageId AND userId = :userId")
    suspend fun receipt(messageId: String, userId: String): MessageReceiptEntity?
    @Query("SELECT * FROM message_receipts WHERE messageId = :messageId ORDER BY userId")
    suspend fun receipts(messageId: String): List<MessageReceiptEntity>
    @Upsert suspend fun putReceipt(r: MessageReceiptEntity)
    @Query("DELETE FROM message_receipts WHERE messageId = :messageId") suspend fun deleteReceipts(messageId: String)
    @Query("DELETE FROM message_receipts WHERE conversationId = :id") suspend fun deleteReceiptsOf(id: String)
    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteMessage(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLocalDeletion(d: LocalDeletionEntity)
    @Query("SELECT COUNT(*) FROM local_deletions WHERE messageId = :id") suspend fun localDeletionCount(id: String): Int
    @Query("DELETE FROM local_deletions WHERE conversationId = :id") suspend fun deleteLocalDeletionsOf(id: String)

    // --- outbox ---------------------------------------------------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOutbox(e: OutboxEntity)
    @Query("SELECT * FROM outbox ORDER BY createdAt, messageId") suspend fun outbox(): List<OutboxEntity>
    @Query("SELECT * FROM outbox WHERE messageId = :id") suspend fun outboxEntry(id: String): OutboxEntity?
    @Query("DELETE FROM outbox WHERE messageId = :id") suspend fun removeOutbox(id: String)
}

/** Blocking access to the libsignal tables: libsignal calls the store synchronously from native code. */
@Dao
interface ProtocolDao {
    @Query("SELECT * FROM signal_identity WHERE id = 1") fun identity(): SignalIdentityEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putIdentity(e: SignalIdentityEntity)

    @Query("SELECT record FROM signal_prekeys WHERE id = :id") fun preKey(id: Int): ByteArray?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putPreKey(e: SignalPreKeyEntity)
    @Query("DELETE FROM signal_prekeys WHERE id = :id") fun removePreKey(id: Int)
    @Query("SELECT COUNT(*) FROM signal_prekeys") fun countPreKeys(): Int

    @Query("SELECT record FROM signal_signed_prekeys WHERE id = :id") fun signedPreKey(id: Int): ByteArray?
    @Query("SELECT record FROM signal_signed_prekeys ORDER BY id") fun signedPreKeys(): List<ByteArray>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSignedPreKey(e: SignalSignedPreKeyEntity)
    @Query("DELETE FROM signal_signed_prekeys WHERE id = :id") fun removeSignedPreKey(id: Int)

    @Query("SELECT record FROM signal_kyber_prekeys WHERE id = :id") fun kyberPreKey(id: Int): ByteArray?
    @Query("SELECT * FROM signal_kyber_prekeys ORDER BY id") fun kyberPreKeys(): List<SignalKyberPreKeyEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putKyberPreKey(e: SignalKyberPreKeyEntity)
    @Query("DELETE FROM signal_kyber_prekeys WHERE id = :id") fun removeKyberPreKey(id: Int)

    /** Returns the new row id, or -1 if exactly this triple was recorded before. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertLastResortUse(e: SignalLastResortUsedEntity): Long

    @Query("SELECT record FROM signal_sessions WHERE name = :name")
    fun session(name: String): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSession(e: SignalSessionEntity)
    @Query("DELETE FROM signal_sessions WHERE name = :name")
    fun deleteSession(name: String)

    /** Exact prefix match (no LIKE wildcards: the prefix contains server-provided ids). */
    @Query("DELETE FROM signal_sessions WHERE substr(name, 1, length(:prefix)) = :prefix")
    fun deleteSessionsWithPrefix(prefix: String)

    @Query("SELECT * FROM signal_identities WHERE deviceId = :deviceId")
    fun remoteIdentity(deviceId: String): SignalRemoteIdentityEntity?

    @Query("SELECT * FROM signal_identities WHERE userId = :userId")
    fun remoteIdentitiesOf(userId: String): List<SignalRemoteIdentityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putRemoteIdentity(e: SignalRemoteIdentityEntity)

    @Query("SELECT next FROM signal_counters WHERE kind = :kind") fun counter(kind: String): Int?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putCounter(e: SignalCounterEntity)

    @Query("DELETE FROM signal_identity") fun clearIdentity()
    @Query("DELETE FROM signal_prekeys") fun clearPreKeys()
    @Query("DELETE FROM signal_signed_prekeys") fun clearSignedPreKeys()
    @Query("DELETE FROM signal_kyber_prekeys") fun clearKyberPreKeys()
    @Query("DELETE FROM signal_last_resort_used") fun clearLastResortUsed()
    @Query("DELETE FROM signal_sessions") fun clearSessions()
    @Query("DELETE FROM signal_identities") fun clearRemoteIdentities()
    @Query("DELETE FROM signal_counters") fun clearCounters()
}

/** Tokens: synchronous because OkHttp calls TokenProvider from its own threads. */
@Dao
interface TokenDao {
    @Query("SELECT * FROM tokens WHERE id = 1") fun get(): TokenEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(t: TokenEntity)
    @Query("DELETE FROM tokens") fun clear()
}

/** Row of the conversation list. */
data class ConversationOverviewRow(
    val id: String,
    val type: String,
    val name: String?,
    val avatarAttachmentId: String?,
    val lastMessageAt: Long?,
    val updatedAt: Long,
    val unread: Int,
    val pinnedAt: Long?,
    val mutedUntil: Long,
    val archived: Boolean,
)

/** A message found by the search (the text is the decrypted one; highlighting is done by the caller). */
data class SearchHitRow(
    val messageId: String,
    val conversationId: String,
    val text: String,
    val time: Long,
    val outgoing: Boolean,
    val senderUserId: String,
)

/** The conversation and the attachment list (JSON, see `AttachmentJson`) of a message. */
data class AttachmentRow(val conversationId: String, val attachments: String)

/** Newest message of a conversation (id, who wrote it), for "stay at the bottom" decisions. */
data class LatestMessageRow(val id: String, val outgoing: Boolean)

/** Text of a conversation's draft (conversation list previews). */
data class DraftRow(val conversationId: String, val text: String)

/** What the header needs to know about a pinned remote identity (never the key itself). */
data class IdentityRow(val deviceId: String, val userId: String, val verified: Boolean, val approved: Boolean)

/** Reactive queries for the UI (Flows re-emit when the tables change). */
@Dao
interface UiDao {
    @Query(
        "SELECT c.id AS id, c.type AS type, c.name AS name, c.avatarAttachmentId AS avatarAttachmentId, " +
            "c.lastMessageAt AS lastMessageAt, c.updatedAt AS updatedAt, " +
            "(SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id AND m.outgoing = 0 AND m.deleted = 0 " +
            "AND m.systemType IS NULL AND m.imported = 0 AND COALESCE(m.seq, 0) > c.myReadSeq) AS unread, " +
            "s.pinnedAt AS pinnedAt, COALESCE(s.mutedUntil, 0) AS mutedUntil, COALESCE(s.archived, 0) AS archived " +
            "FROM conversations c LEFT JOIN conversation_settings s ON s.conversationId = c.id " +
            "ORDER BY COALESCE(c.lastMessageAt, c.updatedAt) DESC, c.id",
    )
    fun observeOverview(): Flow<List<ConversationOverviewRow>>

    @Query("SELECT * FROM conversation_members") fun observeMembers(): Flow<List<MemberEntity>>
    @Query("SELECT * FROM users ORDER BY displayName COLLATE NOCASE") fun observeUsers(): Flow<List<UserEntity>>
    @Query("SELECT * FROM devices ORDER BY userId, number") fun observeDevices(): Flow<List<DeviceEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") fun observeConversation(id: String): Flow<ConversationEntity?>
    @Query("SELECT * FROM account WHERE id = 1") fun observeAccount(): Flow<AccountEntity?>

    @Query("SELECT * FROM messages WHERE id = :id") fun observeMessage(id: String): Flow<MessageEntity?>

    /** The reactions that stand in a conversation (not taken back), for the bubbles. */
    @Query("SELECT * FROM reactions WHERE conversationId = :conversationId AND emoji IS NOT NULL ORDER BY at, userId")
    fun observeReactions(conversationId: String): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM conversation_timers WHERE conversationId = :conversationId")
    fun observeTimer(conversationId: String): Flow<ConversationTimerEntity?>

    @Query("SELECT * FROM message_receipts WHERE messageId = :messageId ORDER BY userId")
    fun observeReceipts(messageId: String): Flow<List<MessageReceiptEntity>>

    /** Newest [limit] messages of the conversation, oldest first. */
    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY COALESCE(seq, $MAX_LONG) DESC, clientTs DESC, id DESC LIMIT :limit) " +
            "ORDER BY COALESCE(seq, $MAX_LONG), clientTs, id",
    )
    fun observeMessages(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    /**
     * All messages of a conversation, newest first, as a Paging source (offset based: Room re-creates the source whenever
     * the `messages` table changes). The order is the one of [observeMessages], reversed.
     */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY COALESCE(seq, $MAX_LONG) DESC, clientTs DESC, id DESC",
    )
    fun pagedMessages(conversationId: String): PagingSource<Int, MessageEntity>

    @Query(
        "SELECT id, outgoing FROM messages WHERE conversationId = :conversationId AND imported = 0 " +
            "ORDER BY COALESCE(seq, $MAX_LONG) DESC, clientTs DESC, id DESC LIMIT 1",
    )
    fun observeLatest(conversationId: String): Flow<LatestMessageRow?>

    /** The highest server sequence number among the messages from others; the conversation is read up to there. Imported rows do not count. */
    @Query("SELECT MAX(seq) FROM messages WHERE conversationId = :conversationId AND outgoing = 0 AND imported = 0")
    fun observeNewestIncomingSeq(conversationId: String): Flow<Long?>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE conversationId = :conversationId")
    suspend fun maxSeq(conversationId: String): Long

    /** Incoming messages with a server sequence above [afterSeq] (what arrived while the user read further up). */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND outgoing = 0 AND deleted = 0 " +
            "AND systemType IS NULL AND imported = 0 AND COALESCE(seq, 0) > :afterSeq",
    )
    fun observeIncomingAfter(conversationId: String, afterSeq: Long): Flow<Int>

    /** The oldest unread incoming message (same notion of "unread" as the list's counter). */
    @Query(
        "SELECT id FROM messages WHERE conversationId = :conversationId AND outgoing = 0 AND deleted = 0 " +
            "AND systemType IS NULL AND imported = 0 AND COALESCE(seq, 0) > :readSeq ORDER BY seq, clientTs, id LIMIT 1",
    )
    suspend fun firstUnreadId(conversationId: String, readSeq: Long): String?

    /** How many messages are newer than [messageId] (its offset in [pagedMessages]), or null if it does not exist. */
    @Query(
        "SELECT COUNT(*) FROM messages m, messages t WHERE t.id = :messageId AND t.conversationId = :conversationId " +
            "AND m.conversationId = :conversationId AND (COALESCE(m.seq, $MAX_LONG) > COALESCE(t.seq, $MAX_LONG) " +
            "OR (COALESCE(m.seq, $MAX_LONG) = COALESCE(t.seq, $MAX_LONG) AND (m.clientTs > t.clientTs " +
            "OR (m.clientTs = t.clientTs AND m.id > t.id))))",
    )
    suspend fun newerThan(conversationId: String, messageId: String): Int

    /**
     * Full-text search; [query] is an FTS4 expression built by `SearchQuery.toMatch` (never raw user input). Newest first;
     * deleted messages and system notices are not hits ([conversationId] null: all conversations).
     */
    @Query(
        "SELECT m.id AS messageId, m.conversationId AS conversationId, m.text AS text, " +
            "COALESCE(m.serverTs, m.clientTs) AS time, m.outgoing AS outgoing, m.senderUserId AS senderUserId " +
            "FROM messages m JOIN message_fts ON message_fts.docid = m.rowid " +
            "WHERE message_fts MATCH :query AND m.deleted = 0 AND m.systemType IS NULL AND m.text IS NOT NULL " +
            "AND (:conversationId IS NULL OR m.conversationId = :conversationId) " +
            "ORDER BY COALESCE(m.serverTs, m.clientTs) DESC, m.id LIMIT :limit",
    )
    suspend fun search(query: String, conversationId: String?, limit: Int): List<SearchHitRow>

    @Query("SELECT conversationId, text FROM drafts") fun observeDrafts(): Flow<List<DraftRow>>

    @Query("SELECT deviceId, userId, verified, approved FROM signal_identities")
    fun observeIdentities(): Flow<List<IdentityRow>>

    /** Meta keys of unresolved identity conflicts (`identity_conflict:<device uuid>`, see `DeviceDirectory`). */
    @Query("SELECT `key` FROM meta WHERE `key` LIKE 'identity\\_conflict:%' ESCAPE '\\'")
    fun observeIdentityConflicts(): Flow<List<String>>

    /** The last real message (not a system notice) of a conversation. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND systemType IS NULL " +
            "ORDER BY COALESCE(seq, $MAX_LONG) DESC, clientTs DESC, id DESC LIMIT 1",
    )
    suspend fun lastMessage(conversationId: String): MessageEntity?

    /** The newest unread incoming messages, for notifications (oldest first). */
    @Query(
        "SELECT * FROM (SELECT m.* FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE m.conversationId = :conversationId AND m.outgoing = 0 AND m.deleted = 0 AND m.systemType IS NULL " +
            "AND m.imported = 0 AND COALESCE(m.seq, 0) > c.myReadSeq ORDER BY COALESCE(m.seq, 0) DESC LIMIT :limit) ORDER BY COALESCE(seq, 0)",
    )
    suspend fun unreadMessages(conversationId: String, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM outbox") fun observeOutboxCount(): Flow<Int>

    /** The attachment lists of all messages that have attachments (to find files nobody refers to). */
    @Query("SELECT attachments FROM messages WHERE attachments != '[]'")
    suspend fun attachmentJsons(): List<String>

    /** One row per message that has attachments, for the storage screen. */
    @Query("SELECT conversationId, attachments FROM messages WHERE attachments != '[]'")
    suspend fun attachmentRows(): List<AttachmentRow>
}
