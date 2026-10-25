package se.swahnchat.core.client

import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import se.swahnchat.core.crypto.Recipient
import se.swahnchat.core.model.Content
import se.swahnchat.core.model.ContentType
import se.swahnchat.core.model.Envelope
import se.swahnchat.core.model.MessageKind
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HEART = "❤️"
private const val THUMBS = "👍"
private const val LAUGH = "😂"

/** Shared set-up of the feature tests: two or three phones on one [FakeServer]. */
abstract class FeatureTestBase {
    protected val server = FakeServer()
    protected val scope = newScope()
    protected val clock = AtomicLong(1_700_000_000_000L)

    @AfterTest fun tearDown() = scope.cancel()

    protected fun client(name: String, threshold: Long = 8L * 1024 * 1024, chunk: Int = 8 * 1024 * 1024) =
        TestClient(server, name, scope, false, clock = { clock.get() }, resumableThreshold = threshold, chunkBytes = chunk)

    protected class Pair3(val alice: TestClient, val bob: TestClient, val carol: TestClient, val ab: String, val group: String)

    /** alice+bob direct, plus a group of all three, everything synced. */
    protected suspend fun trio(threshold: Long = 8L * 1024 * 1024, chunk: Int = 8 * 1024 * 1024): Pair3 {
        val alice = client("alice", threshold, chunk); val bob = client("bob", threshold, chunk); val carol = client("carol", threshold, chunk)
        alice.signUp(); bob.signUp(); carol.signUp()
        alice.engine.refreshDirectory(); bob.engine.refreshDirectory(); carol.engine.refreshDirectory()
        val ab = alice.engine.createDirect(bob.userId()).id
        val g = alice.engine.createGroup("family", listOf(bob.userId(), carol.userId())).id
        bob.engine.sync(); carol.engine.sync()
        return Pair3(alice, bob, carol, ab, g)
    }

    protected suspend fun TestClient.send(conv: String, text: String): String {
        val id = engine.sendText(conv, text)
        engine.processOutbox()
        return id
    }

    protected suspend fun TestClient.notices(conv: String) = messages(conv).mapNotNull { it.systemEvent }
    protected suspend fun TestClient.visible(conv: String) = messages(conv).filter { !it.isSystem }

    protected fun serverStored(id: String) = server.messages.getValue(id)
}

class ForwardCompatibilityTest : FeatureTestBase() {
    /** A device of bob's account that the test controls, listed and announced, so alice accepts what it encrypts. */
    private class Rogue(val id: String, val device: RogueDevice)

    private suspend fun rogueOf(bob: TestClient, alice: TestClient): Rogue {
        val device = RogueDevice(bob.userId())
        val id = server.addDevice(bob.userId(), device.upload, announce = true)
        alice.engine.sync()
        return Rogue(id, device)
    }

    private suspend fun Rogue.deliver(
        to: TestClient, from: TestClient, conv: String, content: Content, kind: String = MessageKind.MESSAGE,
        target: String? = null, conversationLabel: String = conv, withEnvelope: Boolean = true,
    ): String {
        val id = content.messageId ?: UUID.randomUUID().toString()
        val env: Envelope? = if (withEnvelope) {
            device.encryptFor(Recipient(to.userId(), to.deviceIdOnServer()), server.bundleFor(this.id, to), content)
        } else {
            null
        }
        server.injectMessage(to.deviceIdOnServer(), forgedMessage(id, conversationLabel, from.userId(), this.id, env, kind, target))
        return id
    }

    private fun unknown(conv: String, id: String = UUID.randomUUID().toString(), type: String = "poll", target: String? = null) =
        Content(type = type, text = "ignored", conversationId = conv, messageId = id, targetMessageId = target)

    @Test
    fun `well-formed content of an unknown type shows no bubble and one update notice, and the ratchet moves on`() = runBlocking<Unit> {
        val t = trio()
        val rogue = rogueOf(t.bob, t.alice)
        val first = rogue.deliver(t.alice, t.bob, t.ab, unknown(t.ab))
        t.alice.engine.sync()
        assertNull(t.alice.store.getMessage(first), "nothing is stored for it")
        assertTrue(t.alice.visible(t.ab).isEmpty())
        assertTrue(t.alice.visible(t.ab).none { it.decryptError != null })
        assertEquals(1, t.alice.notices(t.ab).count { it == SystemEvent.UpdateRequired })
        assertEquals(0, t.alice.store.unreadCount(t.ab))

        // a second one from the same device: still exactly one notice, still no bubble
        rogue.deliver(t.alice, t.bob, t.ab, unknown(t.ab, type = "pin"))
        // and a normal text after them still decrypts: the ratchet state is right
        val textId = UUID.randomUUID().toString()
        rogue.deliver(t.alice, t.bob, t.ab, Content(text = "hello", conversationId = t.ab, messageId = textId))
        t.alice.engine.sync()
        assertEquals("hello", t.alice.store.getMessage(textId)!!.text)
        assertEquals(1, t.alice.notices(t.ab).count { it == SystemEvent.UpdateRequired })
        assertEquals(listOf("hello"), t.alice.visible(t.ab).map { it.text })
    }

    @Test
    fun `the notice is per conversation`() = runBlocking<Unit> {
        val t = trio()
        val rogue = rogueOf(t.bob, t.alice)
        rogue.deliver(t.alice, t.bob, t.ab, unknown(t.ab))
        rogue.deliver(t.alice, t.bob, t.group, unknown(t.group))
        t.alice.engine.sync()
        assertEquals(1, t.alice.notices(t.ab).count { it == SystemEvent.UpdateRequired })
        assertEquals(1, t.alice.notices(t.group).count { it == SystemEvent.UpdateRequired })
    }

    @Test
    fun `a replay of consumed unknown content does not turn into could not decrypt`() = runBlocking<Unit> {
        val t = trio()
        val rogue = rogueOf(t.bob, t.alice)
        val id = rogue.deliver(t.alice, t.bob, t.ab, unknown(t.ab))
        t.alice.engine.sync()
        // the same event again (a crash before the cursor was saved, a server that repeats itself)
        val again = server.queues.getValue(t.alice.deviceIdOnServer()).last { it.type == se.swahnchat.core.model.EventType.MESSAGE }
        server.injectEvent(t.alice.deviceIdOnServer(), again.type, again.conversationId, again.data!!)
        t.alice.engine.sync()
        assertNull(t.alice.store.getMessage(id))
        assertTrue(t.alice.messages(t.ab).none { it.decryptError != null })
        assertEquals(1, t.alice.notices(t.ab).count { it == SystemEvent.UpdateRequired })
    }

    @Test
    fun `unknown content that is not bound to its event is dropped without a notice`() = runBlocking<Unit> {
        val t = trio()
        val rogue = rogueOf(t.bob, t.alice)
        // the ciphertext names the group, the event claims the direct chat
        rogue.deliver(t.alice, t.bob, t.ab, unknown(t.group), conversationLabel = t.ab)
        // the ciphertext names another message id than the event
        val id = UUID.randomUUID().toString()
        val env = rogue.device.encryptFor(
            Recipient(t.alice.userId(), t.alice.deviceIdOnServer()), server.bundleFor(rogue.id, t.alice), unknown(t.ab, id = "someone-else"),
        )
        server.injectMessage(t.alice.deviceIdOnServer(), forgedMessage(id, t.ab, t.bob.userId(), rogue.id, env))
        t.alice.engine.sync()
        assertNull(t.alice.store.getMessage(id))
        assertTrue(t.alice.messages(t.ab).isEmpty() || t.alice.messages(t.ab).all { it.isSystem && it.systemEvent != SystemEvent.UpdateRequired })
        assertTrue(t.alice.notices(t.group).none { it == SystemEvent.UpdateRequired })
    }

    @Test
    fun `unknown content from somebody who is not a member is dropped`() = runBlocking<Unit> {
        val t = trio()
        val dave = client("dave"); dave.signUp()
        val rogue = rogueOf(dave, t.alice)
        // dave is not in the direct chat of alice and bob
        rogue.deliver(t.alice, dave, t.ab, unknown(t.ab))
        t.alice.engine.sync()
        assertTrue(t.alice.notices(t.ab).none { it == SystemEvent.UpdateRequired })
    }

    @Test
    fun `malformed content still shows could not decrypt`() = runBlocking<Unit> {
        val t = trio()
        val rogue = rogueOf(t.bob, t.alice)
        val id = UUID.randomUUID().toString()
        rogue.deliver(t.alice, t.bob, t.ab, Content(v = 3, type = "poll", conversationId = t.ab, messageId = id))
        t.alice.engine.sync()
        assertEquals(DecryptError.CORRUPT, t.alice.store.getMessage(id)!!.decryptError)
    }

    @Test
    fun `a message kind from a newer server is consumed, shows nothing and notes the update once`() = runBlocking<Unit> {
        val t = trio()
        val rogue = rogueOf(t.bob, t.alice)
        val first = rogue.deliver(t.alice, t.bob, t.ab, unknown(t.ab, type = "poll"), kind = "poll")
        val second = rogue.deliver(t.alice, t.bob, t.ab, unknown(t.ab, type = "poll"), kind = "poll")
        val textId = UUID.randomUUID().toString()
        rogue.deliver(t.alice, t.bob, t.ab, Content(text = "after", conversationId = t.ab, messageId = textId))
        t.alice.engine.sync()
        assertNull(t.alice.store.getMessage(first)); assertNull(t.alice.store.getMessage(second))
        assertEquals("after", t.alice.store.getMessage(textId)!!.text, "the ratchet advanced past the unknown kind")
        assertEquals(1, t.alice.notices(t.ab).count { it == SystemEvent.UpdateRequired })
        assertTrue(t.alice.messages(t.ab).none { it.decryptError != null })
    }

    @Test
    fun `an unknown kind without ciphertext is skipped`() = runBlocking<Unit> {
        val t = trio()
        val rogue = rogueOf(t.bob, t.alice)
        val id = rogue.deliver(t.alice, t.bob, t.ab, unknown(t.ab), kind = "poll", withEnvelope = false)
        t.alice.engine.sync()
        assertNull(t.alice.store.getMessage(id))
        assertTrue(t.alice.notices(t.ab).none { it == SystemEvent.UpdateRequired })
    }

    @Test
    fun `a first message that is of an unknown type still establishes the session`() = runBlocking<Unit> {
        // The very first envelope from a new device is a prekey message; if it were dropped unread, the next one would fail.
        val t = trio()
        val rogue = rogueOf(t.bob, t.alice)
        rogue.deliver(t.alice, t.bob, t.ab, unknown(t.ab), kind = "poll")
        val textId = UUID.randomUUID().toString()
        rogue.deliver(t.alice, t.bob, t.ab, Content(text = "second", conversationId = t.ab, messageId = textId))
        t.alice.engine.sync()
        assertEquals("second", t.alice.store.getMessage(textId)!!.text)
    }
}

class ReactionTest : FeatureTestBase() {
    private suspend fun reactionsOn(c: TestClient, id: String) = c.engine.reactionsOf(id).associate { it.userId to it.emoji }

    @Test
    fun `a reaction reaches the other phone, replaces the previous one and toggles off`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.send(t.ab, "hi")
        t.bob.engine.sync()
        t.bob.engine.react(id, THUMBS)
        // shown at once on the reactor's phone, as unsent
        assertEquals(mapOf(t.bob.userId() to THUMBS), reactionsOn(t.bob, id))
        assertNull(t.bob.store.getReaction(id, t.bob.userId())!!.seq)
        t.bob.engine.processOutbox()
        assertNotNull(t.bob.store.getReaction(id, t.bob.userId())!!.seq)
        t.alice.engine.sync()
        assertEquals(mapOf(t.bob.userId() to THUMBS), reactionsOn(t.alice, id))

        t.bob.engine.react(id, HEART) // another emoji replaces it: one reaction per member
        t.bob.engine.processOutbox(); t.alice.engine.sync()
        assertEquals(mapOf(t.bob.userId() to HEART), reactionsOn(t.alice, id))

        t.bob.engine.react(id, HEART) // the same emoji again takes it back
        assertTrue(reactionsOn(t.bob, id).isEmpty())
        t.bob.engine.processOutbox(); t.alice.engine.sync()
        assertTrue(reactionsOn(t.alice, id).isEmpty())
        assertEquals(3, server.messages.values.count { it.kind == MessageKind.REACTION })
    }

    @Test
    fun `everybody in a group can react and the author sees each reactor`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.send(t.group, "party?"); t.bob.engine.sync(); t.carol.engine.sync()
        t.bob.engine.react(id, THUMBS); t.bob.engine.processOutbox()
        t.carol.engine.react(id, LAUGH); t.carol.engine.processOutbox()
        t.alice.engine.react(id, THUMBS); t.alice.engine.processOutbox() // even the author, on their own message
        for (c in listOf(t.alice, t.bob, t.carol)) c.engine.sync()
        val expected = mapOf(t.bob.userId() to THUMBS, t.carol.userId() to LAUGH, t.alice.userId() to THUMBS)
        for (c in listOf(t.alice, t.bob, t.carol)) assertEquals(expected, reactionsOn(c, id), c.name)
    }

    @Test
    fun `a reaction is not a message - no unread, no new last message`() = runBlocking<Unit> {
        val t = trio()
        val id = t.bob.send(t.ab, "mine"); t.alice.engine.sync()
        t.alice.engine.markRead(t.ab)
        val lastSeq = t.alice.store.getConversation(t.ab)!!.lastMessageSeq
        t.bob.engine.react(id, THUMBS)
        t.alice.engine.react(id, HEART); t.alice.engine.processOutbox(); t.bob.engine.processOutbox()
        t.bob.engine.sync(); t.alice.engine.sync()
        assertEquals(0, t.bob.store.unreadCount(t.ab))
        assertEquals(0, t.alice.store.unreadCount(t.ab))
        assertEquals(1, t.alice.visible(t.ab).size)
        assertEquals(lastSeq, t.alice.store.getConversation(t.ab)!!.lastMessageSeq)
        assertEquals(1, t.bob.visible(t.ab).size)
    }

    @Test
    fun `the latest reaction by server order wins whatever order they arrive in`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.send(t.ab, "x"); t.bob.engine.sync()
        t.bob.engine.react(id, THUMBS); t.bob.engine.processOutbox()
        t.bob.engine.react(id, HEART); t.bob.engine.processOutbox()
        // the server hands alice the newer one first
        val q = server.queues.getValue(t.alice.deviceIdOnServer())
        val events = q.filter { it.type == se.swahnchat.core.model.EventType.MESSAGE }.takeLast(2)
        q.removeAll(events.toSet()); q.addAll(events.reversed())
        t.alice.engine.sync()
        assertEquals(mapOf(t.bob.userId() to HEART), reactionsOn(t.alice, id))
    }

    @Test
    fun `the emoji must be a single emoji`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.send(t.ab, "x")
        for (bad in listOf("", "ab", "hi", "$THUMBS$THUMBS", " ")) assertFailsWith<IllegalArgumentException>(bad) { t.alice.engine.react(id, bad) }
        assertEquals(0, server.messages.values.count { it.kind == MessageKind.REACTION })
    }

    @Test
    fun `you cannot react to a deleted message, a notice, or one that was never sent`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.send(t.ab, "x")
        val pending = t.alice.engine.sendText(t.ab, "not yet")
        assertFailsWith<IllegalArgumentException> { t.alice.engine.react(pending, THUMBS) }
        t.alice.engine.deleteMessage(id); t.alice.engine.processOutbox()
        assertFailsWith<IllegalArgumentException> { t.alice.engine.react(id, THUMBS) }
        val notice = t.alice.messages(t.ab).firstOrNull { it.isSystem }
        if (notice != null) assertFailsWith<IllegalArgumentException> { t.alice.engine.react(notice.id, THUMBS) }
    }

    @Test
    fun `a reaction to a message that is deleted later disappears with it`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.send(t.ab, "x"); t.bob.engine.sync()
        t.bob.engine.react(id, THUMBS); t.bob.engine.processOutbox(); t.alice.engine.sync()
        assertEquals(1, reactionsOn(t.alice, id).size)
        t.alice.engine.deleteMessage(id); t.alice.engine.processOutbox(); t.bob.engine.sync()
        assertTrue(reactionsOn(t.alice, id).isEmpty())
        assertTrue(reactionsOn(t.bob, id).isEmpty())
    }

    @Test
    fun `a reaction is retried after a 409 and a lost response and reaches a device that appeared meanwhile`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.send(t.ab, "x"); t.bob.engine.sync()
        val bob2 = client("bob"); bob2.engine.login("https://fake.example", "bob", "password-bob"); bob2.engine.refreshDirectory()
        server.loseSendResponses = 1
        t.alice.engine.react(id, THUMBS)
        t.alice.engine.processOutbox() // stored, response lost
        t.alice.engine.processOutbox() // same id again: duplicate
        assertEquals(1, server.messages.values.count { it.kind == MessageKind.REACTION })
        val rec = server.messages.values.single { it.kind == MessageKind.REACTION }
        assertTrue(bob2.deviceIdOnServer() in rec.envelopes.keys, "full device set, including the new phone")
        assertTrue(t.bob.deviceIdOnServer() in rec.envelopes.keys)
        t.bob.engine.sync()
        assertEquals(mapOf(t.alice.userId() to THUMBS), reactionsOn(t.bob, id))
    }

    @Test
    fun `the server never sees the emoji`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.send(t.ab, "x")
        t.alice.engine.react(id, LAUGH); t.alice.engine.processOutbox()
        val rec = server.messages.values.single { it.kind == MessageKind.REACTION }
        assertEquals(id, rec.msg.targetMessageId)
        assertTrue(rec.envelopes.values.none { LAUGH in it.body })
    }

    // --- authentication ---------------------------------------------------------------------------------------------

    private suspend fun rogueReaction(
        t: Pair3, target: String, reactorOnServer: TestClient = t.bob, content: (id: String) -> Content,
    ): String {
        val device = RogueDevice(reactorOnServer.userId())
        val devId = server.addDevice(reactorOnServer.userId(), device.upload, announce = true)
        t.alice.engine.sync()
        val id = UUID.randomUUID().toString()
        val env = device.encryptFor(Recipient(t.alice.userId(), t.alice.deviceIdOnServer()), server.bundleFor(devId, t.alice), content(id))
        server.injectMessage(
            t.alice.deviceIdOnServer(),
            forgedMessage(id, t.ab, reactorOnServer.userId(), devId, env, MessageKind.REACTION, target),
        )
        return id
    }

    @Test
    fun `a genuine reaction is accepted from a member's own device`() = runBlocking<Unit> {
        val t = trio()
        val msg = t.alice.send(t.ab, "x")
        rogueReaction(t, msg) { Content(type = ContentType.REACTION, emoji = THUMBS, conversationId = t.ab, messageId = it, targetMessageId = msg) }
        t.alice.engine.sync()
        assertEquals(mapOf(t.bob.userId() to THUMBS), reactionsOn(t.alice, msg))
    }

    @Test
    fun `a reaction re-aimed at another message is dropped`() = runBlocking<Unit> {
        val t = trio()
        val a = t.alice.send(t.ab, "a"); val b = t.alice.send(t.ab, "b")
        rogueReaction(t, b) { Content(type = ContentType.REACTION, emoji = THUMBS, conversationId = t.ab, messageId = it, targetMessageId = a) }
        t.alice.engine.sync()
        assertTrue(reactionsOn(t.alice, a).isEmpty())
        assertTrue(reactionsOn(t.alice, b).isEmpty())
    }

    @Test
    fun `a reaction moved to another conversation is dropped`() = runBlocking<Unit> {
        val t = trio()
        val inGroup = t.alice.send(t.group, "in the group")
        // the content names the direct chat and a target in the group
        rogueReaction(t, inGroup) { Content(type = ContentType.REACTION, emoji = THUMBS, conversationId = t.ab, messageId = it, targetMessageId = inGroup) }
        t.alice.engine.sync()
        assertTrue(reactionsOn(t.alice, inGroup).isEmpty(), "the target is not in the conversation the reaction was for")
    }

    @Test
    fun `a reaction from somebody who is not a member is dropped`() = runBlocking<Unit> {
        val t = trio()
        val dave = client("dave"); dave.signUp()
        val msg = t.alice.send(t.ab, "private")
        rogueReaction(t, msg, reactorOnServer = dave) {
            Content(type = ContentType.REACTION, emoji = THUMBS, conversationId = t.ab, messageId = it, targetMessageId = msg)
        }
        t.alice.engine.sync()
        assertTrue(reactionsOn(t.alice, msg).isEmpty())
    }

    @Test
    fun `a reaction without ciphertext, with a bad emoji, or to a missing message changes nothing`() = runBlocking<Unit> {
        val t = trio()
        val msg = t.alice.send(t.ab, "x")
        server.injectMessage(
            t.alice.deviceIdOnServer(),
            forgedMessage(UUID.randomUUID().toString(), t.ab, t.bob.userId(), t.bob.deviceIdOnServer(), null, MessageKind.REACTION, msg),
        )
        rogueReaction(t, msg) { Content(type = ContentType.REACTION, emoji = "not an emoji", conversationId = t.ab, messageId = it, targetMessageId = msg) }
        rogueReaction(t, "no-such-message") {
            Content(type = ContentType.REACTION, emoji = THUMBS, conversationId = t.ab, messageId = it, targetMessageId = "no-such-message")
        }
        t.alice.engine.sync()
        assertTrue(reactionsOn(t.alice, msg).isEmpty())
        assertTrue(t.alice.visible(t.ab).none { it.decryptError != null }, "no bubble for a failed reaction")
        assertEquals(1, t.alice.visible(t.ab).size)
    }

    @Test
    fun `the reactor is the event's sender, not a field the content could name`() = runBlocking<Unit> {
        val t = trio()
        val msg = t.alice.send(t.group, "x"); t.bob.engine.sync()
        // bob's device sends a reaction while the server labels it as carol's: the device is not carol's, so it is not decrypted
        val device = RogueDevice(t.bob.userId())
        val devId = server.addDevice(t.bob.userId(), device.upload, announce = true)
        t.alice.engine.sync()
        val id = UUID.randomUUID().toString()
        val env = device.encryptFor(
            Recipient(t.alice.userId(), t.alice.deviceIdOnServer()), server.bundleFor(devId, t.alice),
            Content(type = ContentType.REACTION, emoji = THUMBS, conversationId = t.group, messageId = id, targetMessageId = msg),
        )
        server.injectMessage(t.alice.deviceIdOnServer(), forgedMessage(id, t.group, t.carol.userId(), devId, env, MessageKind.REACTION, msg))
        t.alice.engine.sync()
        assertTrue(reactionsOn(t.alice, msg).isEmpty())
    }
}

class DisappearingMessagesTest : FeatureTestBase() {
    private suspend fun TestClient.timer(conv: String) = engine.disappearingTimer(conv)

    @Test
    fun `a timer change travels as encrypted content, patches the server and shows a notice on both phones`() = runBlocking<Unit> {
        val t = trio()
        t.alice.engine.setDisappearingTimer(t.ab, 86_400)
        assertEquals(86_400L, t.alice.timer(t.ab))
        assertTrue(t.alice.notices(t.ab).any { it == SystemEvent.TimerChanged(t.alice.userId(), 86_400) })
        assertEquals(0L, t.bob.timer(t.ab))
        t.alice.engine.processOutbox()
        assertTrue("patch:${t.ab}:86400" in server.log)
        assertEquals(86_400L, server.conversations.getValue(t.ab).disappearingSeconds)
        t.bob.engine.sync()
        assertEquals(86_400L, t.bob.timer(t.ab))
        assertEquals(listOf(SystemEvent.TimerChanged(t.alice.userId(), 86_400)), t.bob.notices(t.ab))
        assertTrue(t.bob.visible(t.ab).isEmpty(), "no bubble")
        assertEquals(0, t.bob.store.unreadCount(t.ab))
        // the server sees an ordinary message; the new value is inside the ciphertext only
        val rec = server.messages.values.single()
        assertEquals(MessageKind.MESSAGE, rec.kind)
        assertTrue(rec.envelopes.values.none { "86400" in it.body })
        // the sender's own pending change got its place in the server's order
        assertNotNull(t.alice.store.getTimer(t.ab)!!.seq)

        t.bob.engine.setDisappearingTimer(t.ab, 0)
        t.bob.engine.processOutbox(); t.alice.engine.sync()
        assertEquals(0L, t.alice.timer(t.ab))
        assertTrue(t.alice.notices(t.ab).any { it == SystemEvent.TimerChanged(t.bob.userId(), 0) })
        assertTrue("patch:${t.ab}:null" in server.log)
    }

    @Test
    fun `only the allowed values can be set`() = runBlocking<Unit> {
        val t = trio()
        for (bad in listOf(-1L, 1L, 299L, 31_536_001L)) assertFailsWith<IllegalArgumentException>("$bad") { t.alice.engine.setDisappearingTimer(t.ab, bad) }
        assertEquals(0, server.messages.size)
    }

    @Test
    fun `in a group only an administrator can set the timer, like the server's patch`() = runBlocking<Unit> {
        val t = trio()
        assertFailsWith<IllegalArgumentException> { t.bob.engine.setDisappearingTimer(t.group, 3_600) }
        assertEquals(0L, t.bob.timer(t.group))
        assertEquals(0, server.messages.size)
        t.alice.engine.setDisappearingTimer(t.group, 3_600); t.alice.engine.processOutbox()
        t.bob.engine.sync(); t.carol.engine.sync()
        assertEquals(3_600L, t.bob.timer(t.group)); assertEquals(3_600L, t.carol.timer(t.group))
        assertTrue(t.bob.notices(t.group).any { it == SystemEvent.TimerChanged(t.alice.userId(), 3_600) })
        // in a direct chat either person can
        t.bob.engine.setDisappearingTimer(t.ab, 300); t.bob.engine.processOutbox(); t.alice.engine.sync()
        assertEquals(300L, t.alice.timer(t.ab))
    }

    @Test
    fun `a failing server patch does not undo the change and is tried again`() = runBlocking<Unit> {
        val t = trio()
        server.patchFails = 1
        t.alice.engine.setDisappearingTimer(t.ab, 3_600)
        t.alice.engine.processOutbox()
        assertEquals(3_600L, t.alice.timer(t.ab))
        t.bob.engine.sync()
        assertEquals(3_600L, t.bob.timer(t.ab), "the encrypted change went out")
        assertNull(server.conversations.getValue(t.ab).disappearingSeconds)
        t.alice.engine.processOutbox() // next pass repeats the patch
        assertEquals(3_600L, server.conversations.getValue(t.ab).disappearingSeconds)
    }

    @Test
    fun `messages carry expires_in from the conversation timer at send time`() = runBlocking<Unit> {
        val t = trio()
        val before = t.alice.send(t.ab, "before")
        t.alice.engine.setDisappearingTimer(t.ab, 3_600); t.alice.engine.processOutbox(); t.bob.engine.sync()
        val after = t.bob.send(t.ab, "after the timer"); t.alice.engine.sync()
        assertNull(t.alice.store.getMessage(before)!!.expiresIn)
        assertEquals(3_600L, t.bob.store.getMessage(after)!!.expiresIn)
        assertEquals(3_600L, t.alice.store.getMessage(after)!!.expiresIn)
        // changing the timer later does not rewrite what was sent
        t.alice.engine.setDisappearingTimer(t.ab, 300); t.alice.engine.processOutbox(); t.bob.engine.sync()
        assertEquals(3_600L, t.bob.store.getMessage(after)!!.expiresIn)
        val last = t.alice.send(t.ab, "now five minutes")
        assertEquals(300L, t.alice.store.getMessage(last)!!.expiresIn)
    }

    @Test
    fun `a message is removed expires_in after it was displayed, with its attachment file, and stays removed`() = runBlocking<Unit> {
        val t = trio()
        t.alice.engine.setDisappearingTimer(t.ab, 3_600); t.alice.engine.processOutbox(); t.bob.engine.sync()
        val file = File(t.alice.dir, "pic.bin").also { it.writeBytes(Random(1).nextBytes(2_000)) }
        val id = t.alice.engine.sendAttachments(t.ab, listOf(OutgoingAttachment(file, "application/octet-stream", "file", "pic.bin")), caption = "secret")
        t.alice.engine.processOutbox(); t.bob.engine.sync()
        val att = t.bob.store.getMessage(id)!!.attachments.single()
        val downloaded = t.bob.engine.downloadAttachment(id, att.id)
        assertTrue(downloaded.isFile)

        // not displayed yet: nothing runs, however long it takes
        clock.addAndGet(10_000_000)
        assertTrue(t.bob.engine.purgeExpired().isEmpty())
        assertNull(t.bob.store.getMessage(id)!!.expireAt)

        val due = t.bob.engine.markDisplayed(listOf(id))
        assertEquals(clock.get() + 3_600_000, due)
        assertEquals(due, t.bob.store.getMessage(id)!!.expireAt)
        assertEquals(due, t.bob.engine.nextExpiry())
        // displaying it again does not restart the clock
        clock.addAndGet(1_000)
        assertEquals(null, t.bob.engine.markDisplayed(listOf(id)))
        assertEquals(due, t.bob.store.getMessage(id)!!.expireAt)

        clock.set(due!! - 1)
        assertTrue(t.bob.engine.purgeExpired().isEmpty())
        assertNotNull(t.bob.store.getMessage(id))
        clock.set(due)
        val removed = t.bob.engine.purgeExpired()
        assertEquals(listOf(id), removed.map { it.id })
        assertNull(t.bob.store.getMessage(id))
        assertFalse(downloaded.exists(), "the decrypted attachment file is deleted")
        assertNull(t.bob.engine.nextExpiry())
        // the server's copy, a replay or the sender's phone cannot bring it back
        val ev = server.queues.getValue(t.bob.deviceIdOnServer()).last { it.type == se.swahnchat.core.model.EventType.MESSAGE && "$id" in it.data.toString() }
        server.injectEvent(t.bob.deviceIdOnServer(), ev.type, ev.conversationId, ev.data!!)
        t.bob.engine.sync()
        assertNull(t.bob.store.getMessage(id))
    }

    @Test
    fun `the sender's own copy expires too, and a message still waiting to be sent is kept`() = runBlocking<Unit> {
        val t = trio()
        t.alice.engine.setDisappearingTimer(t.ab, 300); t.alice.engine.processOutbox()
        val sent = t.alice.send(t.ab, "sent")
        server.failSends = 5
        val waiting = t.alice.engine.sendText(t.ab, "waiting")
        t.alice.engine.markDisplayed(listOf(sent, waiting))
        clock.addAndGet(400_000)
        assertEquals(listOf(sent), t.alice.engine.purgeExpired().map { it.id })
        assertNotNull(t.alice.store.getMessage(waiting))
    }

    @Test
    fun `an expired message disappears from search and reactions go with it`() = runBlocking<Unit> {
        val t = trio()
        t.alice.engine.setDisappearingTimer(t.ab, 300); t.alice.engine.processOutbox(); t.bob.engine.sync()
        val id = t.alice.send(t.ab, "hello"); t.bob.engine.sync()
        t.bob.engine.react(id, THUMBS); t.bob.engine.processOutbox()
        t.bob.engine.markDisplayed(listOf(id))
        clock.addAndGet(301_000)
        t.bob.engine.purgeExpired()
        assertTrue(t.bob.engine.reactionsOf(id).isEmpty())
        assertNull(t.bob.store.getReaction(id, t.bob.userId())?.takeIf { it.emoji != null })
    }

    // --- tamper -----------------------------------------------------------------------------------------------------

    @Test
    fun `the server cannot make a phone delete anything - no encrypted timer, nothing is removed`() = runBlocking<Unit> {
        val t = trio()
        val ids = (1..3).map { t.alice.send(t.ab, "m$it") }
        t.bob.engine.sync()
        t.bob.engine.markDisplayed(ids)
        // the (hacked) server sets a 5 minute timer and announces it; the stored messages even have expires_at in the past
        server.serverSetsTimer(t.ab, 300)
        val late = t.alice.send(t.ab, "after the server timer")
        assertEquals(1000L + 300_000L, server.expiresAt[late])
        t.bob.engine.sync(); t.alice.engine.sync()
        assertEquals(300L, t.bob.store.getConversation(t.ab)!!.disappearingSeconds, "the server's value is kept as a fact about the server")
        clock.addAndGet(100_000_000)
        for (c in listOf(t.alice, t.bob)) {
            assertEquals(0L, c.timer(t.ab))
            assertTrue(c.engine.purgeExpired().isEmpty())
            assertNull(c.engine.nextExpiry())
            assertEquals(4, c.visible(t.ab).size, c.name)
            assertTrue(c.visible(t.ab).all { it.expiresIn == null && it.expireAt == null })
        }
        // and new messages are sent without a timer
        assertNull(t.bob.store.getMessage(t.bob.send(t.ab, "still plain"))!!.expiresIn)
        assertNull(t.bob.engine.markDisplayed(t.bob.visible(t.ab).map { it.id }))
    }

    @Test
    fun `a timer message from somebody outside the conversation, or not bound to it, is ignored`() = runBlocking<Unit> {
        val t = trio()
        val dave = client("dave"); dave.signUp()
        val outsider = RogueDevice(dave.userId())
        val outsiderId = server.addDevice(dave.userId(), outsider.upload, announce = true)
        val member = RogueDevice(t.bob.userId())
        val memberId = server.addDevice(t.bob.userId(), member.upload, announce = true)
        t.alice.engine.sync()
        fun timer(conv: String, id: String, seconds: Long = 300) = Content(type = ContentType.TIMER, seconds = seconds, conversationId = conv, messageId = id)
        suspend fun inject(dev: RogueDevice, devId: String, user: TestClient, content: Content, eventId: String = content.messageId!!) {
            val env = dev.encryptFor(Recipient(t.alice.userId(), t.alice.deviceIdOnServer()), server.bundleFor(devId, t.alice), content)
            server.injectMessage(t.alice.deviceIdOnServer(), forgedMessage(eventId, t.ab, user.userId(), devId, env))
        }
        inject(outsider, outsiderId, dave, timer(t.ab, "o1")) // not a member
        inject(member, memberId, t.bob, timer(t.group, "o2")) // bound to the group, delivered as the direct chat
        inject(member, memberId, t.bob, timer(t.ab, "someone-else"), eventId = "o3") // the event has another message id
        t.alice.engine.sync()
        assertEquals(0L, t.alice.timer(t.ab))
        assertEquals(0L, t.alice.timer(t.group))
        assertTrue(t.alice.notices(t.ab).none { it is SystemEvent.TimerChanged })
        // a member's genuine one is applied
        inject(member, memberId, t.bob, timer(t.ab, "o4", 600))
        t.alice.engine.sync()
        assertEquals(600L, t.alice.timer(t.ab))
    }

    @Test
    fun `an older timer message never overrides a newer one`() = runBlocking<Unit> {
        val t = trio()
        t.alice.engine.setDisappearingTimer(t.ab, 300); t.alice.engine.processOutbox()
        t.alice.engine.setDisappearingTimer(t.ab, 3_600); t.alice.engine.processOutbox()
        val q = server.queues.getValue(t.bob.deviceIdOnServer())
        val events = q.filter { it.type == se.swahnchat.core.model.EventType.MESSAGE }.takeLast(2)
        q.removeAll(events.toSet()); q.addAll(events.reversed()) // the server delivers the newer one first
        t.bob.engine.sync()
        assertEquals(3_600L, t.bob.timer(t.ab))
    }

    @Test
    fun `expires_in outside the allowed range is not believed`() = runBlocking<Unit> {
        val t = trio()
        val rogue = RogueDevice(t.bob.userId())
        val devId = server.addDevice(t.bob.userId(), rogue.upload, announce = true)
        t.alice.engine.sync()
        val short = UUID.randomUUID().toString()
        val env = rogue.encryptFor(
            Recipient(t.alice.userId(), t.alice.deviceIdOnServer()), server.bundleFor(devId, t.alice),
            Content(text = "one second", expiresIn = 1, conversationId = t.ab, messageId = short),
        )
        server.injectMessage(t.alice.deviceIdOnServer(), forgedMessage(short, t.ab, t.bob.userId(), devId, env))
        t.alice.engine.sync()
        assertEquals("one second", t.alice.store.getMessage(short)!!.text)
        assertNull(t.alice.store.getMessage(short)!!.expiresIn)
    }
}

class ForwardAndUploadTest : FeatureTestBase() {
    private fun sample(t: TestClient, name: String, size: Int, seed: Int = 7) =
        File(t.dir, name).also { it.writeBytes(Random(seed).nextBytes(size)) }

    @Test
    fun `a forwarded message is re-encrypted for the target with fresh attachment keys and marked`() = runBlocking<Unit> {
        val t = trio()
        val file = sample(t.alice, "a.bin", 5_000)
        val original = t.alice.engine.sendAttachments(t.ab, listOf(OutgoingAttachment(file, "application/pdf", "file", "a.pdf")), caption = "read this")
        t.alice.engine.processOutbox(); t.bob.engine.sync()

        // bob forwards what he received (not downloaded yet: the engine fetches it) to the group and to a second chat
        t.alice.engine.setDisappearingTimer(t.group, 3_600); t.alice.engine.processOutbox(); t.bob.engine.sync(); t.carol.engine.sync()
        val ids = t.bob.engine.forwardMessage(original, listOf(t.group))
        t.bob.engine.processOutbox(); t.carol.engine.sync(); t.alice.engine.sync()
        val copy = t.carol.store.getMessage(ids.single())!!
        assertNotEquals(original, copy.id)
        assertTrue(copy.forwarded)
        assertEquals("read this", copy.text)
        assertEquals(t.group, copy.conversationId)
        assertEquals(t.bob.userId(), copy.senderUserId)
        assertEquals(3_600L, copy.expiresIn, "the copy carries the timer of the conversation it goes to")
        val a0 = t.bob.store.getMessage(original)!!.attachments.single()
        val a1 = copy.attachments.single()
        assertNotEquals(a0.id, a1.id); assertNotEquals(a0.key, a1.key); assertNotEquals(a0.sha256, a1.sha256)
        assertEquals("a.pdf", a1.name); assertEquals("application/pdf", a1.mime)
        assertContentEquals(file.readBytes(), t.carol.engine.downloadAttachment(copy.id, a1.id).readBytes())
        // the original is not forwarded, and not changed
        assertFalse(t.bob.store.getMessage(original)!!.forwarded)
        assertTrue(t.bob.store.getMessage(ids.single())!!.forwarded)
        // the server only ever got attachments uploaded by the forwarder: ids differ from the original's
        assertEquals(setOf(a1.id), serverStored(copy.id).msg.attachmentIds.toSet())
    }

    @Test
    fun `text can be forwarded to several conversations, at most five, never a deleted message`() = runBlocking<Unit> {
        val t = trio()
        val msg = t.alice.send(t.ab, "hello")
        val ids = t.alice.engine.forwardMessage(msg, listOf(t.group, t.group)) // duplicates collapse
        assertEquals(1, ids.size)
        t.alice.engine.processOutbox(); t.bob.engine.sync(); t.carol.engine.sync()
        assertEquals(listOf("hello"), t.carol.visible(t.group).map { it.text })
        assertTrue(t.carol.visible(t.group).single().forwarded)

        val many = (1..6).map { client("x$it").also { c -> c.signUp() } }
        t.alice.engine.refreshDirectory()
        val convs = many.map { t.alice.engine.createDirect(it.userId()).id }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.forwardMessage(msg, convs) }
        assertEquals(5, t.alice.engine.forwardMessage(msg, convs.take(5)).size)
        assertFailsWith<IllegalArgumentException> { t.alice.engine.forwardMessage(msg, emptyList()) }

        t.alice.engine.deleteMessage(msg); t.alice.engine.processOutbox()
        assertFailsWith<IllegalArgumentException> { t.alice.engine.forwardMessage(msg, listOf(t.group)) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.forwardMessage(UUID.randomUUID().toString(), listOf(t.group)) }
    }

    @Test
    fun `forwarding survives a 409 and a lost response and sends exactly one message per target`() = runBlocking<Unit> {
        val t = trio()
        val msg = t.bob.send(t.ab, "keep"); t.alice.engine.sync()
        server.addDevice(t.carol.userId(), RogueDevice(t.carol.userId()).upload)
        server.loseSendResponses = 1
        t.alice.engine.forwardMessage(msg, listOf(t.group))
        t.alice.engine.processOutbox(); t.alice.engine.processOutbox()
        assertEquals(1, server.messages.values.count { it.msg.conversationId == t.group && it.kind == MessageKind.MESSAGE })
    }

    @Test
    fun `big attachments go up as resumable uploads, in chunks`() = runBlocking<Unit> {
        val t = trio(threshold = 10_000, chunk = 4_096)
        server.maxChunkBytes = 4_096
        val file = sample(t.alice, "big.bin", 30_000)
        val id = t.alice.engine.sendAttachments(t.ab, listOf(OutgoingAttachment(file, "application/octet-stream", "file", "big.bin")))
        t.alice.engine.processOutbox()
        assertEquals(MessageStatus.SENT, t.alice.store.getMessage(id)!!.status)
        assertEquals(1, server.log.count { it.startsWith("upload-start:") })
        assertTrue(server.log.count { it.startsWith("upload-chunk:") } >= 8, "30 kB in 4 kB chunks plus the cipher overhead")
        t.bob.engine.sync()
        val a = t.bob.store.getMessage(id)!!.attachments.single()
        assertContentEquals(file.readBytes(), t.bob.engine.downloadAttachment(id, a.id).readBytes())
        // a small file still takes the plain route
        val small = sample(t.alice, "small.bin", 1_000)
        t.alice.engine.sendAttachments(t.ab, listOf(OutgoingAttachment(small, "application/octet-stream", "file", "small.bin")))
        t.alice.engine.processOutbox()
        assertEquals(1, server.log.count { it.startsWith("upload-start:") })
    }

    @Test
    fun `a resumable upload continues where the server stopped after a broken request or a lost response`() = runBlocking<Unit> {
        val t = trio(threshold = 10_000, chunk = 4_096)
        server.maxChunkBytes = 4_096
        val file = sample(t.alice, "big.bin", 30_000, seed = 3)
        server.failChunks = 1
        server.loseChunkResponses = 1
        val id = t.alice.engine.sendAttachments(t.ab, listOf(OutgoingAttachment(file, "application/octet-stream", "file", "big.bin")))
        t.alice.engine.processOutbox()
        assertEquals(MessageStatus.SENT, t.alice.store.getMessage(id)!!.status)
        assertEquals(1, server.log.count { it.startsWith("upload-start:") }, "one session, resumed")
        // no byte was sent twice: offsets strictly increase
        val offsets = server.log.filter { it.startsWith("upload-chunk:") }.map { it.split(":")[1].toLong() }
        assertEquals(offsets.distinct(), offsets)
        t.bob.engine.sync()
        val a = t.bob.store.getMessage(id)!!.attachments.single()
        assertContentEquals(file.readBytes(), t.bob.engine.downloadAttachment(id, a.id).readBytes())
    }

    @Test
    fun `a message carries up to ten attachments with a caption, not eleven`() = runBlocking<Unit> {
        val t = trio()
        val files = (1..10).map { OutgoingAttachment(sample(t.alice, "f$it.bin", 300 + it, it), "application/octet-stream", "file", "f$it.bin") }
        val id = t.alice.engine.sendAttachments(t.ab, files, caption = "ten files")
        t.alice.engine.processOutbox(); t.bob.engine.sync()
        val got = t.bob.store.getMessage(id)!!
        assertEquals("ten files", got.text)
        assertEquals((1..10).map { "f$it.bin" }, got.attachments.map { it.name })
        assertFailsWith<IllegalArgumentException> {
            t.alice.engine.sendAttachments(t.ab, files + files.first(), caption = null)
        }
    }
}
