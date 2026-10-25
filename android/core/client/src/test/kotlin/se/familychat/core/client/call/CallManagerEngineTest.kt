package se.familychat.core.client.call

import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import se.familychat.core.client.FakeServer
import se.familychat.core.client.TestClient
import se.familychat.core.client.eventually
import se.familychat.core.client.newScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [CallManager] on two real engines (crypto, sync, the fake server) with fake media: the signalling path end to end. */
class CallManagerEngineTest {
    private val server = FakeServer()
    private val scope = newScope()

    @AfterTest fun tearDown() = scope.cancel()

    private class Side(val client: TestClient, scope: kotlinx.coroutines.CoroutineScope) {
        val media = FakeMedia()
        val manager = CallManager(client.engine, media, scope).also { it.start() }
        val state get() = manager.state.value

        /** The phone syncs (a push, or the realtime hint, in the app) until [what] holds. */
        suspend fun until(what: String, cond: () -> Boolean) = eventually(6_000, what) { client.engine.sync(); cond() }
    }

    @Test
    fun `a call over two engines rings, connects and ends`() = runBlocking<Unit> {
        val alice = TestClient(server, "alice", scope).also { it.signUp() }
        val bob = TestClient(server, "bob", scope).also { it.signUp() }
        alice.engine.refreshDirectory(); bob.engine.refreshDirectory()
        val conv = alice.engine.createDirect(bob.userId()).id
        bob.engine.sync()
        val a = Side(alice, scope); val b = Side(bob, scope)

        val id = a.manager.startCall(conv, video = false)
        b.until("bob rings") { b.state is CallState.Incoming }
        assertEquals(CallState.Incoming(id, conv, alice.userId(), false), b.state)
        assertEquals(1, a.media.sessions.size)

        b.manager.accept()
        a.until("alice is answered") { a.state is CallState.Connecting }
        a.media.last.candidate(1)
        b.until("bob has the candidate") { b.media.last.remoteCandidates.isNotEmpty() }
        a.media.last.state(MediaConnectionState.CONNECTED)
        b.media.last.state(MediaConnectionState.CONNECTED)
        eventually(2_000, "both connected") { a.state is CallState.Connected && b.state is CallState.Connected }
        assertEquals(listOf(FAKE_SDP_PREFIX + "answer"), a.media.last.acceptedAnswers)

        a.manager.hangup()
        b.until("bob sees the hangup") { b.state is CallState.Ended }
        assertEquals(EndReason.HANGUP_REMOTE, (b.state as CallState.Ended).reason)
        assertEquals(1, a.media.last.closed)
        assertEquals(1, b.media.last.closed)
        assertTrue(server.callMessages().isNotEmpty())
        assertTrue(alice.store.getOutbox().isEmpty() && bob.messages(conv).isEmpty())
        assertFailsWith<IllegalArgumentException> { a.manager.startCall("nope", video = false) }
    }
}
