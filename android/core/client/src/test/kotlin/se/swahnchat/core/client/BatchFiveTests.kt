package se.swahnchat.core.client

import kotlinx.coroutines.runBlocking
import se.swahnchat.core.crypto.Recipient
import se.swahnchat.core.model.Content
import se.swahnchat.core.model.ContentType
import se.swahnchat.core.model.Envelope
import se.swahnchat.core.model.MessageKind
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mentions, polls, stars and scheduled messages (batch 5) on the engine against the fake server. */
abstract class BatchFiveBase : FeatureTestBase() {
    /** A device of [owner]'s account that the test controls, listed and announced, so the others accept what it encrypts. */
    protected class Rogue(val id: String, val device: RogueDevice)

    protected suspend fun rogueOf(owner: TestClient, vararg observers: TestClient): Rogue {
        val device = RogueDevice(owner.userId())
        val id = server.addDevice(owner.userId(), device.upload, announce = true)
        observers.forEach { it.engine.sync() }
        return Rogue(id, device)
    }

    protected suspend fun Rogue.deliver(
        to: TestClient, from: TestClient, conv: String, content: Content, kind: String = MessageKind.MESSAGE, target: String? = null,
    ): String {
        val id = content.messageId ?: UUID.randomUUID().toString()
        val env: Envelope = device.encryptFor(Recipient(to.userId(), to.deviceIdOnServer()), server.bundleFor(this.id, to), content)
        server.injectMessage(to.deviceIdOnServer(), forgedMessage(id, conv, from.userId(), this.id, env, kind, target))
        return id
    }

    protected fun vote(conv: String, poll: String, ids: List<Int>? = null, close: Boolean? = null) = Content(
        type = ContentType.POLL_VOTE, optionIds = ids, close = close, conversationId = conv,
        messageId = UUID.randomUUID().toString(), targetMessageId = poll,
    )

    protected suspend fun TestClient.pollOf(id: String) = store.getMessage(id)!!.poll!!
    protected suspend fun TestClient.votesOf(id: String) = engine.pollVotes(id).associate { it.userId to it.optionIds }
}

class MentionTest : BatchFiveBase() {
    @Test
    fun `mentions travel inside the encrypted content and land on the members`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.engine.sendText(t.group, "hej @Bob", mentions = listOf(t.bob.userId()))
        t.alice.engine.processOutbox()
        t.bob.engine.sync(); t.carol.engine.sync()
        assertEquals(listOf(t.bob.userId()), t.bob.store.getMessage(id)!!.mentions)
        assertEquals(listOf(t.bob.userId()), t.carol.store.getMessage(id)!!.mentions)
        assertEquals(listOf(t.bob.userId()), t.alice.store.getMessage(id)!!.mentions)
        assertEquals(1, t.bob.engine.unreadMentions(t.group))
        assertEquals(0, t.carol.engine.unreadMentions(t.group))
        // the server only has ciphertext; the ids are not in anything it can read
        val stored = serverStored(id)
        assertFalse(stored.envelopes.values.any { t.bob.userId() in it.body })
        t.bob.engine.markRead(t.group)
        assertEquals(0, t.bob.engine.unreadMentions(t.group))
    }

    @Test
    fun `the sender refuses a mention of a non-member, in a direct chat, of itself only, and too many`() = runBlocking<Unit> {
        val t = trio()
        val dave = client("dave"); dave.signUp()
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendText(t.group, "hej @Dave", mentions = listOf(dave.userId())) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendText(t.ab, "hej @Bob", mentions = listOf(t.bob.userId())) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendText(t.group, "x", mentions = (1..51).map { "u$it" }) }
        // own id and duplicates are dropped, not an error
        val id = t.alice.engine.sendText(t.group, "hej", mentions = listOf(t.alice.userId(), t.bob.userId(), t.bob.userId()))
        assertEquals(listOf(t.bob.userId()), t.alice.store.getMessage(id)!!.mentions)
        assertTrue(t.alice.store.getOutbox().isNotEmpty() && t.alice.messages(t.group).none { it.text == "x" })
    }

    @Test
    fun `the receiver ignores mentioned ids that are not members`() = runBlocking<Unit> {
        val t = trio()
        val dave = client("dave"); dave.signUp()
        val rogue = rogueOf(t.bob, t.alice)
        val id = UUID.randomUUID().toString()
        rogue.deliver(
            t.alice, t.bob, t.group,
            Content(text = "hej", mentions = listOf(dave.userId(), t.carol.userId(), "not-a-user", t.carol.userId()), conversationId = t.group, messageId = id),
        )
        t.alice.engine.sync()
        assertEquals(listOf(t.carol.userId()), t.alice.store.getMessage(id)!!.mentions)
        // in a direct conversation there are no mentions at all
        val id2 = UUID.randomUUID().toString()
        rogue.deliver(t.alice, t.bob, t.ab, Content(text = "hej", mentions = listOf(t.alice.userId()), conversationId = t.ab, messageId = id2))
        t.alice.engine.sync()
        assertEquals(emptyList(), t.alice.store.getMessage(id2)!!.mentions)
        assertEquals(0, t.alice.engine.unreadMentions(t.ab))
    }
}

class PollTest : BatchFiveBase() {
    private suspend fun newPoll(t: Pair3, multiple: Boolean = false): String {
        val id = t.alice.engine.sendPoll(t.group, "Pizza?", listOf("Ja", "Nej", "Kanske"), multiple)
        t.alice.engine.processOutbox()
        t.bob.engine.sync(); t.carol.engine.sync()
        return id
    }

    @Test
    fun `a poll is an ordinary message with the options in the encrypted content`() = runBlocking<Unit> {
        val t = trio()
        val id = newPoll(t, multiple = true)
        for (c in listOf(t.alice, t.bob, t.carol)) {
            val m = c.store.getMessage(id)!!
            assertEquals("Pizza?", m.text)
            assertEquals(listOf("Ja", "Nej", "Kanske"), m.poll!!.options.map { it.text })
            assertEquals(listOf(0, 1, 2), m.poll!!.options.map { it.id })
            assertTrue(m.poll!!.multiple && !m.poll!!.closed)
        }
        assertEquals(MessageKind.MESSAGE, serverStored(id).kind)
        assertEquals(1, t.bob.visible(t.group).size)
    }

    @Test
    fun `a poll is validated before it is queued`() = runBlocking<Unit> {
        val t = trio()
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendPoll(t.group, "x", listOf("Bara en")) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendPoll(t.group, "x", (1..11).map { "v$it" }) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendPoll(t.group, "q".repeat(201), listOf("a", "b")) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendPoll(t.group, "x", listOf("a", "b".repeat(101))) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendPoll(t.group, " ", listOf("a", "b")) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendPoll(t.group, "x", listOf("a", " ")) }
        t.alice.engine.sendPoll(t.group, "q".repeat(200), (1..10).map { "o".repeat(100) })
        assertTrue(t.alice.messages(t.group).count { it.poll != null } == 1)
    }

    @Test
    fun `votes travel as a reaction on the wire, the newest vote of a member replaces the older and an empty vote withdraws`() = runBlocking<Unit> {
        val t = trio()
        val poll = newPoll(t)
        t.bob.engine.votePoll(poll, listOf(0))
        assertEquals(mapOf(t.bob.userId() to listOf(0)), t.bob.votesOf(poll)) // shown at once
        t.bob.engine.processOutbox()
        // the server sees a reaction, nothing that says it is a vote
        val voteMsg = server.messages.values.single { it.msg.targetMessageId == poll }
        assertEquals(MessageKind.REACTION, voteMsg.kind)
        t.alice.engine.sync(); t.carol.engine.sync()
        assertEquals(mapOf(t.bob.userId() to listOf(0)), t.alice.votesOf(poll))
        assertEquals(mapOf(t.bob.userId() to listOf(0)), t.carol.votesOf(poll))
        // a vote is not a message: no bubble, no unread, no last message change
        assertEquals(1, t.alice.visible(t.group).size)
        assertEquals(1, t.carol.store.unreadCount(t.group)) // only the poll itself is unread

        t.carol.engine.votePoll(poll, listOf(1)); t.carol.engine.processOutbox()
        t.bob.engine.votePoll(poll, listOf(2)); t.bob.engine.processOutbox() // replaces his first vote
        t.alice.engine.sync()
        assertEquals(mapOf(t.bob.userId() to listOf(2), t.carol.userId() to listOf(1)), t.alice.votesOf(poll))
        t.bob.engine.votePoll(poll, emptyList()); t.bob.engine.processOutbox() // withdrawn
        t.alice.engine.sync(); t.carol.engine.sync(); t.bob.engine.sync()
        for (c in listOf(t.alice, t.carol, t.bob)) assertEquals(mapOf(t.carol.userId() to listOf(1)), c.votesOf(poll))
    }

    @Test
    fun `an older vote that arrives after a newer one does not replace it`() = runBlocking<Unit> {
        val t = trio()
        val poll = newPoll(t)
        val rogue = rogueOf(t.bob, t.alice)
        val to = Recipient(t.alice.userId(), t.alice.deviceIdOnServer())
        fun forged(ids: List<Int>): se.swahnchat.core.model.Message {
            val c = vote(t.group, poll, ids)
            val env = rogue.device.encryptFor(to, runBlocking { server.bundleFor(rogue.id, t.alice) }, c)
            return forgedMessage(c.messageId!!, t.group, t.bob.userId(), rogue.id, env, MessageKind.REACTION, poll)
        }
        val older = forged(listOf(0))
        val newer = forged(listOf(2)) // the higher server sequence number
        server.injectMessage(t.alice.deviceIdOnServer(), newer)
        server.injectMessage(t.alice.deviceIdOnServer(), older)
        t.alice.engine.sync()
        assertEquals(mapOf(t.bob.userId() to listOf(2)), t.alice.votesOf(poll))
    }

    @Test
    fun `a single-choice poll refuses several options, an unknown option, and a multiple one takes several`() = runBlocking<Unit> {
        val t = trio()
        val poll = newPoll(t)
        assertFailsWith<IllegalArgumentException> { t.bob.engine.votePoll(poll, listOf(0, 1)) }
        assertFailsWith<IllegalArgumentException> { t.bob.engine.votePoll(poll, listOf(7)) }
        val multi = t.alice.engine.sendPoll(t.group, "Toppings?", listOf("Ost", "Skinka", "Svamp"), multiple = true)
        t.alice.engine.processOutbox(); t.bob.engine.sync()
        t.bob.engine.votePoll(multi, listOf(0, 2)); t.bob.engine.processOutbox()
        t.alice.engine.sync()
        assertEquals(mapOf(t.bob.userId() to listOf(0, 2)), t.alice.votesOf(multi))

        // a forged vote with two options in a single-choice poll is ignored by the receiver too
        val rogue = rogueOf(t.bob, t.alice)
        rogue.deliver(t.alice, t.bob, t.group, vote(t.group, poll, listOf(0, 1)), MessageKind.REACTION, poll)
        rogue.deliver(t.alice, t.bob, t.group, vote(t.group, poll, listOf(9)), MessageKind.REACTION, poll)
        t.alice.engine.sync()
        assertTrue(t.alice.votesOf(poll).isEmpty())
    }

    @Test
    fun `only the creator can close a poll, and nobody can vote afterwards`() = runBlocking<Unit> {
        val t = trio()
        val poll = newPoll(t)
        t.bob.engine.votePoll(poll, listOf(0)); t.bob.engine.processOutbox()
        assertFailsWith<IllegalArgumentException> { t.bob.engine.closePoll(poll) }
        // a forged close from a member that is not the creator is ignored
        val rogue = rogueOf(t.bob, t.alice, t.carol)
        rogue.deliver(t.alice, t.bob, t.group, vote(t.group, poll, close = true), MessageKind.REACTION, poll)
        t.alice.engine.sync()
        assertFalse(t.alice.pollOf(poll).closed)

        t.alice.engine.closePoll(poll)
        assertTrue(t.alice.pollOf(poll).closed) // at once
        t.alice.engine.processOutbox()
        t.bob.engine.sync(); t.carol.engine.sync()
        assertTrue(t.bob.pollOf(poll).closed && t.carol.pollOf(poll).closed)
        assertEquals(MessageKind.REACTION, server.messages.values.last { it.msg.targetMessageId == poll }.kind)
        // the votes stay, new ones are refused here and ignored there
        assertEquals(mapOf(t.bob.userId() to listOf(0)), t.carol.votesOf(poll))
        assertFailsWith<IllegalArgumentException> { t.carol.engine.votePoll(poll, listOf(1)) }
        rogue.deliver(t.alice, t.bob, t.group, vote(t.group, poll, listOf(2)), MessageKind.REACTION, poll)
        t.alice.engine.sync()
        assertEquals(mapOf(t.bob.userId() to listOf(0)), t.alice.votesOf(poll))
    }

    @Test
    fun `votes from non-members and votes on messages that are not polls are ignored`() = runBlocking<Unit> {
        val t = trio()
        val poll = newPoll(t)
        val dave = client("dave"); dave.signUp()
        val outsider = rogueOf(dave, t.alice)
        outsider.deliver(t.alice, dave, t.group, vote(t.group, poll, listOf(0)), MessageKind.REACTION, poll)
        t.alice.engine.sync()
        assertTrue(t.alice.votesOf(poll).isEmpty())

        val plain = t.alice.send(t.group, "inte en omröstning")
        t.bob.engine.sync()
        val member = rogueOf(t.bob, t.alice)
        member.deliver(t.alice, t.bob, t.group, vote(t.group, plain, listOf(0)), MessageKind.REACTION, plain)
        t.alice.engine.sync()
        assertTrue(t.alice.engine.pollVotes(plain).isEmpty())
        assertFailsWith<IllegalArgumentException> { t.bob.engine.votePoll(plain, listOf(0)) }
    }

    @Test
    fun `a vote on a deleted poll is ignored and deleting the poll removes its votes`() = runBlocking<Unit> {
        val t = trio()
        val poll = newPoll(t)
        t.bob.engine.votePoll(poll, listOf(1)); t.bob.engine.processOutbox()
        t.alice.engine.sync()
        assertEquals(1, t.alice.votesOf(poll).size)
        t.alice.engine.deleteMessage(poll); t.alice.engine.processOutbox()
        t.bob.engine.sync(); t.carol.engine.sync()
        assertTrue(t.bob.store.getMessage(poll)!!.deleted)
        assertTrue(t.alice.engine.pollVotes(poll).isEmpty() && t.bob.engine.pollVotes(poll).isEmpty())
        assertFailsWith<IllegalArgumentException> { t.carol.engine.votePoll(poll, listOf(0)) }
        val rogue = rogueOf(t.bob, t.alice)
        rogue.deliver(t.alice, t.bob, t.group, vote(t.group, poll, listOf(0)), MessageKind.REACTION, poll)
        t.alice.engine.sync()
        assertTrue(t.alice.engine.pollVotes(poll).isEmpty())
    }

    @Test
    fun `a poll cannot be edited and the vote of a second device of the same account counts`() = runBlocking<Unit> {
        val t = trio()
        val poll = newPoll(t)
        assertFailsWith<IllegalArgumentException> { t.alice.engine.editMessage(poll, "other") }
        // another device of bob votes: alice and bob's first phone both see it
        val rogue = rogueOf(t.bob, t.alice)
        rogue.deliver(t.alice, t.bob, t.group, vote(t.group, poll, listOf(2)), MessageKind.REACTION, poll)
        t.alice.engine.sync()
        assertEquals(mapOf(t.bob.userId() to listOf(2)), t.alice.votesOf(poll))
    }

    @Test
    fun `an unsent vote that the server refuses for good is taken back`() = runBlocking<Unit> {
        val t = trio()
        val poll = newPoll(t)
        t.alice.engine.deleteMessage(poll); t.alice.engine.processOutbox() // the server now has no target for a vote
        // bob does not know yet: his vote is queued, shown, and refused (not found) by the server
        t.bob.engine.votePoll(poll, listOf(0))
        assertEquals(mapOf(t.bob.userId() to listOf(0)), t.bob.votesOf(poll))
        t.bob.engine.processOutbox()
        assertTrue(t.bob.store.getOutbox().isEmpty())
        assertNull(t.bob.store.getPollVote(poll, t.bob.userId()))
    }
}

class StarTest : BatchFiveBase() {
    @Test
    fun `a star is local, newest first, and never reaches the server`() = runBlocking<Unit> {
        val t = trio()
        val a = t.alice.send(t.ab, "ett"); val b = t.alice.send(t.group, "två")
        t.bob.engine.sync()
        val before = server.log.size
        val msgsBefore = server.messages.size
        clock.addAndGet(1_000); t.bob.engine.setStarred(a, true)
        clock.addAndGet(1_000); t.bob.engine.setStarred(b, true)
        assertTrue(t.bob.engine.isStarred(a))
        assertEquals(listOf(b, a), t.bob.engine.starredMessages().map { it.id })
        assertEquals(listOf(a), t.bob.engine.starredMessages(t.ab).map { it.id })
        assertEquals(emptyList(), t.alice.engine.starredMessages().map { it.id }) // stars are per phone
        t.bob.engine.setStarred(a, false)
        assertEquals(listOf(b), t.bob.engine.starredMessages().map { it.id })
        assertEquals(before, server.log.size)
        assertEquals(msgsBefore, server.messages.size)
        assertTrue(t.bob.store.getOutbox().isEmpty())
    }

    @Test
    fun `a star goes with the message when it is deleted for everyone, for me, or the conversation goes`() = runBlocking<Unit> {
        val t = trio()
        val a = t.alice.send(t.group, "ett"); val b = t.alice.send(t.group, "två"); val c = t.alice.send(t.ab, "tre")
        t.bob.engine.sync()
        for (id in listOf(a, b, c)) t.bob.engine.setStarred(id, true)
        t.alice.engine.deleteMessage(a); t.alice.engine.processOutbox(); t.bob.engine.sync()
        assertFalse(t.bob.engine.isStarred(a))
        t.bob.engine.deleteMessageForMe(b)
        assertFalse(t.bob.engine.isStarred(b))
        assertEquals(listOf(c), t.bob.engine.starredMessages().map { it.id })
        t.bob.store.removeConversation(t.ab)
        assertEquals(emptyList(), t.bob.engine.starredMessages().map { it.id })
        // an own message can be starred and un-starred, a notice cannot be starred
        val own = t.bob.send(t.group, "mitt")
        t.bob.engine.setStarred(own, true)
        assertEquals(listOf(own), t.bob.engine.starredMessages(t.group).map { it.id })
        val notice = t.bob.messages(t.group).firstOrNull { it.isSystem }
        if (notice != null) assertFailsWith<IllegalArgumentException> { t.bob.engine.setStarred(notice.id, true) }
    }

    @Test
    fun `expiry removes the star`() = runBlocking<Unit> {
        val t = trio()
        t.alice.engine.setDisappearingTimer(t.ab, 300); t.alice.engine.processOutbox(); t.bob.engine.sync()
        val id = t.alice.send(t.ab, "försvinner"); t.bob.engine.sync()
        t.bob.engine.setStarred(id, true)
        t.bob.engine.markDisplayed(listOf(id))
        clock.addAndGet(301_000)
        t.bob.engine.purgeExpired()
        assertNull(t.bob.store.getMessage(id))
        assertEquals(emptyList(), t.bob.engine.starredMessages().map { it.id })
    }
}

class ScheduledMessageTest : BatchFiveBase() {
    private val minute = 60_000L

    @Test
    fun `nothing is sent before the time, then it is sent encrypted for the devices of that moment`() = runBlocking<Unit> {
        val t = trio()
        val sentBefore = server.log.count { it.startsWith("send:") }
        val at = clock.get() + 10 * minute
        val id = t.alice.engine.sendText(t.ab, "senare", sendAt = at)
        val queued = t.alice.store.getMessage(id)!!
        assertEquals(MessageStatus.PENDING, queued.status)
        assertEquals(at, queued.scheduledAt)
        assertEquals(listOf(id), t.alice.engine.scheduledMessages(t.ab).map { it.id })
        assertEquals(at, t.alice.engine.nextScheduledAt())

        // passes before the time do nothing: nothing is encrypted, nothing is sent, the queue is not "pending"
        val early = t.alice.engine.processOutbox()
        assertEquals(0, early.sent + early.failed + early.pending)
        clock.addAndGet(9 * minute)
        t.alice.engine.processOutbox()
        assertEquals(sentBefore, server.log.count { it.startsWith("send:") })
        assertNull(t.alice.store.getOutboxEntry(id)!!.envelopes) // encryption happens at send time, not before
        assertNull(server.messages[id])

        // bob gets another device while the message waits; it is encrypted for it when it goes out
        val rogue = rogueOf(t.bob, t.alice)
        clock.addAndGet(2 * minute)
        t.alice.engine.processOutbox()
        assertNotNull(server.messages[id])
        assertTrue(rogue.id in serverStored(id).envelopes.keys)
        val sent = t.alice.store.getMessage(id)!!
        assertNull(sent.scheduledAt)
        assertNotNull(sent.seq)
        assertEquals(clock.get(), sent.clientTs) // written when it was sent
        assertTrue(t.alice.engine.scheduledMessages().isEmpty())
        assertNull(t.alice.engine.nextScheduledAt())
        t.bob.engine.sync()
        assertEquals("senare", t.bob.store.getMessage(id)!!.text)
    }

    @Test
    fun `the disappearing-messages timer applies from the actual send`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.engine.sendText(t.ab, "tickande", sendAt = clock.get() + 5 * minute)
        assertNull(t.alice.store.getMessage(id)!!.expiresIn)
        t.alice.engine.setDisappearingTimer(t.ab, 3600) // set after it was scheduled
        t.alice.engine.processOutbox()
        clock.addAndGet(6 * minute)
        t.alice.engine.processOutbox()
        t.bob.engine.sync()
        assertEquals(3600L, t.alice.store.getMessage(id)!!.expiresIn)
        assertEquals(3600L, t.bob.store.getMessage(id)!!.expiresIn)
    }

    @Test
    fun `a schedule is limited to a minute to thirty days ahead`() = runBlocking<Unit> {
        val t = trio()
        val now = clock.get()
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendText(t.ab, "x", sendAt = now + 30_000) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendText(t.ab, "x", sendAt = now - minute) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendText(t.ab, "x", sendAt = now + 31L * 24 * 60 * minute) }
        t.alice.engine.sendText(t.ab, "ok", sendAt = now + minute)
        t.alice.engine.sendText(t.ab, "ok", sendAt = now + 30L * 24 * 60 * minute)
        assertEquals(2, t.alice.engine.scheduledMessages().size)
        assertTrue(t.alice.messages(t.ab).none { it.text == "x" })
    }

    @Test
    fun `a scheduled message can be moved, sent now, or thrown away`() = runBlocking<Unit> {
        val t = trio()
        val a = t.alice.engine.sendText(t.ab, "flytta", sendAt = clock.get() + 10 * minute)
        val b = t.alice.engine.sendText(t.ab, "nu", sendAt = clock.get() + 20 * minute)
        val c = t.alice.engine.sendText(t.ab, "bort", sendAt = clock.get() + 30 * minute)
        assertEquals(listOf(a, b, c), t.alice.engine.scheduledMessages().map { it.id })
        t.alice.engine.rescheduleMessage(a, clock.get() + 40 * minute)
        assertEquals(listOf(b, c, a), t.alice.engine.scheduledMessages().map { it.id })
        assertFailsWith<IllegalArgumentException> { t.alice.engine.rescheduleMessage(a, clock.get() + 10_000) }

        t.alice.engine.sendScheduledNow(b)
        t.alice.engine.processOutbox()
        assertNotNull(server.messages[b])
        assertNull(t.alice.store.getMessage(b)!!.scheduledAt)

        val removed = t.alice.engine.cancelScheduled(c)
        assertEquals("bort", removed!!.text)
        assertNull(t.alice.store.getMessage(c))
        assertNull(t.alice.store.getOutboxEntry(c))
        clock.addAndGet(60 * minute)
        t.alice.engine.processOutbox()
        assertNull(server.messages[c])
        assertNotNull(server.messages[a]) // the moved one went out at its new time
        // sending a message that is not scheduled this way is not possible
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendScheduledNow(b) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.cancelScheduled(b) }
    }

    @Test
    fun `scheduled attachments are uploaded when the time comes, not before`() = runBlocking<Unit> {
        val t = trio()
        val file = java.io.File(t.alice.dir, "bild.bin").apply { writeBytes(ByteArray(2000) { it.toByte() }) }
        val id = t.alice.engine.sendAttachments(
            t.ab, listOf(OutgoingAttachment(file, "application/octet-stream", "file", "bild.bin")), caption = "senare fil",
            sendAt = clock.get() + 5 * minute,
        )
        t.alice.engine.processOutbox()
        assertEquals(0, server.log.count { it == "send:$id" })
        assertNull(t.alice.store.getOutboxEntry(id)!!.attachments.single().uploadedId)
        clock.addAndGet(6 * minute)
        t.alice.engine.processOutbox()
        assertNotNull(t.alice.store.getMessage(id)!!.seq)
        t.bob.engine.sync()
        assertEquals("senare fil", t.bob.store.getMessage(id)!!.text)
        assertEquals(1, t.bob.store.getMessage(id)!!.attachments.size)
    }

    @Test
    fun `a scheduled message stays out of the queue while a later one waits behind nothing`() = runBlocking<Unit> {
        val t = trio()
        t.alice.engine.sendText(t.ab, "senare", sendAt = clock.get() + 30 * minute)
        val now = t.alice.send(t.ab, "direkt")
        assertNotNull(server.messages[now])
        t.bob.engine.sync()
        assertEquals(listOf("direkt"), t.bob.visible(t.ab).map { it.text })
    }
}
