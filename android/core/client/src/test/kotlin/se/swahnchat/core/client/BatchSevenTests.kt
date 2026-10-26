package se.swahnchat.core.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import se.swahnchat.core.network.RealtimeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Privacy switches of batch 7: silent reads, no typing events, opt-in online status. */
class ReadReceiptsTest : FeatureTestBase() {
    @Test
    fun `by default a read is announced and the sender sees it`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.send(t.ab, "hej")
        t.bob.engine.sync()
        t.bob.engine.markRead(t.ab)
        t.alice.engine.sync()
        assertEquals(MessageStatus.READ, t.alice.store.getMessage(id)!!.status)
        assertTrue(server.log.any { it.startsWith("read:${t.ab}:") && !it.endsWith(":silent") })
    }

    @Test
    fun `with read receipts off the read position moves but the sender gets no receipt`() = runBlocking<Unit> {
        val t = trio()
        t.bob.engine.sendReadReceipts = false
        val id = t.alice.send(t.ab, "hej")
        t.bob.engine.sync()
        t.bob.engine.markRead(t.ab)
        t.alice.engine.sync()
        assertEquals(0, t.bob.store.unreadCount(t.ab)) // bob's own state moved
        assertEquals(MessageStatus.DELIVERED, t.alice.store.getMessage(id)!!.status) // alice was told nothing
        assertTrue(server.log.any { it == "read:${t.ab}:${t.bob.store.getConversation(t.ab)!!.myReadSeq}:silent" })
        // reciprocity: bob still sees alice's receipts
        val reply = t.bob.send(t.ab, "svar")
        t.alice.engine.sync()
        t.alice.engine.markRead(t.ab)
        t.bob.engine.sync()
        assertEquals(MessageStatus.READ, t.bob.store.getMessage(reply)!!.status)
    }

    @Test
    fun `switching receipts on again announces the next read, including what was read silently before`() = runBlocking<Unit> {
        val t = trio()
        t.bob.engine.sendReadReceipts = false
        val first = t.alice.send(t.ab, "ett")
        t.bob.engine.sync(); t.bob.engine.markRead(t.ab)
        t.bob.engine.sendReadReceipts = true
        val second = t.alice.send(t.ab, "två")
        t.bob.engine.sync(); t.bob.engine.markRead(t.ab)
        t.alice.engine.sync()
        assertEquals(MessageStatus.READ, t.alice.store.getMessage(second)!!.status)
        // the read pointer is a position: it now covers the first message too
        assertEquals(MessageStatus.READ, t.alice.store.getMessage(first)!!.status)
    }

    @Test
    fun `a silent read that could not be sent stays silent when it is retried`() = runBlocking<Unit> {
        val t = trio()
        t.bob.engine.sendReadReceipts = false
        val id = t.alice.send(t.ab, "hej")
        t.bob.engine.sync()
        server.failReads = 1
        t.bob.engine.markRead(t.ab) // fails: queued as pending
        t.bob.engine.sendReadReceipts = true // the setting changes meanwhile
        t.bob.engine.processOutbox() // flushes the pending read
        t.alice.engine.sync()
        assertEquals(MessageStatus.DELIVERED, t.alice.store.getMessage(id)!!.status)
        assertTrue(server.log.any { it.startsWith("read:${t.ab}:") && it.endsWith(":silent") })
    }
}

class TypingSwitchTest : FeatureTestBase() {
    private suspend fun connected(a: TestClient, b: TestClient) {
        a.engine.startRealtime(); b.engine.startRealtime()
        eventually(what = "connected") {
            a.engine.realtimeState.value == RealtimeState.CONNECTED && b.engine.realtimeState.value == RealtimeState.CONNECTED
        }
    }

    @Test
    fun `with typing off nothing reaches the other person, and it works again when switched on`() = runBlocking<Unit> {
        val t = trio()
        connected(t.alice, t.bob)
        t.alice.engine.sendTypingIndicators = false
        t.alice.engine.sendTyping(t.ab, true)
        delay(300)
        assertTrue(t.bob.engine.typing.value.isEmpty())
        t.alice.engine.sendTypingIndicators = true
        t.alice.engine.sendTyping(t.ab, true)
        eventually(what = "typing shown") { t.bob.engine.typing.value[t.ab]?.contains(t.alice.userId()) == true }
        t.alice.engine.stopRealtime(); t.bob.engine.stopRealtime()
    }
}

class PresenceTest : FeatureTestBase() {
    @Test
    fun `presence is off by default and nobody who does not share shows up`() = runBlocking<Unit> {
        val t = trio()
        assertFalse(t.alice.engine.sharePresence())
        server.setOnline("alice", true)
        t.bob.engine.refreshPresence()
        assertTrue(t.bob.engine.presence.value.isEmpty())
    }

    @Test
    fun `a person who shares shows as online and then as last seen`() = runBlocking<Unit> {
        val t = trio()
        assertTrue(t.alice.engine.setSharePresence(true))
        assertTrue(t.alice.engine.sharePresence())
        server.setOnline("alice", true)
        t.bob.engine.refreshPresence()
        assertEquals(true, t.bob.engine.presence.value[t.alice.userId()]?.online)
        server.setOnline("alice", false, lastSeenAt = 1_699_999_000_000L)
        t.bob.engine.refreshPresence()
        val p = t.bob.engine.presence.value.getValue(t.alice.userId())
        assertFalse(p.online)
        assertEquals(1_699_999_000_000L, p.lastSeenAt)
        assertNull(t.bob.engine.presence.value[t.carol.userId()]) // carol does not share
        assertNull(t.alice.engine.presence.value[t.alice.userId()]) // never about yourself
    }

    @Test
    fun `switching sharing off removes the person again and the server forgets the last seen time`() = runBlocking<Unit> {
        val t = trio()
        t.alice.engine.setSharePresence(true)
        server.setOnline("alice", false, lastSeenAt = 1_699_999_000_000L)
        t.bob.engine.refreshPresence()
        assertTrue(t.alice.userId() in t.bob.engine.presence.value)
        assertFalse(t.alice.engine.setSharePresence(false))
        t.bob.engine.refreshPresence()
        assertFalse(t.alice.userId() in t.bob.engine.presence.value)
        t.alice.engine.setSharePresence(true)
        t.bob.engine.refreshPresence()
        assertNull(t.bob.engine.presence.value.getValue(t.alice.userId()).lastSeenAt.takeIf { it == 1_699_999_000_000L })
    }
}
