package se.familychat.core.client

import kotlinx.coroutines.runBlocking
import se.familychat.core.crypto.Recipient
import se.familychat.core.model.Content
import se.familychat.core.model.ContentType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A store that counts how often the engine asked it to compact its search index. */
private class CountingStore(private val inner: InMemoryLocalStore = InMemoryLocalStore()) : LocalStore by inner {
    var compactions = 0
    override suspend fun compactSearchIndex() {
        compactions++
    }
}

/** Findings of the security review: group timers, imported rows and read marks, the search index after a purge, imported media. */
class HardeningTests : FeatureTestBase() {
    private fun imported(id: String, conv: String, seq: Long?, attachments: List<LocalAttachment> = emptyList()) = StoredMessage(
        id = id, conversationId = conv, senderUserId = "bob", senderDeviceId = null, outgoing = false, status = MessageStatus.RECEIVED,
        seq = seq, clientTs = 5, serverTs = 5, text = "ur en fil", replyTo = null, attachments = attachments, imported = true,
    )

    @Test
    fun `a timer change in a group from a member who is not an administrator is consumed and ignored`() = runBlocking<Unit> {
        val t = trio()
        val member = RogueDevice(t.bob.userId())
        val memberId = server.addDevice(t.bob.userId(), member.upload, announce = true)
        t.alice.engine.sync()
        fun timer(conv: String, id: String, seconds: Long) = Content(type = ContentType.TIMER, seconds = seconds, conversationId = conv, messageId = id)
        suspend fun inject(conv: String, content: Content) {
            val env = member.encryptFor(Recipient(t.alice.userId(), t.alice.deviceIdOnServer()), server.bundleFor(memberId, t.alice), content)
            server.injectMessage(t.alice.deviceIdOnServer(), forgedMessage(content.messageId!!, conv, t.bob.userId(), memberId, env))
        }
        // bob is a member of the group but not an administrator (the engine itself refuses to send this; a modified client would)
        assertFalse(t.alice.store.getConversation(t.group)!!.members.single { it.userId == t.bob.userId() }.role == "admin")
        inject(t.group, timer(t.group, "g1", 300))
        t.alice.engine.sync()
        assertEquals(0L, t.alice.engine.disappearingTimer(t.group), "the timer did not change")
        assertNull(t.alice.store.getTimer(t.group))
        assertTrue(t.alice.notices(t.group).none { it is SystemEvent.TimerChanged }, "and nobody is told it did")
        assertNull(t.alice.store.getMessage("g1"), "no bubble either")
        assertTrue(t.alice.store.isLocallyDeleted("g1"), "consumed: a replay is skipped before decryption")
        // and it does not matter that it was the first one: the same device's timer in the DIRECT chat still works
        inject(t.ab, timer(t.ab, "d1", 600))
        t.alice.engine.sync()
        assertEquals(600L, t.alice.engine.disappearingTimer(t.ab))
        // an administrator's change in the group is applied
        t.alice.engine.setDisappearingTimer(t.group, 3_600); t.alice.engine.processOutbox(); t.bob.engine.sync(); t.carol.engine.sync()
        assertEquals(3_600L, t.bob.engine.disappearingTimer(t.group))
        assertEquals(3_600L, t.carol.engine.disappearingTimer(t.group))
        // a later one from the non-administrator still changes nothing
        inject(t.group, timer(t.group, "g2", 300))
        t.alice.engine.sync()
        assertEquals(3_600L, t.alice.engine.disappearingTimer(t.group))
    }

    @Test
    fun `imported rows never move the read mark, however large their sequence number`() = runBlocking<Unit> {
        val t = trio()
        t.alice.send(t.ab, "first"); t.alice.send(t.ab, "second")
        t.bob.engine.sync()
        val latest = t.bob.store.getMessages(t.ab, 10).maxOf { it.seq!! }
        t.bob.store.upsertMessage(imported("imp-huge", t.ab, Long.MAX_VALUE))
        t.bob.store.upsertMessage(imported("imp-big", t.ab, latest + 1_000_000))
        assertEquals(2, t.bob.store.unreadCount(t.ab), "imported rows are not unread")
        t.bob.engine.markRead(t.ab)
        assertEquals(latest, t.bob.store.getConversation(t.ab)!!.myReadSeq, "read up to the last REAL message")
        // a message that arrives later is still unread, and notified as such
        t.alice.send(t.ab, "third"); t.bob.engine.sync()
        assertEquals(1, t.bob.store.unreadCount(t.ab))
        t.bob.engine.markRead(t.ab)
        assertEquals(0, t.bob.store.unreadCount(t.ab))
        assertTrue(t.bob.store.getConversation(t.ab)!!.myReadSeq > latest)
    }

    @Test
    fun `after expired messages are purged the search index is compacted, and only then`() = runBlocking<Unit> {
        val store = CountingStore()
        val alice = TestClient(server, "alice", scope, false, clock = { clock.get() })
        val bob = TestClient(server, "bob", scope, false, store = store, clock = { clock.get() })
        alice.signUp(); bob.signUp()
        alice.engine.refreshDirectory(); bob.engine.refreshDirectory()
        val ab = alice.engine.createDirect(bob.userId()).id
        bob.engine.sync()
        alice.engine.setDisappearingTimer(ab, 300); alice.engine.processOutbox()
        val id = alice.send(ab, "hemligt ord")
        bob.engine.sync()
        bob.engine.markDisplayed(listOf(id))
        assertTrue(bob.engine.purgeExpired().isEmpty())
        assertEquals(0, store.compactions, "nothing removed, nothing to compact")
        clock.addAndGet(301_000)
        assertEquals(listOf(id), bob.engine.purgeExpired().map { it.id })
        assertEquals(1, store.compactions)
        assertTrue(bob.engine.purgeExpired().isEmpty())
        assertEquals(1, store.compactions)
    }

    @Test
    fun `clearing downloaded media leaves the files of imported messages alone`() = runBlocking<Unit> {
        val t = trio()
        val att = File(t.bob.dir, "att").apply { mkdirs() }
        val kept = File(att, "imported-photo").apply { writeBytes(ByteArray(777)) }
        val local = LocalAttachment(
            id = "import-1", key = null, sha256 = null, size = 777, mime = "image/jpeg", name = "p.jpg", kind = "image", width = null,
            height = null, durationMs = null, thumbnail = null, localPath = kept.absolutePath, downloadState = DownloadState.DONE,
        )
        t.bob.store.upsertMessage(imported("imp-media", t.ab, 1, listOf(local)))
        assertEquals(0L, t.bob.engine.clearDownloadedMedia())
        assertEquals(0L, t.bob.engine.clearDownloadedMedia(t.ab))
        assertTrue(kept.isFile)
        val after = t.bob.store.getMessage("imp-media")!!.attachments.single()
        assertEquals(DownloadState.DONE, after.downloadState)
        assertEquals(kept.absolutePath, after.localPath)
    }
}
