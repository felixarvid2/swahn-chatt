package se.familychat.core.client

import se.familychat.core.model.Conversation
import se.familychat.core.model.Device
import se.familychat.core.model.User

/** Thread-safe in-memory [LocalStore]. State lives only as long as the instance. */
class InMemoryLocalStore : LocalStore {
    private val lock = Any()
    private var account: Account? = null
    private val meta = HashMap<String, String>()
    private val users = LinkedHashMap<String, User>()
    private val devices = HashMap<String, List<Device>>()
    private val conversations = LinkedHashMap<String, Conversation>()
    private val readSeq = HashMap<String, Long>()
    private val messages = LinkedHashMap<String, StoredMessage>()
    private val outbox = LinkedHashMap<String, OutboxEntry>()
    private var cursor = 0L
    private val receipts = HashMap<Pair<String, String>, ReceiptTimes>()
    private val receiptConversation = HashMap<String, String>() // message id -> conversation id
    private val tombstones = HashMap<String, String>() // message id -> conversation id

    private inline fun <T> sync(block: () -> T): T = synchronized(lock, block)

    override suspend fun getAccount() = sync { account }
    override suspend fun saveAccount(account: Account) = sync { this.account = account }

    override suspend fun clearAll() = sync {
        account = null; meta.clear(); users.clear(); devices.clear(); conversations.clear(); readSeq.clear()
        messages.clear(); outbox.clear(); cursor = 0; receipts.clear(); receiptConversation.clear(); tombstones.clear()
    }

    override suspend fun getMeta(key: String) = sync { meta[key] }
    override suspend fun setMeta(key: String, value: String?) = sync {
        if (value == null) meta.remove(key) else meta[key] = value
        Unit
    }

    // A real transaction would also roll back; the in-memory store only serialises transactions.
    override suspend fun <T> transaction(block: suspend () -> T): T = block()

    override suspend fun upsertUsers(users: List<User>) = sync { users.forEach { this.users[it.id] = it } }
    override suspend fun getUser(id: String) = sync { users[id] }
    override suspend fun getUsers() = sync { users.values.toList() }

    override suspend fun replaceDevices(userId: String, devices: List<Device>) = sync { this.devices[userId] = devices.toList(); Unit }
    override suspend fun getDevices(userId: String) = sync { devices[userId].orEmpty() }
    override suspend fun findDevice(deviceId: String) = sync { devices.values.flatten().firstOrNull { it.id == deviceId } }

    override suspend fun upsertConversation(conversation: Conversation) = sync {
        conversations[conversation.id] = conversation.copy(unreadCount = 0)
        // myReadSeq is the server's value but never moves backwards locally
        val cur = readSeq[conversation.id] ?: 0L
        readSeq[conversation.id] = maxOf(cur, conversation.myReadSeq)
        Unit
    }

    private fun withReadSeq(c: Conversation) = c.copy(myReadSeq = readSeq[c.id] ?: c.myReadSeq)

    override suspend fun getConversation(id: String) = sync { conversations[id]?.let { withReadSeq(it) } }

    override suspend fun getConversations() = sync {
        conversations.values.map { withReadSeq(it) }
            .sortedWith(compareByDescending<Conversation> { it.lastMessageSeq ?: Long.MIN_VALUE }.thenByDescending { it.updatedAt })
    }

    override suspend fun removeConversation(id: String) = sync {
        conversations.remove(id); readSeq.remove(id)
        messages.values.removeAll { it.conversationId == id }
        outbox.values.removeAll { it.conversationId == id }
        val gone = receiptConversation.filterValues { it == id }.keys
        receipts.keys.removeAll { it.first in gone }
        receiptConversation.keys.removeAll(gone)
        tombstones.values.removeAll { it == id }
        Unit
    }

    override suspend fun unreadCount(conversationId: String) = sync {
        val mine = readSeq[conversationId] ?: 0L
        messages.values.count {
            it.conversationId == conversationId && !it.outgoing && !it.deleted && !it.isSystem && (it.seq ?: 0) > mine
        }
    }

    override suspend fun setMyReadSeq(conversationId: String, seq: Long) = sync {
        readSeq[conversationId] = maxOf(readSeq[conversationId] ?: 0L, seq); Unit
    }

    override suspend fun getMessage(id: String) = sync { messages[id] }
    override suspend fun upsertMessage(message: StoredMessage) = sync { messages[message.id] = message; Unit }

    override suspend fun updateMessage(id: String, update: (StoredMessage) -> StoredMessage) = sync {
        val cur = messages[id] ?: return@sync null
        update(cur).also { messages[id] = it }
    }

    private val order = compareBy<StoredMessage>({ it.seq ?: Long.MAX_VALUE }, { it.clientTs }, { it.id })

    override suspend fun getMessages(conversationId: String, limit: Int, beforeSeq: Long?) = sync {
        messages.values.filter { it.conversationId == conversationId && (beforeSeq == null || (it.seq ?: Long.MAX_VALUE) < beforeSeq) }
            .sortedWith(order).takeLast(limit)
    }

    override suspend fun outgoingNotReadBy(conversationId: String, userId: String, upToSeq: Long) = sync {
        messages.values.filter {
            it.conversationId == conversationId && it.outgoing && it.seq != null && it.seq <= upToSeq && userId !in it.readBy
        }.sortedWith(order)
    }

    override suspend fun undecryptedFrom(userId: String, deviceId: String) = sync {
        messages.values.filter { it.pendingEnvelopeBody != null && it.senderUserId == userId && it.senderDeviceId == deviceId }
            .sortedWith(order)
    }

    override suspend fun messagesWithAttachments(conversationId: String?) = sync {
        messages.values.filter { it.attachments.isNotEmpty() && (conversationId == null || it.conversationId == conversationId) }
            .sortedWith(order)
    }

    override suspend fun recordReceipt(conversationId: String, messageId: String, userId: String, deliveredAt: Long?, readAt: Long?) = sync {
        val key = messageId to userId
        val cur = receipts[key]
        receipts[key] = ReceiptTimes(messageId, userId, cur?.deliveredAt ?: deliveredAt, cur?.readAt ?: readAt)
        receiptConversation[messageId] = conversationId
        Unit
    }

    override suspend fun getReceipts(messageId: String) = sync { receipts.values.filter { it.messageId == messageId }.sortedBy { it.userId } }

    override suspend fun deleteMessageLocally(id: String, conversationId: String, at: Long) = sync {
        tombstones[id] = conversationId
        receipts.keys.removeAll { it.first == id }
        receiptConversation.remove(id)
        messages.remove(id)
    }

    override suspend fun isLocallyDeleted(id: String) = sync { id in tombstones }

    override suspend fun putOutbox(entry: OutboxEntry) = sync { outbox[entry.messageId] = entry; Unit }
    override suspend fun getOutbox() = sync { outbox.values.sortedWith(compareBy({ it.createdAt }, { it.messageId })) }
    override suspend fun getOutboxEntry(messageId: String) = sync { outbox[messageId] }
    override suspend fun removeOutbox(messageId: String) = sync { outbox.remove(messageId); Unit }

    override suspend fun getSyncCursor() = sync { cursor }
    override suspend fun setSyncCursor(cursor: Long) = sync { this.cursor = cursor }

    /** Test helper: all messages of a conversation, oldest first. */
    suspend fun allMessages(conversationId: String): List<StoredMessage> = getMessages(conversationId, Int.MAX_VALUE)
}
