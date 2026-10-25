package se.swahnchat.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import se.swahnchat.core.client.Account
import se.swahnchat.core.client.DecryptError
import se.swahnchat.core.client.DownloadState
import se.swahnchat.core.client.FailureReason
import se.swahnchat.core.client.LocalAttachment
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.client.MessageStatus
import se.swahnchat.core.client.OutboxAttachment
import se.swahnchat.core.client.OutboxEntry
import se.swahnchat.core.client.ReceiptTimes
import se.swahnchat.core.client.StoredMessage
import se.swahnchat.core.client.SystemEvent
import se.swahnchat.core.model.Conversation
import se.swahnchat.core.model.ConversationMember
import se.swahnchat.core.model.Device
import se.swahnchat.core.model.Envelope
import se.swahnchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The behaviour every [LocalStore] must have, taken from `InMemoryLocalStore` (the reference). It runs against the
 * in-memory store and against the Room implementation, so the two cannot drift apart.
 */
abstract class LocalStoreContract {
    abstract fun newStore(): LocalStore

    /** In-memory stores serialise transactions but cannot roll back. */
    open val rollsBack: Boolean = true

    protected val store: LocalStore by lazy { newStore() }

    private fun account() = Account("https://x.example/api/v1/", "u1", "d1", 3, "anna", "Anna", true, "Pixel")

    private fun conv(id: String, lastSeq: Long? = null, updated: Long = 1, myRead: Long = 0, name: String? = null, members: List<String> = listOf("u1", "u2")) =
        Conversation(
            id = id, type = if (members.size > 2) "group" else "direct", name = name, createdBy = "u1", createdAt = 1, updatedAt = updated,
            lastMessageAt = lastSeq?.let { it * 10 }, lastMessageSeq = lastSeq, myReadSeq = myRead, unreadCount = 99,
            members = members.mapIndexed { i, u -> ConversationMember(u, if (i == 0) "admin" else "member", 100L + i) },
        )

    private fun msg(
        id: String, conv: String = "c1", seq: Long? = null, ts: Long = 1000, outgoing: Boolean = false, sender: String = if (outgoing) "u1" else "u2",
        text: String? = "hi $id", status: MessageStatus = if (outgoing) MessageStatus.SENT else MessageStatus.RECEIVED,
    ) = StoredMessage(
        id = id, conversationId = conv, senderUserId = sender, senderDeviceId = "dev-$sender", outgoing = outgoing, status = status,
        seq = seq, clientTs = ts, serverTs = seq?.let { ts + 5 }, text = text, replyTo = null,
    )

    // --- account, meta, cursor ------------------------------------------------------------------------------------------

    @Test fun `account round trips and is replaced`() = runBlocking {
        assertNull(store.getAccount())
        store.saveAccount(account())
        assertEquals(account(), store.getAccount())
        store.saveAccount(account().copy(displayName = "Anna B"))
        assertEquals("Anna B", store.getAccount()!!.displayName)
    }

    @Test fun `meta values can be set replaced and removed`() = runBlocking {
        assertNull(store.getMeta("k"))
        store.setMeta("k", "v1")
        store.setMeta("k", "v2")
        assertEquals("v2", store.getMeta("k"))
        store.setMeta("k", null)
        assertNull(store.getMeta("k"))
    }

    @Test fun `sync cursor starts at zero and is stored`() = runBlocking {
        assertEquals(0L, store.getSyncCursor())
        store.setSyncCursor(41)
        store.setSyncCursor(42)
        assertEquals(42L, store.getSyncCursor())
    }

    @Test fun `clearAll removes everything`() = runBlocking {
        store.saveAccount(account())
        store.setMeta("k", "v")
        store.upsertUsers(listOf(User("u2", "bob", "Bob")))
        store.replaceDevices("u2", listOf(Device("d2", "u2", 1, "Phone")))
        store.upsertConversation(conv("c1"))
        store.upsertMessage(msg("m1", seq = 1))
        store.putOutbox(OutboxEntry("o1", "c1", "message", null, "x", null, emptyList(), 1, createdAt = 1))
        store.setSyncCursor(9)
        store.clearAll()
        assertNull(store.getAccount()); assertNull(store.getMeta("k")); assertTrue(store.getUsers().isEmpty())
        assertTrue(store.getDevices("u2").isEmpty()); assertTrue(store.getConversations().isEmpty())
        assertNull(store.getMessage("m1")); assertTrue(store.getOutbox().isEmpty()); assertEquals(0L, store.getSyncCursor())
    }

    // --- directory ------------------------------------------------------------------------------------------------------

    @Test fun `users are upserted by id`() = runBlocking {
        store.upsertUsers(listOf(User("u1", "anna", "Anna", isAdmin = true, createdAt = 5), User("u2", "bob", "Bob")))
        store.upsertUsers(listOf(User("u2", "bob", "Robert", avatarAttachmentId = "av1")))
        assertEquals(2, store.getUsers().size)
        assertEquals("Robert", store.getUser("u2")!!.displayName)
        assertEquals("av1", store.getUser("u2")!!.avatarAttachmentId)
        assertTrue(store.getUser("u1")!!.isAdmin)
        assertNull(store.getUser("nobody"))
    }

    @Test fun `replaceDevices drops devices that are not listed and findDevice looks across users`() = runBlocking {
        store.replaceDevices("u2", listOf(Device("d2a", "u2", 1, "A", identityKey = "k1"), Device("d2b", "u2", 2, "B")))
        store.replaceDevices("u3", listOf(Device("d3", "u3", 1, "C")))
        store.replaceDevices("u2", listOf(Device("d2b", "u2", 2, "B renamed", lastSeenAt = 7)))
        assertEquals(listOf("d2b"), store.getDevices("u2").map { it.id })
        assertEquals("B renamed", store.findDevice("d2b")!!.name)
        assertEquals("C", store.findDevice("d3")!!.name)
        assertNull(store.findDevice("d2a"))
    }

    // --- conversations --------------------------------------------------------------------------------------------------

    @Test fun `conversation round trips with members and ignores the unread count of the dto`() = runBlocking {
        store.upsertConversation(conv("c1", lastSeq = 4, members = listOf("u1", "u2", "u3"), name = "Family"))
        val c = store.getConversation("c1")!!
        assertEquals("Family", c.name)
        assertEquals(listOf("u1", "u2", "u3"), c.members.map { it.userId })
        assertEquals(listOf("admin", "member", "member"), c.members.map { it.role })
        assertEquals(0, c.unreadCount)
        assertEquals(4L, c.lastMessageSeq)
        assertNull(store.getConversation("missing"))
    }

    @Test fun `my read seq never moves backwards`() = runBlocking {
        store.upsertConversation(conv("c1", myRead = 5))
        store.upsertConversation(conv("c1", myRead = 2))
        assertEquals(5L, store.getConversation("c1")!!.myReadSeq)
        store.upsertConversation(conv("c1", myRead = 9))
        assertEquals(9L, store.getConversation("c1")!!.myReadSeq)
        store.setMyReadSeq("c1", 3)
        assertEquals(9L, store.getConversation("c1")!!.myReadSeq)
        store.setMyReadSeq("c1", 12)
        assertEquals(12L, store.getConversation("c1")!!.myReadSeq)
    }

    @Test fun `conversations are listed by newest activity first`() = runBlocking {
        store.upsertConversation(conv("old", lastSeq = 3, updated = 10))
        store.upsertConversation(conv("new", lastSeq = 30, updated = 5))
        store.upsertConversation(conv("empty-a", lastSeq = null, updated = 50))
        store.upsertConversation(conv("empty-b", lastSeq = null, updated = 60))
        assertEquals(listOf("new", "old", "empty-b", "empty-a"), store.getConversations().map { it.id })
    }

    @Test fun `removing a conversation removes its messages and outbox entries only`() = runBlocking {
        store.upsertConversation(conv("c1")); store.upsertConversation(conv("c2"))
        store.upsertMessage(msg("m1", "c1", seq = 1)); store.upsertMessage(msg("m2", "c2", seq = 2))
        store.putOutbox(OutboxEntry("o1", "c1", "message", null, "x", null, emptyList(), 1, createdAt = 1))
        store.putOutbox(OutboxEntry("o2", "c2", "message", null, "y", null, emptyList(), 1, createdAt = 2))
        store.removeConversation("c1")
        assertNull(store.getConversation("c1")); assertNull(store.getMessage("m1")); assertNull(store.getOutboxEntry("o1"))
        assertNotNull(store.getConversation("c2")); assertNotNull(store.getMessage("m2")); assertNotNull(store.getOutboxEntry("o2"))
        assertTrue(store.getMessages("c1").isEmpty())
    }

    @Test fun `unread counts incoming real messages beyond the read pointer`() = runBlocking {
        store.upsertConversation(conv("c1", myRead = 2))
        store.upsertMessage(msg("a", seq = 1))
        store.upsertMessage(msg("b", seq = 3))
        store.upsertMessage(msg("c", seq = 4))
        store.upsertMessage(msg("own", seq = 5, outgoing = true))
        store.upsertMessage(msg("gone", seq = 6).copy(deleted = true))
        store.upsertMessage(msg("sys", seq = 7).copy(systemEvent = SystemEvent.NewDevice("u2", "dev-2", "Phone")))
        assertEquals(2, store.unreadCount("c1"))
        store.setMyReadSeq("c1", 4)
        assertEquals(0, store.unreadCount("c1"))
        assertEquals(0, store.unreadCount("unknown"))
    }

    // --- messages -------------------------------------------------------------------------------------------------------

    @Test fun `upserting the same message twice keeps one row and the latest content`() = runBlocking {
        store.upsertMessage(msg("m1", seq = 1, text = "first"))
        store.upsertMessage(msg("m1", seq = 1, text = "second"))
        assertEquals(1, store.getMessages("c1").size)
        assertEquals("second", store.getMessage("m1")!!.text)
    }

    @Test fun `every message field survives a round trip`() = runBlocking {
        val attachment = LocalAttachment(
            id = "att1", key = "a2V5", sha256 = "ab12", size = 1234, mime = "image/jpeg", name = "båt \"quoted\".jpg", kind = "image",
            width = 640, height = 480, durationMs = null, thumbnail = "dGh1bWI=", localPath = "/files/att1", downloadState = DownloadState.DONE,
        )
        val m = StoredMessage(
            id = "m1", conversationId = "c1", senderUserId = "u2", senderDeviceId = "dev-u2", outgoing = true, status = MessageStatus.READ,
            seq = 8, clientTs = 100, serverTs = 105, text = "Här är texten 😀", replyTo = "m0", attachments = listOf(attachment, attachment.copy(id = "att2", kind = "voice", durationMs = 4200, thumbnail = null)),
            edited = true, editedAt = 110, deleted = false, decryptError = DecryptError.UNTRUSTED_IDENTITY, failureReason = FailureReason.TOO_LARGE,
            deliveredTo = setOf("u2", "u3"), readBy = setOf("u3"), pendingEnvelopeType = "prekey", pendingEnvelopeBody = "Ym9keQ==",
        )
        store.upsertMessage(m)
        assertEquals(m, store.getMessage("m1"))
        val sys = StoredMessage(
            id = "sys-1", conversationId = "c1", senderUserId = "", senderDeviceId = null, outgoing = false, status = MessageStatus.RECEIVED,
            seq = 8, clientTs = 1, serverTs = null, text = null, replyTo = null, systemEvent = SystemEvent.IdentityChanged("u2", "dev-3"),
        )
        store.upsertMessage(sys)
        assertEquals(sys, store.getMessage("sys-1"))
        val nd = sys.copy(id = "sys-2", systemEvent = SystemEvent.NewDevice("u2", "dev-4", "Tablet"))
        store.upsertMessage(nd)
        assertEquals(nd, store.getMessage("sys-2"))
        // every notice type keeps its fields, including the approval flag and the group member notices
        for ((i, event) in listOf(
            SystemEvent.NewDevice("u2", "dev-5", "Tablet", needsApproval = true),
            SystemEvent.MemberAdded("u3"),
            SystemEvent.MemberRemoved("u4"),
        ).withIndex()) {
            val n = sys.copy(id = "sys-n$i", systemEvent = event)
            store.upsertMessage(n)
            assertEquals(n, store.getMessage("sys-n$i"))
        }
    }

    @Test fun `updateMessage applies edits and deletes atomically and reports a missing message`() = runBlocking {
        store.upsertMessage(msg("m1", seq = 1, outgoing = true, text = "old"))
        val edited = store.updateMessage("m1") { it.copy(text = "new", edited = true, editedAt = 9) }
        assertEquals("new", edited!!.text)
        assertEquals(true, store.getMessage("m1")!!.edited)
        store.updateMessage("m1") { it.copy(text = null, deleted = true, attachments = emptyList()) }
        assertEquals(true, store.getMessage("m1")!!.deleted)
        assertNull(store.getMessage("m1")!!.text)
        assertNull(store.updateMessage("missing") { it.copy(text = "x") })
        assertNull(store.getMessage("missing"))
    }

    @Test fun `concurrent updates of one message do not lose increments`() = runBlocking {
        store.upsertMessage(msg("m1", seq = 1, outgoing = true))
        (1..20).map { i -> async(kotlinx.coroutines.Dispatchers.Default) { store.updateMessage("m1") { it.copy(deliveredTo = it.deliveredTo + "u$i") } } }.awaitAll()
        assertEquals(20, store.getMessage("m1")!!.deliveredTo.size)
    }

    @Test fun `messages are ordered by seq then pending ones by client time and id`() = runBlocking {
        store.upsertMessage(msg("s3", seq = 3, ts = 300))
        store.upsertMessage(msg("s1", seq = 1, ts = 100))
        store.upsertMessage(msg("p2", seq = null, ts = 900, outgoing = true, status = MessageStatus.PENDING))
        store.upsertMessage(msg("p1", seq = null, ts = 800, outgoing = true, status = MessageStatus.PENDING))
        store.upsertMessage(msg("p1b", seq = null, ts = 800, outgoing = true, status = MessageStatus.PENDING))
        store.upsertMessage(msg("s2", seq = 2, ts = 200))
        assertEquals(listOf("s1", "s2", "s3", "p1", "p1b", "p2"), store.getMessages("c1").map { it.id })
    }

    @Test fun `getMessages returns the newest page oldest first and pages backwards by seq`() = runBlocking {
        for (i in 1..10) store.upsertMessage(msg("m$i", seq = i.toLong(), ts = i * 100L))
        assertEquals(listOf("m8", "m9", "m10"), store.getMessages("c1", limit = 3).map { it.id })
        assertEquals(listOf("m5", "m6", "m7"), store.getMessages("c1", limit = 3, beforeSeq = 8).map { it.id })
        assertEquals(listOf("m1", "m2"), store.getMessages("c1", limit = 5, beforeSeq = 3).map { it.id })
        assertTrue(store.getMessages("other").isEmpty())
    }

    @Test fun `outgoingNotReadBy finds sent messages up to a seq that the user has not read`() = runBlocking {
        store.upsertMessage(msg("o1", seq = 1, outgoing = true).copy(readBy = setOf("u2")))
        store.upsertMessage(msg("o2", seq = 2, outgoing = true))
        store.upsertMessage(msg("o3", seq = 5, outgoing = true))
        store.upsertMessage(msg("in", seq = 3))
        store.upsertMessage(msg("pending", seq = null, outgoing = true, status = MessageStatus.PENDING))
        assertEquals(listOf("o2"), store.outgoingNotReadBy("c1", "u2", 4).map { it.id })
        assertEquals(listOf("o2", "o3"), store.outgoingNotReadBy("c1", "u2", 5).map { it.id })
        assertEquals(listOf("o1", "o2", "o3"), store.outgoingNotReadBy("c1", "u9", 5).map { it.id })
    }

    @Test fun `undecryptedFrom finds messages kept for a retry by sender device`() = runBlocking {
        store.upsertMessage(msg("a", seq = 1).copy(pendingEnvelopeType = "prekey", pendingEnvelopeBody = "AAA", senderDeviceId = "d1"))
        store.upsertMessage(msg("b", seq = 2).copy(pendingEnvelopeType = "whisper", pendingEnvelopeBody = "BBB", senderDeviceId = "d1"))
        store.upsertMessage(msg("c", seq = 3).copy(pendingEnvelopeType = "whisper", pendingEnvelopeBody = "CCC", senderDeviceId = "d2"))
        store.upsertMessage(msg("d", seq = 4).copy(senderDeviceId = "d1"))
        assertEquals(listOf("a", "b"), store.undecryptedFrom("u2", "d1").map { it.id })
        assertTrue(store.undecryptedFrom("u3", "d1").isEmpty())
    }

    // --- outbox ---------------------------------------------------------------------------------------------------------

    @Test fun `outbox entries keep attachments and envelopes and are listed oldest first`() = runBlocking {
        val att = OutboxAttachment("/p/a.jpg", "image/jpeg", "a.jpg", "image", 10, 20, null, "dGg=", "up1", "a2V5", "ff", 99)
        val full = OutboxEntry(
            "o2", "c1", "message", null, "text åäö", "m0", listOf(att), 5,
            envelopes = listOf(Envelope("d1", "prekey", "Ym9keQ==")), attempts = 2, lastError = "network", createdAt = 20,
        )
        store.putOutbox(full)
        store.putOutbox(OutboxEntry("o1", "c1", "edit", "m1", "e", null, emptyList(), 6, createdAt = 10))
        assertEquals(listOf("o1", "o2"), store.getOutbox().map { it.messageId })
        assertEquals(full, store.getOutboxEntry("o2"))
        store.putOutbox(full.copy(attempts = 3, envelopes = null))
        assertEquals(3, store.getOutboxEntry("o2")!!.attempts)
        assertNull(store.getOutboxEntry("o2")!!.envelopes)
        assertEquals(2, store.getOutbox().size)
        store.removeOutbox("o1")
        store.removeOutbox("nope")
        assertEquals(listOf("o2"), store.getOutbox().map { it.messageId })
    }

    // --- transactions ---------------------------------------------------------------------------------------------------

    @Test fun `transaction returns the block's value and its writes are visible afterwards`() = runBlocking {
        val v = store.transaction {
            store.upsertMessage(msg("t1", seq = 1))
            store.setMeta("k", "v")
            42
        }
        assertEquals(42, v)
        assertNotNull(store.getMessage("t1"))
        assertEquals("v", store.getMeta("k"))
    }

    @Test fun `a failing transaction rolls back all of its writes`() = runBlocking {
        if (!rollsBack) return@runBlocking
        assertFailsWith<IllegalStateException> {
            store.transaction {
                store.upsertMessage(msg("t1", seq = 1))
                store.setMeta("k", "v")
                store.putOutbox(OutboxEntry("o1", "c1", "message", null, "x", null, emptyList(), 1, createdAt = 1))
                throw IllegalStateException("boom")
            }
        }
        assertNull(store.getMessage("t1")); assertNull(store.getMeta("k")); assertNull(store.getOutboxEntry("o1"))
    }

    @Test fun `transactions can be nested`() = runBlocking {
        store.transaction {
            store.upsertMessage(msg("outer", seq = 1))
            store.transaction { store.upsertMessage(msg("inner", seq = 2)) }
        }
        assertEquals(2, store.getMessages("c1").size)
    }

    @Test fun `many parallel writers of the same message leave one row`() = runBlocking {
        (1..30).map { i ->
            async(kotlinx.coroutines.Dispatchers.Default) { store.upsertMessage(msg("same", seq = 1, text = "v$i")) }
        }.awaitAll()
        assertEquals(1, store.getMessages("c1").size)
    }

    // --- receipt times, local deletions, messages with attachments ---------------------------------------------------------

    @Test fun `receipt times keep the first value and fill in the missing one`() = runBlocking {
        store.upsertMessage(msg("o1", seq = 1, outgoing = true))
        assertTrue(store.getReceipts("o1").isEmpty())
        store.recordReceipt("c1", "o1", "u2", deliveredAt = 10, readAt = null)
        assertEquals(listOf(ReceiptTimes("o1", "u2", 10, null)), store.getReceipts("o1"))
        store.recordReceipt("c1", "o1", "u2", deliveredAt = 99, readAt = 20) // delivered again later: the first time stays
        store.recordReceipt("c1", "o1", "u3", deliveredAt = null, readAt = 30)
        assertEquals(listOf(ReceiptTimes("o1", "u2", 10, 20), ReceiptTimes("o1", "u3", null, 30)), store.getReceipts("o1"))
        store.recordReceipt("c1", "o1", "u2", deliveredAt = null, readAt = null) // nothing known: nothing changes
        assertEquals(ReceiptTimes("o1", "u2", 10, 20), store.getReceipts("o1").first())
    }

    @Test fun `deleting a message locally removes it and its receipts and leaves a tombstone`() = runBlocking {
        store.upsertConversation(conv("c1"))
        store.upsertMessage(msg("a", seq = 1, outgoing = true))
        store.upsertMessage(msg("b", seq = 2))
        store.recordReceipt("c1", "a", "u2", 1, 2)
        assertEquals(false, store.isLocallyDeleted("a"))

        val removed = store.deleteMessageLocally("a", "c1", 5)
        assertEquals("a", removed?.id)
        assertNull(store.getMessage("a"))
        assertTrue(store.getReceipts("a").isEmpty())
        assertTrue(store.isLocallyDeleted("a"))
        assertEquals(listOf("b"), store.getMessages("c1").map { it.id })
        assertNull(store.updateMessage("a") { it.copy(text = "late edit") }) // a late change finds nothing to change
        assertNull(store.getMessage("a"))

        assertNull(store.deleteMessageLocally("a", "c1", 6)) // deleting again is harmless
        assertNull(store.deleteMessageLocally("never-seen", "c1", 7)) // and a tombstone can precede the message
        assertTrue(store.isLocallyDeleted("never-seen"))
    }

    @Test fun `removing a conversation removes its receipt times and tombstones`() = runBlocking {
        store.upsertConversation(conv("c1")); store.upsertConversation(conv("c2"))
        store.upsertMessage(msg("a", seq = 1, outgoing = true)); store.upsertMessage(msg("z", conv = "c2", seq = 1, outgoing = true))
        store.recordReceipt("c1", "a", "u2", 1, null); store.recordReceipt("c2", "z", "u2", 1, null)
        store.deleteMessageLocally("gone1", "c1", 1); store.deleteMessageLocally("gone2", "c2", 1)
        store.removeConversation("c1")
        assertTrue(store.getReceipts("a").isEmpty())
        assertEquals(false, store.isLocallyDeleted("gone1"))
        assertEquals(1, store.getReceipts("z").size)
        assertTrue(store.isLocallyDeleted("gone2"))
    }

    @Test fun `clearAll removes receipt times and tombstones`() = runBlocking {
        store.upsertMessage(msg("a", seq = 1, outgoing = true))
        store.recordReceipt("c1", "a", "u2", 1, null)
        store.deleteMessageLocally("gone", "c1", 1)
        store.clearAll()
        assertTrue(store.getReceipts("a").isEmpty())
        assertEquals(false, store.isLocallyDeleted("gone"))
    }

    @Test fun `messages with attachments can be listed for one conversation or all`() = runBlocking {
        fun att(id: String) = LocalAttachment(id, "k", "s", 10, "image/jpeg", "p.jpg", "image", null, null, null, null, "/x/$id", DownloadState.DONE)
        store.upsertMessage(msg("plain", seq = 1))
        store.upsertMessage(msg("p1", conv = "c1", seq = 2).copy(attachments = listOf(att("a1"))))
        store.upsertMessage(msg("p2", conv = "c2", seq = 1).copy(attachments = listOf(att("a2"), att("a3"))))
        assertEquals(listOf("p1"), store.messagesWithAttachments("c1").map { it.id })
        assertEquals(setOf("p1", "p2"), store.messagesWithAttachments().map { it.id }.toSet())
        assertEquals(listOf("a2", "a3"), store.messagesWithAttachments("c2").single().attachments.map { it.id })
    }
}
