package se.familychat.core.client

import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import se.familychat.core.crypto.Recipient
import se.familychat.core.model.Content
import se.familychat.core.model.EventType
import se.familychat.core.model.MessageKind
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server controls every API response. These tests make [FakeServer] behave maliciously (remapped device numbers,
 * devices injected through 409s, emptied-then-refilled lists, devices added to the user's own account, mislabelled
 * sender ids, forged and moved messages) and check that nothing is silently encrypted to an injected device or accepted
 * from one, and that the user is told. Rogue devices have real keys, so refusals are about trust, not bad signatures.
 */
class ServerAttackTest {
    private val server = FakeServer()
    private val scope = newScope()

    @AfterTest fun tearDown() = scope.cancel()

    private fun client(name: String) = TestClient(server, name, scope, false)

    private class Trio(val alice: TestClient, val bob: TestClient, val carol: TestClient, val ab: String, val ac: String)

    /** alice+bob and alice+carol direct conversations, everything synced. */
    private suspend fun trio(): Trio {
        val alice = client("alice"); val bob = client("bob"); val carol = client("carol")
        alice.signUp(); bob.signUp(); carol.signUp()
        alice.engine.refreshDirectory(); bob.engine.refreshDirectory(); carol.engine.refreshDirectory()
        val ab = alice.engine.createDirect(bob.userId()).id
        val ac = alice.engine.createDirect(carol.userId()).id
        bob.engine.sync(); carol.engine.sync()
        return Trio(alice, bob, carol, ab, ac)
    }

    private suspend fun pair(): Triple<TestClient, TestClient, String> {
        val t = trio()
        return Triple(t.alice, t.bob, t.ab)
    }

    private suspend fun TestClient.events(conv: String) = messages(conv).mapNotNull { it.systemEvent }

    private suspend fun TestClient.send(conv: String, text: String): String {
        val id = engine.sendText(conv, text)
        engine.processOutbox()
        return id
    }

    private fun serverEnvelopeDevices(messageId: String) = server.messages.getValue(messageId).envelopes.keys

    // ---------------------------------------------------------------------------------------------------------------
    // F1 a: the server controls the device number; the uuid is the identity
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `renumbering devices with the same keys changes nothing`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        alice.send(conv, "before")
        bob.engine.sync()
        server.listDevicesHook = { _, honest -> honest.map { it.copy(number = it.number + 10) } }
        server.getKeysHook = { _, _, honest -> honest.copy(devices = honest.devices.map { it.copy(deviceNumber = 99) }) }
        server.announceDevicesChanged(bob.userId())
        alice.engine.sync()
        val id = alice.send(conv, "after")
        assertEquals(MessageStatus.SENT, alice.store.getMessage(id)!!.status)
        bob.engine.sync()
        assertEquals("after", bob.store.getMessage(id)!!.text)
        assertTrue(alice.engine.pendingIdentityChanges().isEmpty())
        assertTrue(alice.events(conv).none { it is SystemEvent.IdentityChanged || it is SystemEvent.NewDevice })
    }

    @Test
    fun `a known uuid listed with a new number and the servers own identity key is an identity change and is not encrypted to`() =
        runBlocking<Unit> {
            val (alice, bob, conv) = pair()
            val bobDevice = bob.deviceIdOnServer()
            val rogue = RogueDevice(bob.userId())
            server.listDevicesHook = { uid, honest ->
                if (uid != bob.userId()) honest
                else honest.map { it.copy(number = it.number + 5, identityKey = rogue.identityKey) }
            }
            server.getKeysHook = { _, _, honest ->
                honest.copy(devices = honest.devices.map { rogue.bundle(asDeviceId = it.deviceId, number = 6) })
            }
            server.announceDevicesChanged(bob.userId())
            alice.engine.sync()

            val pending = alice.engine.pendingIdentityChanges().single()
            assertEquals(bobDevice, pending.deviceId)
            assertEquals(IdentityChange.Kind.CHANGED, pending.kind)
            assertTrue(alice.events(conv).any { it is SystemEvent.IdentityChanged && it.deviceId == bobDevice })

            val id = alice.engine.sendText(conv, "secret")
            val r = alice.engine.processOutbox()
            assertEquals(1, r.failed)
            val m = alice.store.getMessage(id)!!
            assertEquals(MessageStatus.FAILED, m.status)
            assertEquals(FailureReason.IDENTITY_CHANGED, m.failureReason)
            assertFalse(server.messages.containsKey(id), "nothing may reach the server for a device with a swapped identity")
        }

    @Test
    fun `a bundle with another identity than the pinned one is refused and shows up as pending change (send path)`() =
        runBlocking<Unit> {
            val (alice, bob, conv) = pair() // no message yet: no session, a bundle will be fetched
            val rogue = RogueDevice(bob.userId())
            server.getKeysHook = { _, _, honest ->
                honest.copy(devices = honest.devices.map { rogue.bundle(asDeviceId = it.deviceId, number = it.deviceNumber) })
            }
            val id = alice.engine.sendText(conv, "secret")
            assertEquals(1, alice.engine.processOutbox().failed)
            assertEquals(FailureReason.IDENTITY_CHANGED, alice.store.getMessage(id)!!.failureReason)
            assertFalse(server.messages.containsKey(id))
            // same path as on receive: pending change and a notice in the conversation (F5)
            val pending = alice.engine.pendingIdentityChanges().single()
            assertEquals(bob.deviceIdOnServer(), pending.deviceId)
            assertTrue(alice.events(conv).any { it is SystemEvent.IdentityChanged })
            // the pending key came from a bundle only; the device list still lists the pinned one, so it cannot be accepted
            assertFalse(alice.engine.acceptIdentity(bob.userId(), bob.deviceIdOnServer(), pending.newIdentityKey!!))
        }

    @Test
    fun `a prekey message presenting an identity that differs from the listed one cannot be accepted`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        val bobDevice = bob.deviceIdOnServer()
        val rogue = RogueDevice(bob.userId()) // list stays honest: bob's real key
        val id = UUID.randomUUID().toString()
        val env = rogue.encryptFor(
            Recipient(alice.userId(), alice.deviceIdOnServer()), server.bundleFor(bobDevice, alice),
            Content(text = "from bob?", conversationId = conv, messageId = id),
        )
        server.injectMessage(alice.deviceIdOnServer(), forgedMessage(id, conv, bob.userId(), bobDevice, env))
        alice.engine.sync()
        val m = alice.store.getMessage(id)!!
        assertEquals(DecryptError.UNTRUSTED_IDENTITY, m.decryptError)
        assertNull(m.text)
        val pending = alice.engine.pendingIdentityChanges().single()
        assertFalse(alice.engine.acceptIdentity(pending.userId, pending.deviceId, pending.newIdentityKey!!))
        assertEquals(DecryptError.UNTRUSTED_IDENTITY, alice.store.getMessage(id)!!.decryptError)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F1 b: devices introduced through paths other than a devices_changed event
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `a device injected via a 409 gets a notice (no verified contact)`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        alice.send(conv, "one"); bob.engine.sync()
        val rogue = RogueDevice(bob.userId())
        val rogueId = server.addDevice(bob.userId(), rogue.upload) // no devices_changed event
        val id = alice.send(conv, "two")
        // TOFU: with nothing verified the message goes out, but the user was told about the new device
        assertEquals(MessageStatus.SENT, alice.store.getMessage(id)!!.status)
        assertTrue(rogueId in serverEnvelopeDevices(id))
        val notice = alice.events(conv).filterIsInstance<SystemEvent.NewDevice>().single()
        assertEquals(rogueId, notice.deviceId)
        assertEquals(bob.userId(), notice.userId)
        assertFalse(notice.needsApproval)
    }

    @Test
    fun `a device injected via a 409 next to a verified device is blocked until the user accepts it`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        alice.send(conv, "one"); bob.engine.sync()
        alice.engine.setVerified(bob.deviceIdOnServer(), true)
        val rogue = RogueDevice(bob.userId())
        val rogueId = server.addDevice(bob.userId(), rogue.upload)

        val id = alice.send(conv, "two")
        val m = alice.store.getMessage(id)!!
        assertEquals(MessageStatus.FAILED, m.status)
        assertEquals(FailureReason.IDENTITY_CHANGED, m.failureReason)
        assertFalse(server.messages.containsKey(id), "the listener must not receive anything")
        val pending = alice.engine.pendingIdentityChanges().single()
        assertEquals(rogueId, pending.deviceId)
        assertEquals(IdentityChange.Kind.NEW_DEVICE, pending.kind)
        val notice = alice.events(conv).filterIsInstance<SystemEvent.NewDevice>().single()
        assertTrue(notice.needsApproval)

        // the user compared the safety number and accepts: the message goes out, now including the new device
        assertTrue(alice.engine.safetyNumber(bob.userId(), rogueId) != null)
        assertTrue(alice.engine.acceptIdentity(bob.userId(), rogueId, pending.newIdentityKey!!))
        alice.engine.processOutbox()
        assertEquals(MessageStatus.SENT, alice.store.getMessage(id)!!.status)
        assertTrue(rogueId in serverEnvelopeDevices(id))
        assertTrue(alice.engine.pendingIdentityChanges().isEmpty())
    }

    @Test
    fun `an unapproved device cannot send either - its messages wait for the decision`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        alice.send(conv, "one"); bob.engine.sync()
        alice.engine.setVerified(bob.deviceIdOnServer(), true)
        val rogue = RogueDevice(bob.userId())
        val rogueId = server.addDevice(bob.userId(), rogue.upload, announce = true)
        alice.engine.sync() // devices_changed: pinned unapproved
        val id = UUID.randomUUID().toString()
        val env = rogue.encryptFor(
            Recipient(alice.userId(), alice.deviceIdOnServer()), server.bundleFor(rogueId, alice),
            Content(text = "hello from the new device", conversationId = conv, messageId = id),
        )
        server.injectMessage(alice.deviceIdOnServer(), forgedMessage(id, conv, bob.userId(), rogueId, env))
        alice.engine.sync()
        assertEquals(DecryptError.UNTRUSTED_IDENTITY, alice.store.getMessage(id)!!.decryptError)
        assertNull(alice.store.getMessage(id)!!.text)
        assertTrue(alice.engine.acceptIdentity(bob.userId(), rogueId, alice.engine.pendingIdentityChanges().single().newIdentityKey!!))
        assertEquals("hello from the new device", alice.store.getMessage(id)!!.text)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F1 c: emptying the device list must not reset "new device" detection
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `a device appearing after an empty list is still announced and blocked next to a verified one`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        alice.send(conv, "one"); bob.engine.sync()
        alice.engine.setVerified(bob.deviceIdOnServer(), true)
        server.listDevicesHook = { uid, honest -> if (uid == bob.userId()) emptyList() else honest }
        alice.engine.refreshDevices(bob.userId())
        assertTrue(alice.store.getDevices(bob.userId()).isEmpty())
        server.listDevicesHook = null
        val rogueId = server.addDevice(bob.userId(), RogueDevice(bob.userId()).upload)
        alice.engine.refreshDevices(bob.userId())

        val notices = alice.events(conv).filterIsInstance<SystemEvent.NewDevice>()
        assertEquals(listOf(rogueId), notices.map { it.deviceId }) // bob's own device was known all along
        assertTrue(notices.single().needsApproval)
        val id = alice.send(conv, "two")
        assertEquals(MessageStatus.FAILED, alice.store.getMessage(id)!!.status)
        assertFalse(server.messages.containsKey(id))
    }

    @Test
    fun `removing and re-adding a known device is not a new device`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        alice.send(conv, "one"); bob.engine.sync()
        server.listDevicesHook = { uid, honest -> if (uid == bob.userId()) emptyList() else honest }
        alice.engine.refreshDevices(bob.userId())
        server.listDevicesHook = null
        alice.engine.refreshDevices(bob.userId())
        assertTrue(alice.events(conv).none { it is SystemEvent.NewDevice })
        val id = alice.send(conv, "two")
        assertEquals(MessageStatus.SENT, alice.store.getMessage(id)!!.status)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F1 d: a device added to the user's OWN account
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `a device added to the own account is announced in every conversation and blocked until approved`() = runBlocking<Unit> {
        val t = trio()
        t.alice.send(t.ab, "x"); t.bob.engine.sync()
        val rogueId = server.addDevice(t.alice.userId(), RogueDevice(t.alice.userId()).upload, announce = true)
        t.alice.engine.sync()
        for (c in listOf(t.ab, t.ac)) {
            val n = t.alice.events(c).filterIsInstance<SystemEvent.NewDevice>().single()
            assertEquals(t.alice.userId(), n.userId)
            assertEquals(rogueId, n.deviceId)
            assertTrue(n.needsApproval, "no verified device is needed: a copy for a new own device is always held back")
        }
        // other members of the conversation hear about alice's new device too (their rule: only blocked if they verified)
        t.bob.engine.sync()
        val seenByBob = t.bob.events(t.ab).filterIsInstance<SystemEvent.NewDevice>().single { it.deviceId == rogueId }
        assertFalse(seenByBob.needsApproval)

        // nothing is sent to the new device until alice approves it
        val id = t.alice.send(t.ab, "private")
        assertEquals(FailureReason.IDENTITY_CHANGED, t.alice.store.getMessage(id)!!.failureReason)
        assertFalse(server.messages.containsKey(id), "the copy for the own devices must not reach the injected device")
        val pending = t.alice.engine.pendingIdentityChanges().single()
        assertEquals(rogueId, pending.deviceId)
        assertTrue(t.alice.engine.acceptIdentity(t.alice.userId(), rogueId, pending.newIdentityKey!!))
        t.alice.engine.processOutbox()
        assertTrue(rogueId in serverEnvelopeDevices(id))
    }

    @Test
    fun `logging in a second phone on the account first blocks the old phone, not the new one`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        alice.send(conv, "x"); bob.engine.sync()
        val alice2 = client("alice")
        alice2.engine.login("https://fake.example", "alice", "password-alice")
        alice2.engine.refreshDirectory()
        // the new phone saw the old one as part of its baseline: it can send at once
        assertTrue(alice2.engine.pendingIdentityChanges().isEmpty())
        // the old phone has to approve the new one
        alice.engine.sync()
        val pending = alice.engine.pendingIdentityChanges().single()
        assertEquals(alice2.deviceIdOnServer(), pending.deviceId)
        val id = alice.send(conv, "from the old phone")
        assertEquals(MessageStatus.FAILED, alice.store.getMessage(id)!!.status)
        assertTrue(alice.engine.acceptIdentity(alice.userId(), pending.deviceId, pending.newIdentityKey!!))
        alice.engine.processOutbox()
        assertEquals(MessageStatus.SENT, alice.store.getMessage(id)!!.status)
        alice2.engine.sync()
        assertEquals("from the old phone", alice2.store.getMessage(id)!!.text)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F1 e: consistency of sender ids
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `a sender device that is not listed for the claimed user is not decrypted`() = runBlocking<Unit> {
        val t = trio()
        // carol writes to alice; the server claims the message comes from bob (in carol's conversation)
        val real = t.carol.send(t.ac, "from carol")
        val genuine = server.queues.getValue(t.alice.deviceIdOnServer()).last { it.type == EventType.MESSAGE }
        val env = server.messages.getValue(real).envelopes.getValue(t.alice.deviceIdOnServer())
        val fakeId = UUID.randomUUID().toString()
        // drop the genuine event, deliver a mislabelled copy first
        server.queues.getValue(t.alice.deviceIdOnServer()).remove(genuine)
        server.injectMessage(
            t.alice.deviceIdOnServer(),
            forgedMessage(fakeId, t.ac, t.bob.userId(), t.carol.deviceIdOnServer(), se.familychat.core.model.Envelope("x", env.type, env.body)),
        )
        t.alice.engine.sync()
        val m = t.alice.store.getMessage(fakeId)!!
        assertEquals(DecryptError.UNKNOWN_SENDER_DEVICE, m.decryptError)
        assertNull(m.text)
        // the ciphertext was not consumed: the genuine message still decrypts
        server.injectEvent(t.alice.deviceIdOnServer(), genuine.type, genuine.conversationId, genuine.data!!)
        t.alice.engine.sync()
        assertEquals("from carol", t.alice.store.getMessage(real)!!.text)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F2: member list changes
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `members added or removed behind the clients back are announced`() = runBlocking<Unit> {
        val t = trio()
        val dave = client("dave"); dave.signUp()
        val group = t.alice.engine.createGroup("family", listOf(t.bob.userId()))
        t.bob.engine.sync()
        assertTrue(t.alice.events(group.id).none { it is SystemEvent.MemberAdded }, "creating a conversation is the baseline")
        assertTrue(t.bob.events(group.id).none { it is SystemEvent.MemberAdded })

        // the server adds dave without anybody asking, and pushes the new member list
        server.conversations.getValue(group.id).members[dave.userId()] = FakeServer.MutableMember(dave.userId(), "member")
        t.alice.engine.refreshDirectory()
        t.bob.engine.refreshDirectory()
        for (c in listOf(t.alice, t.bob)) {
            assertEquals(listOf(dave.userId()), c.events(group.id).filterIsInstance<SystemEvent.MemberAdded>().map { it.userId })
        }
        // refreshing again changes nothing
        t.alice.engine.refreshDirectory()
        assertEquals(1, t.alice.events(group.id).count { it is SystemEvent.MemberAdded })

        server.conversations.getValue(group.id).members.remove(t.bob.userId())
        t.alice.engine.refreshDirectory()
        assertEquals(listOf(t.bob.userId()), t.alice.events(group.id).filterIsInstance<SystemEvent.MemberRemoved>().map { it.userId })
    }

    @Test
    fun `member changes arriving as conversation_updated events are announced`() = runBlocking<Unit> {
        val t = trio()
        val group = t.alice.engine.createGroup("family", listOf(t.bob.userId()))
        t.bob.engine.sync()
        t.alice.engine.addMembers(group.id, listOf(t.carol.userId()))
        t.bob.engine.sync() // conversation_updated
        assertEquals(listOf(t.carol.userId()), t.bob.events(group.id).filterIsInstance<SystemEvent.MemberAdded>().map { it.userId })
        assertEquals(listOf(t.carol.userId()), t.alice.events(group.id).filterIsInstance<SystemEvent.MemberAdded>().map { it.userId })
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F3: content bound to its context
    // ---------------------------------------------------------------------------------------------------------------

    private fun rewriteQueued(deviceId: String, messageId: String, change: (se.familychat.core.model.Message) -> se.familychat.core.model.Message) {
        server.queues.getValue(deviceId).replaceAll { e ->
            if (e.type == EventType.MESSAGE && (e.data as JsonObject)["id"] == JsonPrimitive(messageId)) {
                val m = se.familychat.core.model.FamilyChatJson.decodeFromJsonElement(se.familychat.core.model.Message.serializer(), e.data!!)
                val n = change(m)
                e.copy(
                    conversationId = n.conversationId,
                    data = se.familychat.core.model.FamilyChatJson.encodeToJsonElement(se.familychat.core.model.Message.serializer(), n),
                )
            } else e
        }
    }

    @Test
    fun `a message moved to another conversation is rejected and stored nowhere`() = runBlocking<Unit> {
        val t = trio()
        val group = t.alice.engine.createGroup("g", listOf(t.bob.userId(), t.carol.userId()))
        t.bob.engine.sync(); t.carol.engine.sync()
        // bob is a member of both conversations, so only the binding inside the ciphertext can tell the difference
        val id = t.bob.send(t.ab, "meant for the direct chat")
        rewriteQueued(t.alice.deviceIdOnServer(), id) { it.copy(conversationId = group.id) }
        t.alice.engine.sync()
        assertNull(t.alice.store.getMessage(id), "stored under the conversation the server claimed")
        assertTrue(t.alice.messages(group.id).none { !it.isSystem })
        assertTrue(t.alice.messages(t.ab).none { !it.isSystem })
    }

    @Test
    fun `a message with a relabelled id is rejected`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        val first = bob.send(conv, "first"); alice.engine.sync()
        val id = bob.send(conv, "second")
        // the server presents the second message under the id of the first (to replace it) and under a fresh id
        val fresh = UUID.randomUUID().toString()
        rewriteQueued(alice.deviceIdOnServer(), id) { it.copy(id = fresh) }
        alice.engine.sync()
        assertNull(alice.store.getMessage(fresh))
        assertNull(alice.store.getMessage(id))
        assertEquals("first", alice.store.getMessage(first)!!.text)
    }

    @Test
    fun `a message from a sender who is not a member of the conversation is rejected`() = runBlocking<Unit> {
        val t = trio()
        // carol is not in alice's conversation with bob; the server delivers her message there
        val id = t.carol.send(t.ac, "carol's words")
        rewriteQueued(t.alice.deviceIdOnServer(), id) { it.copy(conversationId = t.ab) }
        t.alice.engine.sync()
        assertNull(t.alice.store.getMessage(id))
    }

    @Test
    fun `an edit that does not name the target inside the ciphertext is ignored`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        val a = bob.send(conv, "text a")
        val b = bob.send(conv, "text b")
        alice.engine.sync()
        bob.engine.editMessage(a, "edited a"); bob.engine.processOutbox()
        val editMsg = server.messages.values.last { it.kind == MessageKind.EDIT }.msg.id
        rewriteQueued(alice.deviceIdOnServer(), editMsg) { it.copy(targetMessageId = b) } // server aims it at b
        alice.engine.sync()
        assertEquals("text b", alice.store.getMessage(b)!!.text)
        assertEquals("text a", alice.store.getMessage(a)!!.text)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F4: deletes
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `an encrypted delete reaches every device and survives a lost response and a 409`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        val id = bob.send(conv, "oops"); alice.engine.sync()
        assertEquals("oops", alice.store.getMessage(id)!!.text)
        val rogueId = server.addDevice(alice.userId(), RogueDevice(alice.userId()).upload) // forces a 409 on the delete
        server.loseSendResponses = 1
        bob.engine.deleteMessage(id)
        bob.engine.processOutbox() // stored, response lost
        bob.engine.processOutbox() // retried with the same id: duplicate
        val del = server.messages.values.single { it.kind == MessageKind.DELETE }
        assertEquals(setOf(alice.deviceIdOnServer(), rogueId), del.envelopes.keys)
        assertEquals(1, server.messages.values.count { it.kind == MessageKind.DELETE })
        assertTrue(bob.store.getMessage(id)!!.deleted)
        alice.engine.sync()
        val got = alice.store.getMessage(id)!!
        assertTrue(got.deleted)
        assertNull(got.text)
    }

    @Test
    fun `a delete without ciphertext from the server does not wipe anything`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        val id = bob.send(conv, "keep me"); alice.engine.sync()
        server.injectMessage(
            alice.deviceIdOnServer(),
            forgedMessage(UUID.randomUUID().toString(), conv, bob.userId(), bob.deviceIdOnServer(), null, MessageKind.DELETE, id),
        )
        alice.engine.sync()
        val m = alice.store.getMessage(id)!!
        assertFalse(m.deleted)
        assertEquals("keep me", m.text)
    }

    @Test
    fun `a genuine delete re-aimed at another message is ignored`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        val a = bob.send(conv, "a"); val b = bob.send(conv, "b"); alice.engine.sync()
        bob.engine.deleteMessage(a); bob.engine.processOutbox()
        val del = server.messages.values.single { it.kind == MessageKind.DELETE }.msg.id
        rewriteQueued(alice.deviceIdOnServer(), del) { it.copy(targetMessageId = b) }
        alice.engine.sync()
        assertFalse(alice.store.getMessage(b)!!.deleted)
        assertEquals("b", alice.store.getMessage(b)!!.text)
        assertFalse(alice.store.getMessage(a)!!.deleted, "the re-aimed delete must be dropped, not applied to its original target")
    }

    @Test
    fun `a delete cannot be applied by somebody who did not write the target`() = runBlocking<Unit> {
        val t = trio()
        val group = t.alice.engine.createGroup("g", listOf(t.bob.userId(), t.carol.userId()))
        t.bob.engine.sync(); t.carol.engine.sync()
        val mine = t.bob.send(group.id, "bob's"); val hers = t.carol.send(group.id, "carol's")
        t.alice.engine.sync()
        t.carol.engine.deleteMessage(hers); t.carol.engine.processOutbox()
        val del = server.messages.values.single { it.kind == MessageKind.DELETE }.msg.id
        // carol's genuine delete, re-aimed at bob's message
        rewriteQueued(t.alice.deviceIdOnServer(), del) { it.copy(targetMessageId = mine) }
        t.alice.engine.sync()
        assertEquals("bob's", t.alice.store.getMessage(mine)!!.text)
        assertFalse(t.alice.store.getMessage(mine)!!.deleted)
        assertFalse(t.alice.store.getMessage(hers)!!.deleted, "the re-aimed delete must be dropped, not applied to its original target")
    }

    @Test
    fun `a forged edit without ciphertext changes nothing`() = runBlocking<Unit> {
        val (alice, bob, conv) = pair()
        val id = bob.send(conv, "original"); alice.engine.sync()
        server.injectMessage(
            alice.deviceIdOnServer(),
            forgedMessage(UUID.randomUUID().toString(), conv, bob.userId(), bob.deviceIdOnServer(), null, MessageKind.EDIT, id),
        )
        alice.engine.sync()
        assertEquals("original", alice.store.getMessage(id)!!.text)
    }

}
