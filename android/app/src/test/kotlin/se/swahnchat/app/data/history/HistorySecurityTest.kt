package se.swahnchat.app.data.history

import kotlinx.coroutines.runBlocking
import se.swahnchat.core.model.Conversation
import se.swahnchat.core.model.ConversationMember
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What an authentic history file is still not trusted with: who said what, where it sorts, what it unpacks to, what it costs to open. */
class HistorySecurityTest {
    private val phone = Phone().fill()

    private fun line(json: String) = (json + "\n").toByteArray()

    private fun msg(id: String, sender: String = "bob", conv: String = "c1", seq: String = "5", extra: String = "") =
        """{"id":"$id","conversation_id":"$conv","sender_user_id":"$sender","seq":$seq,"client_ts":1000,"text":"text $id"$extra}"""

    private fun file(vararg messages: String) =
        buildFile(listOf("manifest.json" to manifest(), "messages.jsonl" to line(messages.joinToString("\n"))))

    /** A phone that has the conversations as the server last told it: [lastSeq] messages in c1. */
    private fun target(lastSeq: Long?): Phone = phone.newPhone().also { p ->
        runBlocking {
            val c = p.store.getConversation("c1")!!
            p.store.upsertConversation(c.copy(lastMessageSeq = lastSeq))
        }
    }

    // --- who may have said it --------------------------------------------------------------------------------------------------------

    @Test fun `a message from somebody who is not a member of the conversation is dropped`() = runBlocking<Unit> {
        val fresh = target(10)
        // cia is in no conversation of this phone; bob is in c1 and c2; "" is the sender of system notices
        val result = fresh.import(file(msg("ok", "bob"), msg("stranger", "cia"), msg("mine", "me"), msg("empty-sender", "")))
        assertEquals(2, result.imported)
        assertEquals(2, result.invalid)
        assertNull(fresh.store.getMessage("stranger"))
        assertEquals(listOf("mine", "ok").sorted(), fresh.store.getMessages("c1", 10).map { it.id }.sorted())
    }

    @Test fun `membership is the one of the conversation the message is in`() = runBlocking<Unit> {
        val fresh = phone.newPhone()
        val members = listOf(ConversationMember("me"), ConversationMember("cia"))
        fresh.store.upsertConversation(Conversation("c3", "group", "Andra", createdBy = "me", members = members))
        val result = fresh.import(file(msg("a", "bob", "c3"), msg("b", "cia", "c3"), msg("c", "cia", "c1")))
        assertEquals(1, result.imported, "only cia in c3")
        assertEquals(listOf("b"), fresh.store.getMessages("c3", 10).map { it.id })
        assertNull(fresh.store.getMessage("c"))
    }

    // --- where it sorts, and what it does to read marks ------------------------------------------------------------------------------------

    @Test fun `a sequence number past the conversation's is clamped, so later real messages stay unread`() = runBlocking<Unit> {
        val fresh = target(10)
        val result = fresh.import(
            file(
                msg("huge", seq = Long.MAX_VALUE.toString()), msg("negative", seq = "-5"), msg("none", seq = "null"), msg("fine", seq = "7"),
                msg("edge", seq = "10"), msg("above", seq = "11"),
            ),
        )
        assertEquals(6, result.imported)
        val seqs = fresh.store.getMessages("c1", 10).associate { it.id to it.seq }
        assertEquals(mapOf("huge" to 10L, "negative" to 0L, "none" to 0L, "fine" to 7L, "edge" to 10L, "above" to 10L), seqs)
        assertTrue(fresh.store.getMessages("c1", 10).all { it.imported })
        assertEquals(0, fresh.store.unreadCount("c1"), "imported messages are not unread")
        // the next real message of the conversation: unread, and after the imported ones in the list
        fresh.store.upsertMessage(real("real-11", "c1", 11))
        assertEquals(1, fresh.store.unreadCount("c1"))
        assertEquals("real-11", fresh.store.getMessages("c1", 100).last().id)
    }

    @Test fun `a conversation this phone has seen nothing of puts imported messages first`() = runBlocking<Unit> {
        val fresh = target(null)
        fresh.import(file(msg("huge", seq = Long.MAX_VALUE.toString()), msg("small", seq = "3")))
        assertEquals(listOf(0L, 0L), fresh.store.getMessages("c1", 10).map { it.seq })
        fresh.store.upsertMessage(real("real-1", "c1", 1))
        assertEquals("real-1", fresh.store.getMessages("c1", 10).last().id)
    }

    private fun real(id: String, conv: String, seq: Long) = se.swahnchat.core.client.StoredMessage(
        id = id, conversationId = conv, senderUserId = "bob", senderDeviceId = "d", outgoing = false,
        status = se.swahnchat.core.client.MessageStatus.RECEIVED, seq = seq, clientTs = 1, serverTs = 1, text = "real", replyTo = null,
    )

    // --- reactions ---------------------------------------------------------------------------------------------------------------------------

    @Test fun `of the reactions only this account's own are taken - nobody else's name goes on a reaction`() = runBlocking<Unit> {
        val fresh = target(10)
        val reactions = ""","reactions":[{"user_id":"bob","emoji":"👍"},{"user_id":"me","emoji":"❤️"},{"user_id":"cia","emoji":"😂"}]"""
        fresh.import(file(msg("m", extra = reactions)))
        assertEquals(listOf("me"), fresh.store.getReactions("m").map { it.userId })
    }

    // --- what it unpacks to ---------------------------------------------------------------------------------------------------------------------

    @Test fun `a bomb in any kind of entry is stopped by what it inflates to, not by what it is stored as`() {
        val fresh = target(10)
        val small = HistoryLimits(maxPayloadBytes = 10_000_000, maxEntryBytes = 5_000_000, maxSpoolBytes = 5_000_000)
        for (name in listOf("media/0", "something-from-the-future.bin")) {
            val bomb = buildFile(listOf("manifest.json" to manifest(), name to ByteArray(60_000_000)))
            assertTrue(bomb.size < 200_000, "$name is small as stored: ${bomb.size}")
            assertFailsWith<HistoryException.TooLarge>(name) { fresh.import(bomb, limits = small) }
        }
        // the lines of the message list too: 60 MB of empty lines, or of ever so many tiny ones
        val newlines = buildFile(listOf("manifest.json" to manifest(), "messages.jsonl" to ByteArray(60_000_000) { '\n'.code.toByte() }))
        assertFailsWith<HistoryException.TooLarge> { fresh.import(newlines, limits = small) }
        // and several entries that are each below the entry limit but together above the total
        val spread = buildFile(listOf("manifest.json" to manifest()) + (0 until 4).map { "x$it" to ByteArray(4_000_000) })
        assertFailsWith<HistoryException.TooLarge> { fresh.import(spread, limits = HistoryLimits(maxPayloadBytes = 10_000_000)) }
        assertEquals(0, fresh.messageCount("c1"))
        assertTrue(fresh.leftovers().isEmpty(), "${fresh.leftovers()}")
    }

    @Test fun `too many message lines fail the import, however short, valid or not`() {
        val fresh = target(10)
        val valid = (1..30).joinToString("\n") { msg("v$it") }
        assertFailsWith<HistoryException.TooLarge> { fresh.import(file(valid), limits = HistoryLimits(maxMessageLines = 20)) }
        val junk = (1..30).joinToString("\n") { "not json $it" }
        assertFailsWith<HistoryException.TooLarge> { fresh.import(file(junk), limits = HistoryLimits(maxMessageLines = 20)) }
        assertEquals(0, fresh.messageCount("c1"), "nothing is imported from a file that was refused")
        // exactly at the limit is fine
        assertEquals(30, fresh.import(file(valid), limits = HistoryLimits(maxMessageLines = 30)).imported)
    }

    @Test fun `the validated messages are bounded in the spool`() {
        val fresh = target(10)
        val many = (1..200).joinToString("\n") { msg("s$it") }
        assertFailsWith<HistoryException.TooLarge> { fresh.import(file(many), limits = HistoryLimits(maxSpoolBytes = 5_000)) }
        assertTrue(fresh.leftovers().isEmpty())
        assertEquals(200, fresh.import(file(many)).imported)
    }

    @Test fun `the default limits are the documented ones`() {
        val d = HistoryLimits()
        assertEquals(200_000, d.maxMessageLines)
        assertEquals(1024L * 1024 * 1024, d.maxSpoolBytes)
    }

    // --- what opening it costs ----------------------------------------------------------------------------------------------------------------

    private fun header(memoryKiB: Int, iterations: Int, lanes: Int = 1): ByteArray =
        HistoryFormat.Header(KdfParams(memoryKiB, iterations, lanes), ByteArray(16)).encode()

    private fun read(bytes: ByteArray) = HistoryFormat.readHeader(ByteArrayInputStream(bytes)).first.kdf

    @Test fun `the header may ask for at most 128 MiB and six passes`() {
        assertEquals(128 * 1024, KdfParams.MAX_MEMORY_KIB)
        assertEquals(6, KdfParams.MAX_ITERATIONS)
        assertEquals(KdfParams(128 * 1024, 6, 1), read(header(128 * 1024, 6)))
        assertEquals(KdfParams.DEFAULT, read(header(64 * 1024, 3)), "what an export uses is accepted")
        val tooMuch = listOf(
            128 * 1024 + 1 to 3, 256 * 1024 to 3, 64 * 1024 to 7, 64 * 1024 to 10, 64 * 1024 to 65_535, 64 * 1024 to 0, Int.MAX_VALUE to 3,
        )
        for ((memory, passes) in tooMuch) {
            assertFailsWith<HistoryException.UnsupportedParameters>("$memory KiB, $passes passes") { read(header(memory, passes)) }
        }
        // and the importer refuses before it derives anything: no passphrase work for a crafted header
        val crafted = phone.export().also { ByteBuffer.wrap(it).putInt(6, 200 * 1024) }
        assertFailsWith<HistoryException.UnsupportedParameters> { phone.newPhone().import(crafted) }
    }

    @Test fun `a phone that cannot give the memory refuses the file instead of crashing`() {
        val e = assertFailsWith<HistoryException.UnsupportedParameters> {
            HistoryFormat.guardMemory(128 * 1024) { throw OutOfMemoryError("Java heap space") }
        }
        assertTrue("128 MiB" in e.message!!, e.message)
        assertEquals(7, HistoryFormat.guardMemory(1) { 7 })
        // other errors are not hidden
        assertFailsWith<IllegalStateException> { HistoryFormat.guardMemory(1) { error("x") } }
    }
}
