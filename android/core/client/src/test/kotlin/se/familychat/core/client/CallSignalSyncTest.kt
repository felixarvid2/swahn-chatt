package se.familychat.core.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import se.familychat.core.model.CallAction
import se.familychat.core.model.CallSignal
import se.familychat.core.model.EventType
import se.familychat.core.model.FamilyChatJson
import se.familychat.core.model.IceCandidate
import se.familychat.core.model.Message
import se.familychat.core.model.MessageKind
import se.familychat.core.model.SendMessageRequest
import se.familychat.core.network.ApiException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Call signals through the real engines, crypto and the fake server: sending, receiving, and what is dropped. */
class CallSignalSyncTest {
    private val server = FakeServer()
    private val scope = newScope()

    @AfterTest fun tearDown() = scope.cancel()

    private fun client(name: String) = TestClient(server, name, scope, false)

    private val sdp = "v=0\r\na=fingerprint:sha-256 AA:BB\r\n"
    private fun offer(video: Boolean = true) = CallSignal(UUID.randomUUID().toString(), CallAction.OFFER, video, sdp = sdp)
    private fun hangup(id: String = UUID.randomUUID().toString()) = CallSignal(id, CallAction.HANGUP)

    private class Trio(val alice: TestClient, val bob: TestClient, val carol: TestClient, val ab: String, val ac: String)

    private suspend fun trio(): Trio {
        val alice = client("alice"); val bob = client("bob"); val carol = client("carol")
        alice.signUp(); bob.signUp(); carol.signUp()
        alice.engine.refreshDirectory(); bob.engine.refreshDirectory(); carol.engine.refreshDirectory()
        val ab = alice.engine.createDirect(bob.userId()).id
        val ac = alice.engine.createDirect(carol.userId()).id
        bob.engine.sync(); carol.engine.sync()
        return Trio(alice, bob, carol, ab, ac)
    }

    /** Everything [client] emits from now on. */
    private class Watch(client: TestClient, scope: kotlinx.coroutines.CoroutineScope) {
        val signals = CopyOnWriteArrayList<CallSignalEvent>()
        val events = CopyOnWriteArrayList<EngineEvent>()

        init {
            scope.launch(kotlinx.coroutines.Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { client.engine.callSignals.collect { signals += it } }
            scope.launch(kotlinx.coroutines.Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { client.engine.events.collect { events += it } }
        }
    }

    private fun rewriteQueued(deviceId: String, messageId: String, change: (Message) -> Message) {
        server.queues.getValue(deviceId).replaceAll { e ->
            if (e.type == EventType.MESSAGE && (e.data as JsonObject)["id"] == JsonPrimitive(messageId)) {
                val n = change(FamilyChatJson.decodeFromJsonElement(Message.serializer(), e.data!!))
                e.copy(conversationId = n.conversationId, data = FamilyChatJson.encodeToJsonElement(Message.serializer(), n))
            } else e
        }
    }

    private suspend fun assertNothingStored(c: TestClient, conv: String, before: List<String> = emptyList()) {
        assertEquals(before, c.messages(conv).map { it.id }, "a call signal left a message or notice behind")
        assertEquals(0, c.store.unreadCount(conv))
        assertNull(c.store.getConversation(conv)?.lastMessageSeq)
    }

    private fun assertNoMessageEvents(w: Watch) {
        assertTrue(w.events.none { it is EngineEvent.MessageReceived || it is EngineEvent.MessageChanged }, "events: ${w.events}")
    }

    @Test
    fun `a signal reaches every device of the other member as an event and is stored nowhere`() = runBlocking<Unit> {
        val t = trio()
        val bob2 = client("bob")
        bob2.engine.login("https://fake.example", "bob", "password-bob")
        bob2.engine.refreshDirectory(); bob2.engine.sync(); t.bob.engine.sync() // bob hears of his own new phone first
        val bobHad = t.bob.messages(t.ab).map { it.id }
        val w1 = Watch(t.bob, scope); val w2 = Watch(bob2, scope)
        val signal = offer()
        t.alice.engine.sendCallSignal(t.ab, signal) // alice has not heard of bob2: the 409 is resolved like for a message
        assertEquals(setOf(t.bob.deviceIdOnServer(), bob2.deviceIdOnServer()), server.callMessages().single().envelopes.keys)
        t.bob.engine.sync(); bob2.engine.sync()
        for (w in listOf(w1, w2)) {
            val e = w.signals.single()
            assertEquals(CallSignalEvent(t.ab, t.alice.userId(), t.alice.deviceIdOnServer(), false, e.serverTs, signal), e)
            assertNoMessageEvents(w)
        }
        assertNothingStored(t.bob, t.ab, bobHad)
        assertNothingStored(bob2, t.ab)
        // nothing on the sending side either: no row, no outbox entry
        assertTrue(t.alice.messages(t.ab).none { !it.isSystem }) // (only the notice about bob's new phone, which the 409 revealed)
        assertTrue(t.alice.store.getOutbox().isEmpty())
        // the ratchet moved on: ordinary messages still decrypt in both directions
        val id = t.alice.engine.sendText(t.ab, "after the call signal"); t.alice.engine.processOutbox()
        t.bob.engine.sync()
        assertEquals("after the call signal", t.bob.store.getMessage(id)!!.text)
        t.bob.approveAllPending() // (his own new phone, unrelated to the call)
        val back = t.bob.engine.sendText(t.ab, "and back"); t.bob.engine.processOutbox()
        t.alice.engine.sync()
        assertEquals("and back", t.alice.store.getMessage(back)!!.text)
        assertEquals(1, t.bob.store.unreadCount(t.ab))
    }

    @Test
    fun `the caller's other device gets the signal marked as from itself`() = runBlocking<Unit> {
        val t = trio()
        val alice2 = client("alice")
        alice2.engine.login("https://fake.example", "alice", "password-alice")
        alice2.engine.refreshDirectory()
        t.alice.engine.sync(); t.alice.approveAllPending()
        val w = Watch(alice2, scope)
        val signal = CallSignal(UUID.randomUUID().toString(), CallAction.ANSWER, sdp = sdp)
        t.bob.engine.sendCallSignal(t.ab, signal) // bob answers: alice's two phones both learn it
        alice2.engine.sync()
        val fromBob = w.signals.single()
        assertEquals(false, fromBob.fromSelf)
        assertEquals(t.bob.userId(), fromBob.fromUserId)
        w.signals.clear()
        t.alice.engine.sendCallSignal(t.ab, hangup(signal.callId))
        alice2.engine.sync()
        val own = w.signals.single()
        assertTrue(own.fromSelf)
        assertEquals(t.alice.userId(), own.fromUserId)
        assertEquals(t.alice.deviceIdOnServer(), own.fromDeviceId)
        assertNothingStored(alice2, t.ab)
    }

    @Test
    fun `a signal older than 60 seconds is dropped without breaking the ratchet`() = runBlocking<Unit> {
        val t = trio()
        val w = Watch(t.bob, scope)
        server.serverTime = { System.currentTimeMillis() - 120_000 }
        t.alice.engine.sendCallSignal(t.ab, offer())
        server.serverTime = { System.currentTimeMillis() - 59_000 }
        val fresh = offer()
        t.alice.engine.sendCallSignal(t.ab, fresh)
        t.bob.engine.sync()
        assertEquals(listOf(fresh.callId), w.signals.map { it.signal.callId })
        assertNothingStored(t.bob, t.ab)
        val id = t.alice.engine.sendText(t.ab, "still works"); t.alice.engine.processOutbox()
        t.bob.engine.sync()
        assertEquals("still works", t.bob.store.getMessage(id)!!.text)
    }

    @Test
    fun `a phone and a server with different clocks still agree on what is fresh`() = runBlocking<Unit> {
        server.serverTime = { System.currentTimeMillis() - 300_000 } // the server's clock is 5 minutes behind the phones'
        val t = trio()
        val w = Watch(t.bob, scope)
        t.alice.engine.sendCallSignal(t.ab, offer())
        t.bob.engine.sync()
        assertEquals(1, w.signals.size)
    }

    @Test
    fun `a signal with a wrong binding, from a non-member or in a group is dropped and consumed`() = runBlocking<Unit> {
        val t = trio()
        val group = t.alice.engine.createGroup("g", listOf(t.bob.userId(), t.carol.userId()))
        t.bob.engine.sync(); t.carol.engine.sync()
        val w = Watch(t.bob, scope)
        // carol writes to alice; the server delivers it to bob as if it were a call in a conversation carol is not part of
        t.carol.engine.sendCallSignal(t.ac, offer())
        val carolsId = server.callMessages().single().msg.id
        // alice (member of ac and ab) receives it as a call in ab
        rewriteQueued(t.alice.deviceIdOnServer(), carolsId) { it.copy(conversationId = t.ab) }
        val wa = Watch(t.alice, scope)
        t.alice.engine.sync()
        assertTrue(wa.signals.isEmpty())
        // bob's call in ab shown to alice as a call in the group (alice is a member of both: only the binding tells)
        t.bob.engine.sendCallSignal(t.ab, offer())
        val bobsId = server.callMessages().last().msg.id
        rewriteQueued(t.alice.deviceIdOnServer(), bobsId) { it.copy(conversationId = group.id) }
        t.alice.engine.sync()
        assertTrue(wa.signals.isEmpty())
        assertTrue(t.alice.messages(group.id).none { !it.isSystem })
        assertTrue(t.alice.messages(t.ab).isEmpty())
        // a genuine one afterwards still works
        t.bob.engine.sendCallSignal(t.ab, offer())
        t.alice.engine.sync()
        assertEquals(1, wa.signals.size)
        assertTrue(w.signals.isEmpty())
    }

    @Test
    fun `a call event without an envelope is consumed silently`() = runBlocking<Unit> {
        val t = trio()
        val w = Watch(t.bob, scope)
        val cursorBefore = t.bob.store.getSyncCursor()
        server.injectMessage(
            t.bob.deviceIdOnServer(),
            forgedMessage(UUID.randomUUID().toString(), t.ab, t.alice.userId(), t.alice.deviceIdOnServer(), null, kind = MessageKind.CALL),
        )
        assertEquals(1, t.bob.engine.sync())
        assertTrue(t.bob.store.getSyncCursor() > cursorBefore)
        assertTrue(w.signals.isEmpty())
        assertNoMessageEvents(w)
        assertTrue(t.bob.messages(t.ab).isEmpty(), "no UpdateRequired notice and no tombstone")
    }

    @Test
    fun `a call from an unlisted device is dropped`() = runBlocking<Unit> {
        val t = trio()
        t.alice.engine.sendCallSignal(t.ab, offer())
        val id = server.callMessages().single().msg.id
        val w = Watch(t.bob, scope)
        rewriteQueued(t.bob.deviceIdOnServer(), id) { it.copy(senderDeviceId = UUID.randomUUID().toString()) }
        t.bob.engine.sync()
        assertTrue(w.signals.isEmpty())
        assertTrue(t.bob.messages(t.ab).isEmpty())
    }

    @Test
    fun `sending is refused for groups and the server refuses what the client never sends`() = runBlocking<Unit> {
        val t = trio()
        val group = t.alice.engine.createGroup("g", listOf(t.bob.userId(), t.carol.userId()))
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendCallSignal(group.id, offer()) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendCallSignal("nope", offer()) }
        val api = server.connect(t.alice.deviceIdOnServer()).api
        val devices = listOf(t.bob.deviceIdOnServer(), t.carol.deviceIdOnServer())
        fun req(conv: String, target: String? = null, attachments: List<String> = emptyList()) = SendMessageRequest(
            UUID.randomUUID().toString(), conv, MessageKind.CALL, target, 1, attachments,
            envelopes = (if (conv == group.id) devices else listOf(t.bob.deviceIdOnServer())).map { se.familychat.core.model.Envelope(it, "whisper", "x") },
        )
        assertEquals("calls_direct_only", assertFailsWith<ApiException.Conflict> { api.sendMessage(req(group.id)) }.code)
        assertFailsWith<ApiException.Invalid> { api.sendMessage(req(t.ab, target = UUID.randomUUID().toString())) }
        assertFailsWith<ApiException.Invalid> { api.sendMessage(req(t.ab, attachments = listOf("a"))) }
        assertTrue(server.callMessages().isEmpty())
    }

    @Test
    fun `a call message never changes the conversation, its unread count or the history`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.engine.sendText(t.ab, "hello"); t.alice.engine.processOutbox()
        t.bob.engine.sync()
        val seqBefore = t.bob.store.getConversation(t.ab)!!.lastMessageSeq
        t.alice.engine.sendCallSignal(t.ab, offer())
        t.bob.engine.sync()
        assertEquals(seqBefore, t.bob.store.getConversation(t.ab)!!.lastMessageSeq)
        assertEquals(1, t.bob.store.unreadCount(t.ab))
        assertEquals(listOf(id), t.bob.messages(t.ab).map { it.id })
        assertEquals(1, server.messageCountInConversation(t.ab))
        // no delivered receipt for it either: alice's only receipt is for the text
        t.alice.engine.sync()
        assertEquals(listOf(id), t.alice.messages(t.ab).map { it.id })
    }

    @Test
    fun `a lost response is retried with the same message and ciphertexts and delivers once`() = runBlocking<Unit> {
        val t = trio()
        val w = Watch(t.bob, scope)
        server.loseSendResponses = 2
        t.alice.engine.sendCallSignal(t.ab, offer())
        assertEquals(1, server.callMessages().size)
        assertEquals(3, server.log.count { it == "send:${server.callMessages().single().msg.id}" })
        t.bob.engine.sync()
        assertEquals(1, w.signals.size)
        // the ratchet was advanced once for it, not three times: the next message decrypts without a gap
        val id = t.alice.engine.sendText(t.ab, "next"); t.alice.engine.processOutbox()
        t.bob.engine.sync()
        assertEquals("next", t.bob.store.getMessage(id)!!.text)
        assertNull(t.bob.store.getMessage(id)!!.decryptError)
    }

    @Test
    fun `a signal is retried at most twice and then fails`() = runBlocking<Unit> {
        val t = trio()
        server.failSends = 3
        assertFailsWith<IOException> { t.alice.engine.sendCallSignal(t.ab, offer()) }
        assertEquals(0, server.failSends)
        assertTrue(server.callMessages().isEmpty())
        assertTrue(t.alice.store.getOutbox().isEmpty(), "a signal is never queued for later")
        server.failSends = 2
        t.alice.engine.sendCallSignal(t.ab, offer())
        assertEquals(1, server.callMessages().size)
    }

    @Test
    fun `the 61st call message in a minute is refused with a rate limit`() = runBlocking<Unit> {
        val t = trio()
        val ice = listOf(IceCandidate("0", 0, "c"))
        repeat(60) { t.alice.engine.sendCallSignal(t.ab, CallSignal(UUID.randomUUID().toString(), CallAction.ICE, candidates = ice)) }
        assertFailsWith<ApiException.RateLimited> { t.alice.engine.sendCallSignal(t.ab, hangup()) }
    }

    @Test
    fun `turn credentials come from the server`() = runBlocking<Unit> {
        val t = trio()
        assertEquals(server.turn, t.alice.engine.fetchTurnCredentials())
        server.failTurn = true
        assertFailsWith<IOException> { t.alice.engine.fetchTurnCredentials() }
        assertEquals(2, server.turnFetches.get())
    }
}
