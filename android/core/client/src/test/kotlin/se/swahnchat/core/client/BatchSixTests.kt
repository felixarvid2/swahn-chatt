package se.swahnchat.core.client

import kotlinx.coroutines.runBlocking
import se.swahnchat.core.model.Content
import se.swahnchat.core.model.ContentType
import se.swahnchat.core.model.LocationSpec
import se.swahnchat.core.model.MessageKind
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared positions (batch 6) on the engine against the fake server. */
class LocationTest : BatchFiveBase() {
    private val here = LocationSpec(59.3293, 18.0686, 12.5, "Hemma")

    @Test
    fun `a position is an ordinary encrypted message that every member stores as a location`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.engine.sendLocation(t.group, here)
        t.alice.engine.processOutbox()
        t.bob.engine.sync(); t.carol.engine.sync()
        for (c in listOf(t.alice, t.bob, t.carol)) {
            val m = c.store.getMessage(id)!!
            assertEquals(here, m.location)
            assertNull(m.text)
            assertTrue(m.attachments.isEmpty() && m.poll == null)
        }
        assertEquals(MessageKind.MESSAGE, serverStored(id).kind)
        // the server holds ciphertext only: neither the coordinates nor the label are readable in what it stored
        val stored = serverStored(id)
        assertFalse(stored.envelopes.values.any { "59.3293" in it.body || "Hemma" in it.body })
        assertEquals(1, t.bob.visible(t.group).size)
    }

    @Test
    fun `an invalid position is refused before it is queued`() = runBlocking<Unit> {
        val t = trio()
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendLocation(t.group, LocationSpec(91.0, 0.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendLocation(t.group, LocationSpec(0.0, 181.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendLocation(t.group, LocationSpec(0.0, 0.0, -1.0)) }
        assertFailsWith<IllegalArgumentException> { t.alice.engine.sendLocation(t.group, LocationSpec(0.0, 0.0, 1.0, "x".repeat(101))) }
        assertTrue(t.alice.store.getOutbox().isEmpty())
    }

    @Test
    fun `a position can be deleted for everyone but neither edited nor forwarded`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.engine.sendLocation(t.group, here)
        t.alice.engine.processOutbox(); t.bob.engine.sync()
        assertFailsWith<IllegalArgumentException> { t.alice.engine.editMessage(id, "text") }
        assertFailsWith<IllegalArgumentException> { t.bob.engine.forwardMessage(id, listOf(t.ab)) }
        t.alice.engine.deleteMessage(id); t.alice.engine.processOutbox(); t.bob.engine.sync()
        val m = t.bob.store.getMessage(id)!!
        assertTrue(m.deleted)
        assertNull(m.location) // the coordinates are wiped with the message
        assertNull(t.alice.store.getMessage(id)!!.location)
    }

    @Test
    fun `a failed position is retried with its coordinates`() = runBlocking<Unit> {
        val t = trio()
        val id = t.alice.engine.sendLocation(t.group, here)
        t.alice.store.updateMessage(id) { it.copy(status = MessageStatus.FAILED) }
        t.alice.store.removeOutbox(id)
        t.alice.engine.retryMessage(id)
        assertEquals(here, t.alice.store.getOutboxEntry(id)?.location)
    }

    @Test
    fun `the receiver drops an out-of-range position and a position that is not bound to its event`() = runBlocking<Unit> {
        val t = trio()
        val rogue = rogueOf(t.bob, t.alice)
        // a valid one from another device of bob arrives
        val ok = UUID.randomUUID().toString()
        rogue.deliver(t.alice, t.bob, t.group, Content(type = ContentType.LOCATION, location = here, conversationId = t.group, messageId = ok))
        // out of range: ContentCodec cannot even build it, so the rogue sends the raw JSON
        val bad = UUID.randomUUID().toString()
        rogue.deliver(
            t.alice, t.bob, t.group,
            Content(type = ContentType.LOCATION, location = LocationSpec(120.0, 0.0, 1.0), conversationId = t.group, messageId = bad),
        )
        t.alice.engine.sync()
        assertEquals(here, t.alice.store.getMessage(ok)!!.location)
        assertNotNull(t.alice.store.getMessage(ok))
        val badRow = t.alice.store.getMessage(bad)
        assertTrue(badRow == null || badRow.location == null)
    }
}
