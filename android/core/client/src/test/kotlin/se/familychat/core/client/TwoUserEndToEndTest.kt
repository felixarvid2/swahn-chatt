package se.familychat.core.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import se.familychat.core.crypto.InMemoryProtocolStorage
import se.familychat.core.model.MessageKind
import se.familychat.core.network.ApiClient
import se.familychat.core.network.ApiException
import se.familychat.core.network.InMemoryTokenProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end test of the real engine, crypto and network stack against a real FamilyChat backend. Skipped unless
 *
 * - `FAMILYCHAT_TEST_URL` (e.g. `http://127.0.0.1:8000`) and
 * - `FAMILYCHAT_TEST_ADMIN_INVITE` (an administrator invitation code)
 *
 * are set; `FAMILYCHAT_TEST_DB` (path of the server's SQLite file) additionally enables the "server cannot read
 * plaintext" check. `android/scripts/run-e2e.sh` starts a throw-away backend and sets all three.
 *
 * Every phone below is an independent client: its own [InMemoryLocalStore], libsignal storage, tokens and engine, talking
 * to the server only over HTTP/WebSocket.
 */
class TwoUserEndToEndTest {

    /** One independent phone using the real network stack. */
    class Phone(val label: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = InMemoryLocalStore()
        val protocol = InMemoryProtocolStorage()
        val tokens = InMemoryTokenProvider()
        val dir: File = Files.createTempDirectory("fc-e2e-$label").toFile().also { it.deleteOnExit() }
        val engine = FamilyChatEngine(
            store, protocol, tokens,
            EngineConfig(deviceName = "$label phone", attachmentDir = File(dir, "att"), tempDir = File(dir, "tmp"), allowInsecureLan = true),
            scope,
        )
        val userId get() = engine.account.value!!.userId
        val deviceId get() = engine.account.value!!.deviceId

        /** A second, raw API client with this phone's tokens (to look at the server's view). */
        fun rawApi() = ApiClient.create(url, tokens, allowInsecureLan = true).api

        suspend fun texts(conversationId: String) = store.getMessages(conversationId, 1000).filter { !it.isSystem }.map { it.text }

        fun close() = scope.cancel()
    }

    companion object {
        private val url: String = System.getenv("FAMILYCHAT_TEST_URL").orEmpty()
        private val adminInvite: String = System.getenv("FAMILYCHAT_TEST_ADMIN_INVITE").orEmpty()
        private val dbPath: String? = System.getenv("FAMILYCHAT_TEST_DB")?.takeIf { it.isNotBlank() }

        private val run = UUID.randomUUID().toString().take(8)
        private val phones = ArrayList<Phone>()
        lateinit var admin: Phone
        lateinit var alice: Phone
        lateinit var bob: Phone
        lateinit var carol: Phone

        private fun phone(label: String) = Phone(label).also { phones += it }

        @JvmStatic
        @BeforeClass
        fun setUpUsers() {
            Assume.assumeTrue("FAMILYCHAT_TEST_URL / FAMILYCHAT_TEST_ADMIN_INVITE not set: skipping", url.isNotBlank() && adminInvite.isNotBlank())
            runBlocking {
                admin = phone("admin")
                admin.engine.redeemInvite(url, adminInvite, "admin_$run", "admin-password-1", "Admin")
                // the admin hands out invitations through the API
                fun codeFor(who: String) = runBlocking { admin.engine.createInvitation(note = "e2e $who").code }
                alice = phone("alice")
                alice.engine.redeemInvite(url, codeFor("alice"), "alice_$run", "alice-password-1", "Alice")
                bob = phone("bob")
                bob.engine.redeemInvite(url, codeFor("bob"), "bob_$run", "bob-password-1", "Bob")
                carol = phone("carol")
                carol.engine.redeemInvite(url, codeFor("carol"), "carol_$run", "carol-password-1", "Carol")
                listOf(alice, bob, carol).forEach { it.engine.refreshDirectory() }
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDownUsers() {
            phones.forEach { it.close() }
        }
    }

    private val timeout = 6_000L

    private suspend fun Phone.receives(conversationId: String, messageId: String, what: String = "message $messageId") =
        eventually(timeout, "$label to receive $what") { store.getMessage(messageId)?.let { it.text != null || it.deleted || it.decryptError != null } == true }

    private suspend fun directChat(a: Phone, b: Phone): String {
        val c = a.engine.createDirect(b.userId)
        b.engine.refreshDirectory()
        return c.id
    }

    @Test
    fun realtimeMessagesBothWaysWithReceiptsEditAndDelete() = runBlocking {
        alice.engine.startRealtime(); bob.engine.startRealtime()
        try {
            val conv = directChat(alice, bob)
            eventually(timeout, "bob knows the conversation") { bob.store.getConversation(conv) != null }

            val m1 = alice.engine.sendText(conv, "Hej Bob, hemligt: ${UUID.randomUUID()}")
            bob.receives(conv, m1)
            assertEquals(alice.store.getMessage(m1)!!.text, bob.store.getMessage(m1)!!.text)
            assertNull(bob.store.getMessage(m1)!!.decryptError)

            val m2 = bob.engine.sendText(conv, "Hej Alice", replyTo = m1)
            alice.receives(conv, m2)
            assertEquals(m1, alice.store.getMessage(m2)!!.replyTo)

            // delivery + read receipts reach the sender
            eventually(timeout, "delivered receipt") { alice.store.getMessage(m1)!!.status.ordinal >= MessageStatus.DELIVERED.ordinal }
            bob.engine.markRead(conv)
            eventually(timeout, "read receipt") { alice.store.getMessage(m1)!!.status == MessageStatus.READ }
            assertEquals(0, bob.store.unreadCount(conv))

            // typing indicator
            alice.engine.sendTyping(conv, true)
            eventually(timeout, "typing indicator") { bob.engine.typing.value[conv]?.contains(alice.userId) == true }

            // edit and delete propagate
            alice.engine.editMessage(m1, "Hej Bob (ändrad)")
            eventually(timeout, "edit") { bob.store.getMessage(m1)?.edited == true }
            assertEquals("Hej Bob (ändrad)", bob.store.getMessage(m1)!!.text)
            alice.engine.deleteMessage(m1)
            eventually(timeout, "delete") { bob.store.getMessage(m1)?.deleted == true }
            assertNull(bob.store.getMessage(m1)!!.text)
            Unit
        } finally {
            alice.engine.stopRealtime(); bob.engine.stopRealtime()
        }
    }

    @Test
    fun offlineCatchUpDeliversEverythingOnceAndInOrder() = runBlocking {
        val conv = directChat(alice, bob)
        bob.engine.sync()
        // bob is offline (no realtime): alice sends 30 messages
        val ids = (1..30).map { alice.engine.sendText(conv, "offline $it") }
        eventually(timeout * 2, "alice's 30 messages accepted") { ids.all { alice.store.getMessage(it)!!.status != MessageStatus.PENDING } }
        assertTrue(ids.all { alice.store.getMessage(it)!!.status == MessageStatus.SENT || alice.store.getMessage(it)!!.status.ordinal > 1 })

        bob.engine.sync()
        assertEquals((1..30).map { "offline $it" }, bob.texts(conv))
        assertEquals(ids, bob.store.getMessages(conv, 100).map { it.id })
        assertTrue(bob.store.getMessages(conv, 100).all { it.decryptError == null })
        val seqs = bob.store.getMessages(conv, 100).map { it.seq!! }
        assertEquals(seqs.sorted(), seqs)
        // a second sync changes nothing
        assertEquals(0, bob.engine.sync())
        assertEquals(30, bob.store.getMessages(conv, 100).size)
        // the server saw the acks: alice learns about delivery
        alice.engine.sync()
        eventually(timeout, "delivery receipts for all") { alice.engine.sync(); ids.all { alice.store.getMessage(it)!!.status.ordinal >= MessageStatus.DELIVERED.ordinal } }
    }

    @Test
    fun duplicateSendOfTheSameIdCreatesOneMessage() = runBlocking {
        val conv = directChat(alice, bob)
        bob.engine.sync()
        val id = alice.engine.sendText(conv, "only once")
        eventually(timeout, "sent") { alice.store.getMessage(id)!!.status != MessageStatus.PENDING }
        // re-send the identical entry, as after a crash between the POST and the bookkeeping
        alice.store.putOutbox(OutboxEntry(id, conv, MessageKind.MESSAGE, null, "only once", null, emptyList(), 1, createdAt = 1))
        alice.engine.processOutbox()
        bob.engine.sync()
        assertEquals(listOf("only once"), bob.texts(conv))
        val onServer = alice.rawApi().listMessages(conv, null, 50)
        assertEquals(1, onServer.count { it.id == id })
    }

    @Test
    fun attachmentIsEncryptedOnTheWireAndRoundTrips() = runBlocking {
        val conv = directChat(alice, bob)
        bob.engine.sync()
        val data = Random(42).nextBytes(2 * 1024 * 1024 + 123)
        val file = File(alice.dir, "pic.bin").apply { writeBytes(data) }
        val id = alice.engine.sendAttachments(
            conv, listOf(OutgoingAttachment(file, "image/jpeg", "image", "pic.jpg", 640, 480, null, null)), caption = "se bild",
        )
        eventually(timeout * 2, "attachment message sent") { alice.store.getMessage(id)!!.status != MessageStatus.PENDING }
        bob.engine.sync()
        val att = bob.store.getMessage(id)!!.attachments.single()
        assertEquals("se bild", bob.store.getMessage(id)!!.text)
        assertEquals(data.size.toLong(), att.size)

        // what the server stores is ciphertext of a different size and content
        val raw = bob.rawApi().let { api ->
            val f = File(bob.dir, "raw.bin")
            api.downloadAttachment(att.id, f)
            f.readBytes()
        }
        assertTrue(raw.size > data.size)
        assertFalse(raw.copyOfRange(0, 1024).contentEquals(data.copyOfRange(0, 1024)))

        val out = bob.engine.downloadAttachment(id, att.id)
        assertContentEquals(data, out.readBytes())
    }

    @Test
    fun groupOfThreeAndRemovedMemberGetsNothingNew() = runBlocking {
        listOf(alice, bob, carol).forEach { it.engine.startRealtime() }
        try {
            val g = alice.engine.createGroup("Familjen $run", listOf(bob.userId, carol.userId))
            eventually(timeout, "members know the group") { bob.store.getConversation(g.id) != null && carol.store.getConversation(g.id) != null }

            val m1 = alice.engine.sendText(g.id, "hej alla")
            bob.receives(g.id, m1); carol.receives(g.id, m1)
            val m2 = carol.engine.sendText(g.id, "hej själv")
            alice.receives(g.id, m2); bob.receives(g.id, m2)
            eventually(timeout, "group delivery") { alice.store.getMessage(m1)!!.status.ordinal >= MessageStatus.DELIVERED.ordinal }

            alice.engine.removeMember(g.id, carol.userId)
            eventually(timeout, "carol learns she was removed") { carol.store.getConversation(g.id) == null }

            val m3 = alice.engine.sendText(g.id, "carol ska inte se detta")
            bob.receives(g.id, m3)
            // carol never gets it: not via sync, not via the API
            carol.engine.sync()
            assertNull(carol.store.getMessage(m3))
            assertFailsWith<ApiException.NotFound> { carol.rawApi().listMessages(g.id, null, 50) }
            // and the server addressed exactly one device
            assertEquals(1, alice.rawApi().listMessages(g.id, null, 50).count { it.id == m3 })
            Unit
        } finally {
            listOf(alice, bob, carol).forEach { it.engine.stopRealtime() }
        }
    }

    @Test
    fun secondDeviceReceivesSubsequentMessagesOnBothDevices() = runBlocking {
        val conv = directChat(alice, bob)
        bob.engine.sync()
        alice.engine.startRealtime(); bob.engine.startRealtime()
        val bob2 = Phone("bob2").also { phones += it }
        try {
            val before = alice.engine.sendText(conv, "innan bobs andra telefon")
            bob.receives(conv, before)

            bob2.engine.login(url, "bob_$run", "bob-password-1")
            bob2.engine.startRealtime()
            eventually(timeout, "alice notices the new device") {
                alice.store.getMessages(conv, 50).any { it.systemEvent is SystemEvent.NewDevice }
            }

            val after = alice.engine.sendText(conv, "efter bobs andra telefon")
            bob.receives(conv, after); bob2.receives(conv, after)
            assertEquals("efter bobs andra telefon", bob.store.getMessage(after)!!.text)
            assertEquals("efter bobs andra telefon", bob2.store.getMessage(after)!!.text)
            assertNull(bob2.store.getMessage(before), "a new device cannot read earlier messages")

            // bob answers from the second phone; alice and bob's first phone both get it
            val reply = bob2.engine.sendText(conv, "svar från telefon två")
            alice.receives(conv, reply); bob.receives(conv, reply)
            assertTrue(bob.store.getMessage(reply)!!.outgoing)
            assertEquals("svar från telefon två", alice.store.getMessage(reply)!!.text)
            Unit
        } finally {
            alice.engine.stopRealtime(); bob.engine.stopRealtime(); bob2.engine.stopRealtime()
        }
    }

    @Test
    fun serverDatabaseNeverContainsPlaintext() = runBlocking {
        Assume.assumeTrue("FAMILYCHAT_TEST_DB not set: skipping plaintext check", dbPath != null)
        val conv = directChat(alice, bob)
        bob.engine.sync()
        val marker = "PLAINTEXT-MARKER-${UUID.randomUUID()}"
        val attMarker = "ATTACHMENT-MARKER-${UUID.randomUUID()}"
        val id = alice.engine.sendText(conv, marker)
        val file = File(alice.dir, "marker.txt").apply { writeText(attMarker.repeat(500)) }
        val id2 = alice.engine.sendAttachments(conv, listOf(OutgoingAttachment(file, "text/plain", "file", "marker.txt")))
        eventually(timeout * 2, "both sent") {
            alice.store.getMessage(id)!!.status != MessageStatus.PENDING && alice.store.getMessage(id2)!!.status != MessageStatus.PENDING
        }
        bob.engine.sync()
        assertEquals(marker, bob.store.getMessage(id)!!.text)

        val db = File(dbPath!!)
        val files = listOf(db, File("$dbPath-wal"), File("$dbPath-shm"), File(db.parentFile, "attachments")).filter { it.exists() }
        val blobs = files.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile }.toList() else listOf(f) }
        assertTrue(blobs.any { it.name == db.name }, "database file must exist")
        val needle = marker.toByteArray()
        val needle2 = attMarker.toByteArray()
        for (f in blobs) {
            val bytes = f.readBytes()
            assertFalse(indexOf(bytes, needle) >= 0, "plaintext message found in ${f.name}")
            assertFalse(indexOf(bytes, needle2) >= 0, "plaintext attachment found in ${f.name}")
        }
        // sanity: the scan does see readable server-side metadata (so a pass is not vacuous)
        val all = blobs.filter { it.name.startsWith(db.name) }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertTrue(all.contains("alice_$run"), "scan should see usernames in the database")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
