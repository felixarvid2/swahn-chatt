package se.familychat.core.client

import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Delete for me" (local only, with a tombstone), receipt times and clearing downloaded media. */
class LocalDeletionTest {
    private val server = FakeServer()
    private val scope = newScope()

    @AfterTest fun tearDown() = scope.cancel()

    private suspend fun pair(): Triple<TestClient, TestClient, String> {
        val alice = TestClient(server, "alice", scope)
        val bob = TestClient(server, "bob", scope)
        alice.signUp(); bob.signUp()
        alice.engine.refreshDirectory(); bob.engine.refreshDirectory()
        val conv = alice.engine.createDirect(bob.userId())
        bob.engine.sync()
        return Triple(alice, bob, conv.id)
    }

    @Test
    fun `delete for me removes the message here only and sends nothing`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val id = alice.engine.sendText(convId, "hello"); alice.engine.processOutbox(); bob.engine.sync()
        val serverBefore = server.messageCountInConversation(convId)
        val outboxBefore = bob.store.getOutbox().size

        assertNotNull(bob.engine.deleteMessageForMe(id))

        assertNull(bob.store.getMessage(id))
        assertTrue(bob.store.isLocallyDeleted(id))
        assertEquals(outboxBefore, bob.store.getOutbox().size) // nothing queued
        bob.engine.processOutbox()
        assertEquals(serverBefore, server.messageCountInConversation(convId)) // nothing sent
        assertEquals("hello", alice.store.getMessage(id)!!.text) // the author still has it
        alice.engine.sync()
        assertEquals("hello", alice.store.getMessage(id)!!.text)
        assertNull(bob.engine.deleteMessageForMe(id)) // already gone
    }

    @Test
    fun `a late edit, a late delete and a replay do not resurrect a message deleted for me`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val id = alice.engine.sendText(convId, "v1"); alice.engine.processOutbox(); bob.engine.sync()
        bob.engine.deleteMessageForMe(id)

        alice.engine.editMessage(id, "v2"); alice.engine.processOutbox()
        bob.engine.sync()
        assertNull(bob.store.getMessage(id))

        alice.engine.deleteMessage(id); alice.engine.processOutbox()
        bob.engine.sync()
        assertNull(bob.store.getMessage(id))

        // the server delivers everything again (events are re-sent until acknowledged): still gone
        bob.store.setSyncCursor(0)
        bob.engine.sync()
        assertNull(bob.store.getMessage(id))
        assertTrue(bob.store.isLocallyDeleted(id))
        // a message sent afterwards still arrives
        val next = alice.engine.sendText(convId, "next"); alice.engine.processOutbox(); bob.engine.sync()
        assertEquals("next", bob.store.getMessage(next)!!.text)
    }

    @Test
    fun `late receipts for an own message deleted for me are ignored`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val id = alice.engine.sendText(convId, "mine"); alice.engine.processOutbox()
        alice.engine.deleteMessageForMe(id)
        bob.engine.sync() // acknowledges -> delivered receipt for alice
        bob.engine.markRead(convId)
        alice.engine.sync()
        assertNull(alice.store.getMessage(id))
        assertTrue(alice.store.getReceipts(id).isEmpty())
        // the echo of the own message on another device of ours is not restored either
        alice.store.setSyncCursor(0)
        alice.engine.sync()
        assertNull(alice.store.getMessage(id))
    }

    @Test
    fun `a message that is still waiting to be sent cannot be deleted for me, notices neither`() = runBlocking<Unit> {
        val (alice, _, convId) = pair()
        val id = alice.engine.sendText(convId, "queued")
        assertFailsWith<IllegalArgumentException> { alice.engine.deleteMessageForMe(id) }
        assertNotNull(alice.store.getMessage(id))
        val notice = alice.store.getMessages(convId, 100).firstOrNull { it.isSystem }
        if (notice != null) assertFailsWith<IllegalArgumentException> { alice.engine.deleteMessageForMe(notice.id) }
    }

    @Test
    fun `deleting for me removes the downloaded file but not the sender's copy`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val f = File(alice.dir, "doc.bin").apply { writeBytes(Random(3).nextBytes(50_000)) }
        val id = alice.engine.sendAttachments(convId, listOf(OutgoingAttachment(f, "application/pdf", "file")))
        alice.engine.processOutbox(); bob.engine.sync()
        val att = bob.store.getMessage(id)!!.attachments.single()
        val downloaded = bob.engine.downloadAttachment(id, att.id)
        assertTrue(downloaded.isFile)

        bob.engine.deleteMessageForMe(id)
        assertFalse(downloaded.exists())
        assertNull(bob.store.getMessage(id))

        // the author's own file (not owned by the engine) is untouched by her delete for me
        alice.engine.deleteMessageForMe(id)
        assertTrue(f.isFile)
    }

    @Test
    fun `clearing downloaded media keeps the messages and the media can be fetched again`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val data = Random(4).nextBytes(30_000)
        val f = File(alice.dir, "doc.bin").apply { writeBytes(data) }
        val id = alice.engine.sendAttachments(convId, listOf(OutgoingAttachment(f, "application/pdf", "file")), caption = "see")
        alice.engine.processOutbox(); bob.engine.sync()
        val att = bob.store.getMessage(id)!!.attachments.single()
        val file = bob.engine.downloadAttachment(id, att.id)

        // another conversation is not touched when only one is cleared
        assertEquals(0L, bob.engine.clearDownloadedMedia("some-other-conversation"))
        assertTrue(file.isFile)

        assertEquals(data.size.toLong(), bob.engine.clearDownloadedMedia(convId))
        assertFalse(file.exists())
        val cleared = bob.store.getMessage(id)!!
        assertEquals("see", cleared.text)
        assertEquals(DownloadState.NONE, cleared.attachments.single().downloadState)
        assertNull(cleared.attachments.single().localPath)
        // the sender's own copy is not the engine's to delete
        assertEquals(0L, alice.engine.clearDownloadedMedia())
        assertTrue(f.isFile)

        assertEquals(data.size, bob.engine.downloadAttachment(id, att.id).length().toInt())
    }

    @Test
    fun `receipt times are recorded from the receipt events, a read receipt that never comes leaves a dash`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val id = alice.engine.sendText(convId, "hi"); alice.engine.processOutbox()
        assertTrue(alice.store.getReceipts(id).isEmpty())

        val before = System.currentTimeMillis()
        bob.engine.sync() // acknowledges: the server tells alice "delivered"
        alice.engine.sync()
        val delivered = alice.store.getReceipts(id).single()
        assertEquals(bob.userId(), delivered.userId)
        assertTrue(delivered.deliveredAt!! >= before)
        assertNull(delivered.readAt)

        bob.engine.markRead(convId)
        alice.engine.sync()
        val read = alice.store.getReceipts(id).single()
        assertEquals(delivered.deliveredAt, read.deliveredAt) // the first time is kept
        assertTrue(read.readAt!! >= delivered.deliveredAt!!)
        assertEquals(MessageStatus.READ, alice.store.getMessage(id)!!.status)
    }
}
