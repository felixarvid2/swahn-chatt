package se.familychat.app.data.db

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

    @Query(
        "SELECT COUNT(*) FROM messages m WHERE m.conversationId = :id AND m.outgoing = 0 AND m.deleted = 0 " +
            "AND m.systemType IS NULL AND COALESCE(m.seq, 0) > COALESCE((SELECT myReadSeq FROM conversations WHERE id = :id), 0)",
    )
    suspend fun unreadCount(id: String): Int

    @Query("UPDATE conversations SET myReadSeq = :seq WHERE id = :id AND myReadSeq < :seq")
    suspend fun raiseReadSeq(id: String, seq: Long)

    // --- messages -------------------------------------------------------------------------------------------------
    @Query("SELECT * FROM messages WHERE id = :id") suspend fun message(id: String): MessageEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMessage(m: MessageEntity)

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
)

/** Reactive queries for the UI (Flows re-emit when the tables change). */
@Dao
interface UiDao {
    @Query(
        "SELECT c.id AS id, c.type AS type, c.name AS name, c.avatarAttachmentId AS avatarAttachmentId, " +
            "c.lastMessageAt AS lastMessageAt, c.updatedAt AS updatedAt, " +
            "(SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id AND m.outgoing = 0 AND m.deleted = 0 " +
            "AND m.systemType IS NULL AND COALESCE(m.seq, 0) > c.myReadSeq) AS unread " +
            "FROM conversations c ORDER BY COALESCE(c.lastMessageAt, c.updatedAt) DESC, c.id",
    )
    fun observeOverview(): Flow<List<ConversationOverviewRow>>

    @Query("SELECT * FROM conversation_members") fun observeMembers(): Flow<List<MemberEntity>>
    @Query("SELECT * FROM users ORDER BY displayName COLLATE NOCASE") fun observeUsers(): Flow<List<UserEntity>>
    @Query("SELECT * FROM devices ORDER BY userId, number") fun observeDevices(): Flow<List<DeviceEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") fun observeConversation(id: String): Flow<ConversationEntity?>
    @Query("SELECT * FROM account WHERE id = 1") fun observeAccount(): Flow<AccountEntity?>

    @Query("SELECT * FROM messages WHERE id = :id") fun observeMessage(id: String): Flow<MessageEntity?>

    /** Newest [limit] messages of the conversation, oldest first. */
    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY COALESCE(seq, $MAX_LONG) DESC, clientTs DESC, id DESC LIMIT :limit) " +
            "ORDER BY COALESCE(seq, $MAX_LONG), clientTs, id",
    )
    fun observeMessages(conversationId: String, limit: Int): Flow<List<MessageEntity>>

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
            "AND COALESCE(m.seq, 0) > c.myReadSeq ORDER BY COALESCE(m.seq, 0) DESC LIMIT :limit) ORDER BY COALESCE(seq, 0)",
    )
    suspend fun unreadMessages(conversationId: String, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM outbox") fun observeOutboxCount(): Flow<Int>

    /** The attachment lists of all messages that have attachments (to find files nobody refers to). */
    @Query("SELECT attachments FROM messages WHERE attachments != '[]'")
    suspend fun attachmentJsons(): List<String>
}
