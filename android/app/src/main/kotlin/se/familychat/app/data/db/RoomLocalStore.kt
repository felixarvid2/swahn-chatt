package se.familychat.app.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.familychat.core.client.Account
import se.familychat.core.client.LocalStore
import se.familychat.core.client.OutboxEntry
import se.familychat.core.client.StoredMessage
import se.familychat.core.model.Conversation
import se.familychat.core.model.Device
import se.familychat.core.model.User
import java.util.concurrent.Callable

/**
 * [LocalStore] on Room, in the same database as [RoomProtocolStorage].
 *
 * Threading (this is what makes [blockingTransaction] sound; see also `SyncProcessor`):
 * - A SQLite transaction belongs to the thread that began it. Room's `inTransaction()` tells whether the *calling thread*
 *   is inside one.
 * - Every public function runs through [atomic]/[io]. Called from a thread that is already inside a transaction (the
 *   thread of a [blockingTransaction], or Room's transaction thread of a [transaction]) it runs inline on that thread,
 *   without switching dispatchers and without starting a second transaction; otherwise it hops to [dispatcher].
 *   Switching threads inside a transaction would deadlock on SQLite's single writer.
 * - Room's own suspending DAO calls take the same shortcut (`compatCoroutineExecute`), so they run inline there too.
 */
class RoomLocalStore(
    private val db: AppDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalStore {
    private val dao = db.storeDao()

    /** Runs [block] on this thread if it is inside a transaction already, else on [dispatcher]. */
    private suspend inline fun <T> io(crossinline block: suspend () -> T): T =
        if (db.inTransaction()) block() else withContext(dispatcher) { block() }

    /** Like [io] but with a transaction around [block] when there is none yet. */
    private suspend inline fun <T> atomic(crossinline block: suspend () -> T): T =
        if (db.inTransaction()) block() else withContext(dispatcher) { db.withTransaction { block() } }

    // --- account / meta -------------------------------------------------------------------------------------------
    override suspend fun getAccount(): Account? = io { dao.account()?.toModel() }
    override suspend fun saveAccount(account: Account) = io { dao.putAccount(account.toEntity()) }

    override suspend fun clearAll() = withContext(dispatcher) {
        check(!db.inTransaction()) { "clearAll() cannot run inside a transaction" }
        // Deletes the signal_* and tokens tables too; also checkpoints the WAL and vacuums, so nothing lingers in the file.
        db.clearAllTables()
        // The search index only marks removed messages as deleted (their tokens stay in its segments until they are merged),
        // so rebuild it from the now empty `messages` and vacuum: no word of the old conversations stays in the file.
        val raw = db.openHelper.writableDatabase
        raw.execSQL("INSERT INTO `message_fts`(`message_fts`) VALUES ('rebuild')")
        raw.execSQL("VACUUM")
    }

    override suspend fun getMeta(key: String): String? = io { dao.meta(key) }
    override suspend fun setMeta(key: String, value: String?) = io {
        if (value == null) dao.deleteMeta(key) else dao.putMeta(MetaEntity(key, value))
    }

    override suspend fun <T> transaction(block: suspend () -> T): T = atomic { block() }

    /** Blocking transaction on the calling thread; nests into an open one. Must not be called on the main thread. */
    override fun <T> blockingTransaction(block: () -> T): T = db.runInTransaction(Callable { block() })

    // --- directory ------------------------------------------------------------------------------------------------
    override suspend fun upsertUsers(users: List<User>) = io { dao.upsertUsers(users.map { it.toEntity() }) }
    override suspend fun getUser(id: String): User? = io { dao.user(id)?.toModel() }
    override suspend fun getUsers(): List<User> = io { dao.users().map { it.toModel() } }

    override suspend fun replaceDevices(userId: String, devices: List<Device>) = atomic {
        dao.deleteDevices(userId)
        dao.putDevices(devices.map { it.toEntity() })
    }

    override suspend fun getDevices(userId: String): List<Device> = io { dao.devices(userId).map { it.toModel() } }
    override suspend fun findDevice(deviceId: String): Device? = io { dao.device(deviceId)?.toModel() }

    // --- conversations --------------------------------------------------------------------------------------------
    override suspend fun upsertConversation(conversation: Conversation) = atomic {
        // myReadSeq is the server's value but never moves backwards locally
        val read = maxOf(dao.conversation(conversation.id)?.myReadSeq ?: 0L, conversation.myReadSeq)
        dao.putConversation(
            ConversationEntity(
                id = conversation.id, type = conversation.type, name = conversation.name,
                avatarAttachmentId = conversation.avatarAttachmentId, createdBy = conversation.createdBy,
                createdAt = conversation.createdAt, updatedAt = conversation.updatedAt,
                lastMessageAt = conversation.lastMessageAt, lastMessageSeq = conversation.lastMessageSeq, myReadSeq = read,
            ),
        )
        dao.deleteMembers(conversation.id)
        dao.putMembers(conversation.members.map { MemberEntity(conversation.id, it.userId, it.role, it.joinedAt) })
    }

    override suspend fun getConversation(id: String): Conversation? = io {
        dao.conversation(id)?.toModel(dao.members(id))
    }

    override suspend fun getConversations(): List<Conversation> = io {
        val members = dao.allMembers().groupBy { it.conversationId }
        dao.conversations().map { it.toModel(members[it.id].orEmpty()) }
    }

    override suspend fun removeConversation(id: String) = atomic {
        dao.deleteMessagesOf(id)
        dao.deleteOutboxOf(id)
        dao.deleteDraft(id)
        dao.deleteSettings(id)
        dao.deleteMembers(id)
        dao.deleteConversation(id)
    }

    override suspend fun unreadCount(conversationId: String): Int = io { dao.unreadCount(conversationId) }
    override suspend fun setMyReadSeq(conversationId: String, seq: Long) = io { dao.raiseReadSeq(conversationId, seq) }

    // --- messages -------------------------------------------------------------------------------------------------
    override suspend fun getMessage(id: String): StoredMessage? = io { dao.message(id)?.toModel() }
    override suspend fun upsertMessage(message: StoredMessage) = atomic {
        val isNew = !message.outgoing && !message.deleted && message.systemEvent == null && dao.message(message.id) == null
        dao.putMessage(message.toEntity())
        // a new incoming message brings an archived conversation back, unless it is muted
        if (isNew) dao.unarchiveUnlessMuted(message.conversationId, System.currentTimeMillis())
    }

    override suspend fun updateMessage(id: String, update: (StoredMessage) -> StoredMessage): StoredMessage? = atomic {
        val current = dao.message(id)?.toModel() ?: return@atomic null
        update(current).also { dao.putMessage(it.toEntity()) }
    }

    override suspend fun getMessages(conversationId: String, limit: Int, beforeSeq: Long?): List<StoredMessage> =
        io { dao.messages(conversationId, limit, beforeSeq).map { it.toModel() } }

    override suspend fun outgoingNotReadBy(conversationId: String, userId: String, upToSeq: Long): List<StoredMessage> =
        io { dao.outgoingUpTo(conversationId, upToSeq).map { it.toModel() }.filter { userId !in it.readBy } }

    override suspend fun undecryptedFrom(userId: String, deviceId: String): List<StoredMessage> =
        io { dao.undecryptedFrom(userId, deviceId).map { it.toModel() } }

    // --- outbox ---------------------------------------------------------------------------------------------------
    override suspend fun putOutbox(entry: OutboxEntry) = io { dao.putOutbox(entry.toEntity()) }
    override suspend fun getOutbox(): List<OutboxEntry> = io { dao.outbox().map { it.toModel() } }
    override suspend fun getOutboxEntry(messageId: String): OutboxEntry? = io { dao.outboxEntry(messageId)?.toModel() }
    override suspend fun removeOutbox(messageId: String) = io { dao.removeOutbox(messageId) }

    // --- sync -----------------------------------------------------------------------------------------------------
    override suspend fun getSyncCursor(): Long = io { dao.cursor() ?: 0L }
    override suspend fun setSyncCursor(cursor: Long) = io { dao.putCursor(SyncStateEntity(cursor = cursor)) }
}
