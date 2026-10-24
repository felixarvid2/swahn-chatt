package se.familychat.core.client

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import se.familychat.core.crypto.AttachmentCryptoException
import se.familychat.core.model.EnvelopeType
import se.familychat.core.model.MessageKind
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineTest {
    private val server = FakeServer()
    private val scope = newScope()

    @AfterTest fun tearDown() = scope.cancel()

    private fun client(name: String, workers: Boolean = false) = TestClient(server, name, scope, workers)

    /** alice + bob signed up, with a direct conversation known to both. */
    private suspend fun pair(): Triple<TestClient, TestClient, String> {
        val alice = client("alice")
        val bob = client("bob")
        alice.signUp(); bob.signUp()
        alice.engine.refreshDirectory(); bob.engine.refreshDirectory()
        val conv = alice.engine.createDirect(bob.userId())
        bob.engine.sync()
        return Triple(alice, bob, conv.id)
    }

    @Test
    fun `sign up uploads keys and prekeys are consumed by senders`() = runBlocking<Unit> {
        val alice = client("alice")
        alice.signUp()
        val dev = server.devices.getValue(alice.deviceIdOnServer())
        assertNotNull(dev.keys)
        assertEquals(100, dev.ec.size)
        assertEquals(100, dev.pq.size)
        assertEquals(alice.engine.account.value!!.deviceNumber, dev.number)
    }

    @Test
    fun `text round trip both directions with receipts, edit and delete`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val id = alice.engine.sendText(convId, "hello bob")
        assertEquals(MessageStatus.PENDING, alice.store.getMessage(id)!!.status)
        val r = alice.engine.processOutbox()
        assertEquals(1, r.sent)
        val sent = alice.store.getMessage(id)!!
        assertEquals(MessageStatus.SENT, sent.status)
        assertNotNull(sent.seq)

        assertEquals(1, bob.engine.sync())
        val got = bob.store.getMessage(id)!!
        assertEquals("hello bob", got.text)
        assertEquals(MessageStatus.RECEIVED, got.status)
        assertFalse(got.outgoing)
        assertEquals(1, bob.store.unreadCount(convId))

        // delivered receipt after bob acked
        alice.engine.sync()
        assertEquals(MessageStatus.DELIVERED, alice.store.getMessage(id)!!.status)

        // read receipt
        bob.engine.markRead(convId)
        assertEquals(0, bob.store.unreadCount(convId))
        alice.engine.sync()
        assertEquals(MessageStatus.READ, alice.store.getMessage(id)!!.status)

        // reply, edit, delete
        val reply = bob.engine.sendText(convId, "hi alice", replyTo = id)
        bob.engine.processOutbox()
        alice.engine.sync()
        assertEquals(id, alice.store.getMessage(reply)!!.replyTo)
        alice.engine.editMessage(id, "hello bob (edited)")
        alice.engine.processOutbox()
        bob.engine.sync()
        val edited = bob.store.getMessage(id)!!
        assertEquals("hello bob (edited)", edited.text)
        assertTrue(edited.edited)
        assertTrue(alice.store.getMessage(id)!!.edited)

        alice.engine.deleteMessage(id)
        assertTrue(alice.store.getMessage(id)!!.deleted)
        alice.engine.processOutbox()
        bob.engine.sync()
        val deleted = bob.store.getMessage(id)!!
        assertTrue(deleted.deleted)
        assertNull(deleted.text)
        assertEquals(0, bob.store.unreadCount(convId)) // deleted messages never count as unread
    }

    @Test
    fun `only the author can edit or delete`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val id = alice.engine.sendText(convId, "mine"); alice.engine.processOutbox(); bob.engine.sync()
        assertFailsWith<IllegalArgumentException> { bob.engine.editMessage(id, "hacked") }
        assertFailsWith<IllegalArgumentException> { bob.engine.deleteMessage(id) }
        assertEquals("mine", bob.store.getMessage(id)!!.text)
    }

    @Test
    fun `messages are ordered by server seq`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val ids = (1..25).map { alice.engine.sendText(convId, "m$it") }
        alice.engine.processOutbox()
        bob.engine.sync()
        assertEquals((1..25).map { "m$it" }, bob.texts(convId))
        val seqs = bob.messages(convId).map { it.seq!! }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(ids, bob.messages(convId).map { it.id })
    }

    @Test
    fun `network failure keeps the message pending with the same id and a retry sends exactly once`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        server.failSends = 2
        val id = alice.engine.sendText(convId, "flaky")
        val first = alice.engine.processOutbox()
        assertTrue(first.offline)
        assertEquals(MessageStatus.PENDING, alice.store.getMessage(id)!!.status)
        assertEquals(1, alice.store.getOutbox().size)
        val envelopesAfterFirst = alice.store.getOutboxEntry(id)

        val second = alice.engine.processOutbox()
        assertTrue(second.offline)
        val third = alice.engine.processOutbox()
        assertEquals(1, third.sent)
        assertEquals(MessageStatus.SENT, alice.store.getMessage(id)!!.status)
        assertTrue(alice.store.getOutbox().isEmpty())
        assertEquals(1, server.messages.values.count { it.msg.id == id })

        bob.engine.sync()
        assertEquals(listOf("flaky"), bob.texts(convId))
        assertNotNull(envelopesAfterFirst)
    }

    @Test
    fun `lost response then retry yields one message (server idempotency)`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        server.loseSendResponses = 1
        val id = alice.engine.sendText(convId, "once")
        assertTrue(alice.engine.processOutbox().offline)
        assertEquals(MessageStatus.PENDING, alice.store.getMessage(id)!!.status)
        val cachedBefore = alice.store.getOutboxEntry(id)!!.envelopes!!
        assertEquals(1, alice.engine.processOutbox().sent)
        assertEquals(1, server.messageCountInConversation(convId))
        bob.engine.sync()
        assertEquals(listOf("once"), bob.texts(convId))
        assertTrue(cachedBefore.isNotEmpty())
        // the retry reused the very same ciphertext, so no extra ratchet step was burnt
        assertEquals(cachedBefore.single().body, server.messages.getValue(id).envelopes.values.single().body)
    }

    @Test
    fun `the same id sent twice creates one message`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val id = alice.engine.sendText(convId, "dup")
        alice.engine.processOutbox()
        // re-queue the identical entry (as after a crash between POST and bookkeeping)
        alice.store.putOutbox(
            OutboxEntry(id, convId, MessageKind.MESSAGE, null, "dup", null, emptyList(), 1, createdAt = 1),
        )
        alice.engine.processOutbox()
        assertEquals(1, server.messageCountInConversation(convId))
        bob.engine.sync()
        assertEquals(listOf("dup"), bob.texts(convId))
    }

    @Test
    fun `409 missing device refreshes devices and keys then retries`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        // warm up: alice has bob's device list and a session
        alice.engine.sendText(convId, "one"); alice.engine.processOutbox(); bob.engine.sync()

        // bob logs in on a second phone; alice's cached device list is now stale
        val bob2 = client("bob")
        bob2.engine.login("https://fake.example", "bob", "password-bob")
        assertEquals(2, server.devices.values.count { it.userId == bob.userId() })

        val id = alice.engine.sendText(convId, "two")
        val result = alice.engine.processOutbox()
        assertEquals(1, result.sent)
        assertEquals(MessageStatus.SENT, alice.store.getMessage(id)!!.status)
        // the first attempt was rejected, then a second one succeeded with both devices
        assertEquals(2, server.log.count { it == "send:$id" })
        assertEquals(2, server.messages.getValue(id).envelopes.size)
        bob.engine.sync(); bob2.engine.sync()
        assertEquals("two", bob.store.getMessage(id)!!.text)
        assertEquals("two", bob2.store.getMessage(id)!!.text)
        // bob2 only sees messages created after it registered
        assertEquals(listOf("two"), bob2.texts(convId))
    }

    @Test
    fun `409 extra device drops its session and retries`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val bob2 = client("bob")
        bob2.engine.login("https://fake.example", "bob", "password-bob")
        alice.engine.refreshDevices(bob.userId())
        alice.engine.sendText(convId, "both"); alice.engine.processOutbox()
        bob2.engine.sync()
        val extraNumber = server.devices.getValue(bob2.deviceIdOnServer()).number
        val extraId = bob2.deviceIdOnServer()
        assertNotNull(alice.protocol.loadSession(se.familychat.core.crypto.DeviceAddress.name(bob.userId(), extraId)))
        server.revokeDevice(extraId) // alice does not know yet

        val id = alice.engine.sendText(convId, "only bob1")
        assertEquals(1, alice.engine.processOutbox().sent)
        assertEquals(1, server.messages.getValue(id).envelopes.size)
        assertEquals(extraNumber, 2)
        assertNull(alice.protocol.loadSession(se.familychat.core.crypto.DeviceAddress.name(bob.userId(), extraId)))
    }

    @Test
    fun `mismatch retries are bounded and the message stays pending`() = runBlocking<Unit> {
        val (alice, _, convId) = pair()
        server.alwaysMismatch = true
        val id = alice.engine.sendText(convId, "never accepted")
        val r = alice.engine.processOutbox()
        assertEquals(0, r.sent)
        assertEquals(1, r.pending)
        assertEquals(5, server.log.count { it == "send:$id" }) // 1 try + 4 retries
        assertEquals(MessageStatus.PENDING, alice.store.getMessage(id)!!.status)
        server.alwaysMismatch = false
        assertEquals(1, alice.engine.processOutbox().sent)
    }

    @Test
    fun `events replayed after a crash are applied once`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val id = alice.engine.sendText(convId, "replay me"); alice.engine.processOutbox()
        alice.engine.editMessage(id, "replayed edit"); alice.engine.processOutbox()
        bob.engine.sync()
        // crash before the cursor was durable: the server resends the same events
        bob.store.setSyncCursor(0)
        bob.engine.sync()
        val msgs = bob.messages(convId)
        assertEquals(1, msgs.size)
        assertEquals("replayed edit", msgs.single().text)
        assertNull(msgs.single().decryptError)
        // and once more, with the edit event replayed on its own
        bob.store.setSyncCursor(0)
        bob.engine.sync()
        assertEquals("replayed edit", bob.store.getMessage(id)!!.text)
        assertNull(bob.store.getMessage(id)!!.decryptError)
    }

    @Test
    fun `ack is sent only after the cursor and messages are committed`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        alice.engine.sendText(convId, "a"); alice.engine.sendText(convId, "b"); alice.engine.processOutbox()
        val observed = mutableListOf<Triple<Long, Long, Int>>()
        server.onAck = { dev, cursor ->
            if (dev == bob.deviceIdOnServer()) runBlocking {
                observed += Triple(cursor, bob.store.getSyncCursor(), bob.store.getMessages(convId, 100).size)
            }
        }
        bob.engine.sync()
        assertTrue(observed.isNotEmpty())
        for ((cursor, storedCursor, count) in observed) {
            assertEquals(cursor, storedCursor, "cursor must be committed before ack")
            assertEquals(2, count, "messages must be stored before ack")
        }
    }

    @Test
    fun `a failure while applying a page prevents the ack and the cursor move, and the retry loses nothing`() = runBlocking<Unit> {
        val alice = client("alice")
        alice.signUp()
        val failing = FailingStore()
        val bob = TestClient(server, "bob", scope, store = failing)
        bob.signUp()
        alice.engine.refreshDirectory(); bob.engine.refreshDirectory()
        val conv = alice.engine.createDirect(bob.userId())
        bob.engine.sync()
        alice.engine.sendText(conv.id, "boom"); alice.engine.processOutbox()
        val cursorBefore = bob.store.getSyncCursor()
        val acksBefore = server.log.count { it.startsWith("ack:") }

        failing.failGetMessage = true // storage breaks before the message is decrypted
        assertFailsWith<IllegalStateException> { bob.engine.sync() }
        assertEquals(cursorBefore, bob.store.getSyncCursor())
        assertEquals(acksBefore, server.log.count { it.startsWith("ack:") })

        failing.failGetMessage = false
        bob.engine.sync() // next attempt works; the message is decrypted exactly once
        assertEquals(listOf("boom"), bob.texts(conv.id))
        assertNull(bob.store.getMessages(conv.id, 10).single().decryptError)
        assertTrue(server.log.count { it.startsWith("ack:") } > acksBefore)
    }

    @Test
    fun `a transient storage error while saving a decrypted message is retried instead of losing the plaintext`() = runBlocking<Unit> {
        val alice = client("alice")
        alice.signUp()
        val failing = FailingStore()
        val bob = TestClient(server, "bob", scope, store = failing)
        bob.signUp()
        alice.engine.refreshDirectory(); bob.engine.refreshDirectory()
        val conv = alice.engine.createDirect(bob.userId())
        bob.engine.sync()
        alice.engine.sendText(conv.id, "kept"); alice.engine.processOutbox()
        failing.failUpsertMessageTimes = 2
        bob.engine.sync()
        assertEquals(listOf("kept"), bob.texts(conv.id))
        assertNull(bob.store.getMessages(conv.id, 10).single().decryptError)
    }

    @Test
    fun `concurrent syncs are serialised`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        repeat(10) { alice.engine.sendText(convId, "c$it") }
        alice.engine.processOutbox()
        (1..6).map { async(kotlinx.coroutines.Dispatchers.Default) { bob.engine.sync() } }.awaitAll()
        assertEquals((0 until 10).map { "c$it" }, bob.texts(convId))
        assertTrue(bob.messages(convId).all { it.decryptError == null })
    }

    @Test
    fun `attachment round trip with multi megabyte file`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val data = Random(9).nextBytes(3 * 1024 * 1024 + 17)
        val f = File(alice.dir, "photo.jpg").apply { writeBytes(data) }
        val id = alice.engine.sendAttachments(
            convId, listOf(OutgoingAttachment(f, "image/jpeg", "image", "photo.jpg", 800, 600, null, "dGh1bWI=")), caption = "look",
        )
        assertEquals(1, alice.engine.processOutbox().sent)
        val sentAtt = alice.store.getMessage(id)!!.attachments.single()
        assertFalse(sentAtt.id.startsWith("local-"))
        assertNotNull(sentAtt.key)
        // the server holds ciphertext only
        val blob = server.attachmentBlob(sentAtt.id)!!
        assertTrue(blob.size > data.size)
        assertFalse(blob.copyOfRange(0, 4096).contentEquals(data.copyOfRange(0, 4096)))

        bob.engine.sync()
        val msg = bob.store.getMessage(id)!!
        assertEquals("look", msg.text)
        val att = msg.attachments.single()
        assertEquals("image/jpeg", att.mime); assertEquals(800, att.width); assertEquals("dGh1bWI=", att.thumbnail)
        assertEquals(DownloadState.NONE, att.downloadState)
        val out = bob.engine.downloadAttachment(id, att.id)
        assertContentEquals(data, out.readBytes())
        val after = bob.store.getMessage(id)!!.attachments.single()
        assertEquals(DownloadState.DONE, after.downloadState)
        assertEquals(out.absolutePath, after.localPath)
    }

    @Test
    fun `tampered or missing attachment ciphertext fails safely`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val f = File(alice.dir, "doc.bin").apply { writeBytes(Random(1).nextBytes(100_000)) }
        val id = alice.engine.sendAttachments(convId, listOf(OutgoingAttachment(f, "application/pdf", "file")))
        alice.engine.processOutbox(); bob.engine.sync()
        val att = bob.store.getMessage(id)!!.attachments.single()
        server.corruptAttachments = true
        assertFailsWith<AttachmentIntegrityException> { bob.engine.downloadAttachment(id, att.id) }
        assertEquals(DownloadState.FAILED, bob.store.getMessage(id)!!.attachments.single().downloadState)
        assertTrue(bob.store.getMessage(id)!!.attachments.single().localPath == null)
        server.corruptAttachments = false
        val ok = bob.engine.downloadAttachment(id, att.id) // retry works
        assertEquals(100_000, ok.length().toInt())
    }

    @Test
    fun `wrong attachment key is reported as a crypto error`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val f = File(alice.dir, "doc.bin").apply { writeBytes(Random(2).nextBytes(10_000)) }
        val id = alice.engine.sendAttachments(convId, listOf(OutgoingAttachment(f, "application/pdf", "file")))
        alice.engine.processOutbox(); bob.engine.sync()
        bob.store.updateMessage(id) { m ->
            m.copy(attachments = m.attachments.map { it.copy(key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })) })
        }
        assertFailsWith<AttachmentCryptoException> { bob.engine.downloadAttachment(id, bob.store.getMessage(id)!!.attachments.single().id) }
    }

    @Test
    fun `upload survives a failed send and is not repeated`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val f = File(alice.dir, "a.bin").apply { writeBytes(Random(3).nextBytes(50_000)) }
        server.failSends = 1
        val id = alice.engine.sendAttachments(convId, listOf(OutgoingAttachment(f, "application/octet-stream", "file")))
        assertTrue(alice.engine.processOutbox().offline)
        assertEquals(1, server.log.count { it.startsWith("upload:") })
        assertEquals(1, alice.engine.processOutbox().sent)
        assertEquals(1, server.log.count { it.startsWith("upload:") })
        bob.engine.sync()
        assertEquals(1, bob.store.getMessage(id)!!.attachments.size)
    }

    @Test
    fun `group of three, removal stops delivery to the removed member`() = runBlocking<Unit> {
        val alice = client("alice"); val bob = client("bob"); val carol = client("carol")
        listOf(alice, bob, carol).forEach { it.signUp() }
        alice.engine.refreshDirectory()
        val g = alice.engine.createGroup("Family", listOf(bob.userId(), carol.userId()))
        bob.engine.sync(); carol.engine.sync()

        val m1 = alice.engine.sendText(g.id, "hello all"); alice.engine.processOutbox()
        bob.engine.sync(); carol.engine.sync()
        assertEquals("hello all", bob.store.getMessage(m1)!!.text)
        assertEquals("hello all", carol.store.getMessage(m1)!!.text)
        // everybody delivered -> alice sees DELIVERED
        alice.engine.sync()
        assertEquals(MessageStatus.DELIVERED, alice.store.getMessage(m1)!!.status)

        alice.engine.removeMember(g.id, carol.userId())
        carol.engine.sync()
        assertNull(carol.store.getConversation(g.id))

        val m2 = alice.engine.sendText(g.id, "carol is gone"); alice.engine.processOutbox()
        assertEquals(1, server.messages.getValue(m2).envelopes.size) // only bob's device
        bob.engine.sync(); carol.engine.sync()
        assertEquals("carol is gone", bob.store.getMessage(m2)!!.text)
        assertNull(carol.store.getMessage(m2))

        // read receipts only count the remaining members
        bob.engine.markRead(g.id); alice.engine.sync()
        assertEquals(MessageStatus.READ, alice.store.getMessage(m2)!!.status)
    }

    @Test
    fun `second device of a contact gets a notice and receives later messages on both devices`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        alice.engine.sendText(convId, "before"); alice.engine.processOutbox(); bob.engine.sync()

        val bob2 = client("bob")
        bob2.engine.login("https://fake.example", "bob", "password-bob")
        alice.engine.sync() // devices_changed
        val notices = alice.messages(convId).filter { it.systemEvent is SystemEvent.NewDevice }
        assertEquals(1, notices.size)
        assertEquals("bob phone", (notices.single().systemEvent as SystemEvent.NewDevice).deviceName)

        val id = alice.engine.sendText(convId, "after"); alice.engine.processOutbox()
        bob.engine.sync(); bob2.engine.sync()
        assertEquals("after", bob.store.getMessage(id)!!.text)
        assertEquals("after", bob2.store.getMessage(id)!!.text)
        assertNull(bob2.store.getMessages(convId, 10).firstOrNull { it.text == "before" })

        // bob2 can answer; both of alice's and bob's other device receive it
        val reply = bob2.engine.sendText(convId, "from my second phone"); bob2.engine.processOutbox()
        alice.engine.sync(); bob.engine.sync()
        assertEquals("from my second phone", alice.store.getMessage(reply)!!.text)
        // bob's first phone does not trust the new phone of his own account until bob approves it there
        assertEquals(DecryptError.UNTRUSTED_IDENTITY, bob.store.getMessage(reply)!!.decryptError)
        assertNull(bob.store.getMessage(reply)!!.text)
        bob.approveAllPending()
        val onBob1 = bob.store.getMessage(reply)!!
        assertEquals("from my second phone", onBob1.text)
        assertTrue(onBob1.outgoing) // sent by bob himself, from another device
    }

    @Test
    fun `own other device receives my messages as outgoing`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val alice2 = client("alice")
        alice2.engine.login("https://fake.example", "alice", "password-alice")
        alice2.engine.refreshDirectory()
        alice.engine.refreshDevices(alice.userId())
        alice.approveAllPending() // the old phone approves the new one of the same account
        val id = alice.engine.sendText(convId, "to everyone"); alice.engine.processOutbox()
        alice2.engine.sync()
        val m = alice2.store.getMessage(id)!!
        assertEquals("to everyone", m.text)
        assertTrue(m.outgoing)
        assertEquals(0, alice2.store.unreadCount(convId))
    }

    @Test
    fun `corrupted incoming ciphertext becomes a could-not-decrypt placeholder and later messages still work`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        val bad = alice.engine.sendText(convId, "will be corrupted"); alice.engine.processOutbox()
        val good = alice.engine.sendText(convId, "fine"); alice.engine.processOutbox()
        // flip a bit in the stored ciphertext of the first message, as a faulty server or disk would
        val rec = server.messages.getValue(bad)
        val env = rec.envelopes.values.single()
        val raw = java.util.Base64.getDecoder().decode(env.body)
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x20).toByte()
        val tampered = env.copy(body = java.util.Base64.getEncoder().encodeToString(raw))
        val deviceId = rec.envelopes.keys.single()
        server.queues.getValue(deviceId).replaceAll { e ->
            if (e.type == "message" && e.data.toString().contains(bad)) {
                val m = se.familychat.core.model.FamilyChatJson.decodeFromJsonElement(se.familychat.core.model.Message.serializer(), e.data!!)
                e.copy(data = se.familychat.core.model.FamilyChatJson.encodeToJsonElement(se.familychat.core.model.Message.serializer(), m.copy(envelope = tampered)))
            } else e
        }
        bob.engine.sync()
        val badMsg = bob.store.getMessage(bad)!!
        assertEquals(DecryptError.CORRUPT, badMsg.decryptError)
        assertNull(badMsg.text)
        assertEquals("fine", bob.store.getMessage(good)!!.text)
    }

    @Test
    fun `changed identity of a known device blocks decryption until accepted`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        alice.engine.sendText(convId, "hi"); alice.engine.processOutbox(); bob.engine.sync()
        val aliceDevice = alice.deviceIdOnServer()
        // The server swaps the identity of alice's device for a key of its own (same uuid) and has that device write.
        val rogue = RogueDevice(alice.userId())
        server.swapIdentity(aliceDevice, rogue.upload, announce = true)
        val id = java.util.UUID.randomUUID().toString()
        val content = se.familychat.core.model.Content(text = "I am new", conversationId = convId, messageId = id)
        val env = rogue.encryptFor(
            se.familychat.core.crypto.Recipient(bob.userId(), bob.deviceIdOnServer()), server.bundleFor(aliceDevice, bob), content,
        )
        server.injectMessage(bob.deviceIdOnServer(), forgedMessage(id, convId, alice.userId(), aliceDevice, env))

        val events = mutableListOf<EngineEvent>()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { bob.engine.events.collect { events += it } }
        bob.engine.sync()
        val m = bob.store.getMessage(id)!!
        assertEquals(DecryptError.UNTRUSTED_IDENTITY, m.decryptError)
        assertNotNull(m.pendingEnvelopeBody)
        eventually(what = "identity event") { events.any { it is EngineEvent.IdentityChanged } }
        job.cancel()
        assertTrue(bob.store.getMessages(convId, 20).any { it.systemEvent is SystemEvent.IdentityChanged })
        val pending = bob.engine.pendingIdentityChanges().single()
        assertEquals(alice.userId(), pending.userId)
        assertEquals(aliceDevice, pending.deviceId)

        assertTrue(bob.engine.acceptIdentity(pending.userId, pending.deviceId, pending.newIdentityKey!!))
        val fixed = bob.store.getMessage(id)!!
        assertNull(fixed.decryptError)
        assertEquals("I am new", fixed.text)
        assertTrue(bob.engine.pendingIdentityChanges().isEmpty())
    }

    @Test
    fun `safety numbers match on both sides`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        alice.engine.sendText(convId, "hi"); alice.engine.processOutbox(); bob.engine.sync()
        val aliceDevice = alice.engine.account.value!!.deviceId
        val bobDevice = bob.engine.account.value!!.deviceId
        val a = alice.engine.safetyNumber(bob.userId(), bobDevice)!!
        val b = bob.engine.safetyNumber(alice.userId(), aliceDevice)!!
        assertEquals(a.digits, b.digits)
        assertEquals(60, a.digits.length)
        assertFalse(alice.engine.isVerified(bobDevice))
        alice.engine.setVerified(bobDevice, true)
        assertTrue(alice.engine.isVerified(bobDevice))
    }

    @Test
    fun `typing indicators arrive via realtime and expire`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        alice.engine.startRealtime(); bob.engine.startRealtime()
        eventually(what = "connected") { alice.engine.realtimeState.value == se.familychat.core.network.RealtimeState.CONNECTED }
        alice.engine.sendTyping(convId, true)
        eventually(what = "typing shown") { bob.engine.typing.value[convId]?.contains(alice.userId()) == true }
        eventually(what = "typing expired") { bob.engine.typing.value.isEmpty() }
        alice.engine.stopRealtime(); bob.engine.stopRealtime()
    }

    @Test
    fun `realtime loop syncs on hints and delivers the outbox in the background`() = runBlocking<Unit> {
        val alice = TestClient(server, "alice", scope, workers = true)
        val bob = TestClient(server, "bob", scope, workers = true)
        alice.signUp(); bob.signUp()
        alice.engine.refreshDirectory(); bob.engine.refreshDirectory()
        val conv = alice.engine.createDirect(bob.userId())
        alice.engine.startRealtime(); bob.engine.startRealtime()
        eventually(what = "bob sees conversation") { bob.store.getConversation(conv.id) != null }

        val id = alice.engine.sendText(conv.id, "live") // no manual processOutbox
        eventually(what = "bob receives live") { bob.store.getMessage(id)?.text == "live" }
        eventually(what = "delivery receipt") { alice.store.getMessage(id)?.status == MessageStatus.DELIVERED }
        bob.engine.markRead(conv.id)
        eventually(what = "read receipt") { alice.store.getMessage(id)?.status == MessageStatus.READ }

        // offline catch-up: bob stops, alice sends 30, bob syncs once and gets all in order
        bob.engine.stopRealtime()
        val ids = (1..30).map { alice.engine.sendText(conv.id, "n$it") }
        eventually(what = "all sent") { ids.all { alice.store.getMessage(it)?.status.let { s -> s != null && s != MessageStatus.PENDING } } }
        bob.engine.sync()
        assertEquals(listOf("live") + (1..30).map { "n$it" }, bob.texts(conv.id))
        bob.engine.sync()
        assertEquals(31, bob.messages(conv.id).size)
        alice.engine.stopRealtime()
    }

    @Test
    fun `logout wipes everything and revokes the device`() = runBlocking<Unit> {
        val (alice, bob, convId) = pair()
        alice.engine.sendText(convId, "x"); alice.engine.processOutbox()
        val deviceId = alice.deviceIdOnServer()
        alice.engine.logout()
        assertNull(alice.engine.account.value)
        assertNull(alice.store.getAccount())
        assertTrue(alice.store.getConversations().isEmpty())
        assertNull(alice.protocol.getLocalIdentity())
        assertTrue(server.devices.getValue(deviceId).revoked)
        assertNull(alice.tokens.getTokens())
        assertNull(alice.engine.restoreSession())
    }
}

/** A store with injectable failures, to simulate storage trouble while applying a sync page. */
class FailingStore(private val delegate: InMemoryLocalStore = InMemoryLocalStore()) : LocalStore by delegate {
    @Volatile var failGetMessage = false
    @Volatile var failUpsertMessageTimes = 0

    override suspend fun getMessage(id: String): StoredMessage? {
        if (failGetMessage) throw IllegalStateException("simulated storage failure")
        return delegate.getMessage(id)
    }

    override suspend fun upsertMessage(message: StoredMessage) {
        if (failUpsertMessageTimes > 0 && !message.isSystem) {
            failUpsertMessageTimes--
            throw IllegalStateException("simulated storage failure")
        }
        delegate.upsertMessage(message)
    }
}
