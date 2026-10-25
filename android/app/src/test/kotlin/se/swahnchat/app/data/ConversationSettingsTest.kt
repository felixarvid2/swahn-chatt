package se.swahnchat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.swahnchat.app.data.db.AppDatabase
import se.swahnchat.app.data.db.ConversationSettingsEntity
import se.swahnchat.app.data.db.RoomLocalStore
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.model.ConversationItemUi
import se.swahnchat.app.ui.model.PreviewKind
import se.swahnchat.app.ui.model.DeliveryUi
import se.swahnchat.app.ui.model.arrangeConversations
import se.swahnchat.core.client.MessageStatus
import se.swahnchat.core.client.StoredMessage
import se.swahnchat.core.model.Conversation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pin, mute and archive: local rules, stored in their own table so that the sync cannot overwrite them. */
@RunWith(RobolectricTestRunner::class)
class ConversationSettingsTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomLocalStore
    private lateinit var settings: ConversationSettingsStore
    private val now = 1_000_000L

    @Before fun open() = runBlocking<Unit> {
        db = RoomTestDb.file(ApplicationProvider.getApplicationContext<Context>(), "settings.db")
        store = RoomLocalStore(db)
        settings = ConversationSettingsStore(db)
        for (i in 1..7) store.upsertConversation(Conversation(id = "c$i", type = "direct", name = null, createdBy = "me", createdAt = 1, updatedAt = i.toLong()))
    }

    @After fun close() = db.close()

    private fun incoming(id: String, conversation: String, seq: Long, deleted: Boolean = false, outgoing: Boolean = false) = StoredMessage(
        id = id, conversationId = conversation, senderUserId = if (outgoing) "me" else "bob", senderDeviceId = "d", outgoing = outgoing,
        status = MessageStatus.RECEIVED, seq = seq, clientTs = seq, serverTs = seq, text = "hej", replyTo = null, attachments = emptyList(), deleted = deleted,
    )

    private suspend fun row(id: String) = db.uiDao().observeOverview().first().single { it.id == id }

    @Test fun `at most five conversations can be pinned`() = runBlocking<Unit> {
        for (i in 1..5) assertTrue(settings.pin("c$i", now + i))
        assertFalse(settings.pin("c6", now + 6), "the sixth is refused")
        assertNull(settings.settings("c6"))
        assertTrue(settings.pin("c3", now + 9), "pinning one that is pinned already is fine")
        assertEquals(now + 3, settings.settings("c3")!!.pinnedAt, "and keeps its place")
        settings.unpin("c1")
        assertTrue(settings.pin("c6", now + 6))
        assertEquals(setOf("c2", "c3", "c4", "c5", "c6"), db.uiDao().observeOverview().first().filter { it.pinnedAt != null }.map { it.id }.toSet())
    }

    @Test fun `archiving unpins and pinning an archived conversation brings it back`() = runBlocking<Unit> {
        settings.pin("c1", now)
        settings.archive("c1")
        assertEquals(ConversationSettingsEntity("c1", null, 0, true), settings.settings("c1"))
        settings.pin("c1", now)
        assertEquals(false, settings.settings("c1")!!.archived)
    }

    @Test fun `mute lasts for the chosen time and always means for good`() = runBlocking<Unit> {
        settings.mute("c1", MuteDuration.HOUR_1, now)
        assertEquals(now + 3_600_000L, settings.settings("c1")!!.mutedUntil)
        assertTrue(settings.isMuted("c1", now + 3_599_999))
        assertFalse(settings.isMuted("c1", now + 3_600_000))
        settings.mute("c1", MuteDuration.HOURS_8, now)
        assertEquals(now + 8 * 3_600_000L, settings.settings("c1")!!.mutedUntil)
        settings.mute("c1", MuteDuration.WEEK_1, now)
        assertEquals(now + 7 * 86_400_000L, settings.settings("c1")!!.mutedUntil)
        settings.mute("c1", MuteDuration.ALWAYS, now)
        assertTrue(settings.isMuted("c1", Long.MAX_VALUE - 1))
        settings.unmute("c1")
        assertFalse(settings.isMuted("c1", now))
        assertNull(settings.settings("c1"), "back to the defaults: no row")
    }

    @Test fun `a muted conversation keeps counting unread messages`() = runBlocking<Unit> {
        settings.mute("c1", MuteDuration.ALWAYS, now)
        store.upsertMessage(incoming("m1", "c1", 1))
        store.upsertMessage(incoming("m2", "c1", 2))
        val r = row("c1")
        assertEquals(2, r.unread)
        assertEquals(ConversationSettingsEntity.MUTED_ALWAYS, r.mutedUntil)
    }

    @Test fun `a new incoming message unarchives unless the conversation is muted`() = runBlocking<Unit> {
        settings.archive("c1")
        settings.archive("c2")
        settings.mute("c2", MuteDuration.ALWAYS, System.currentTimeMillis())
        settings.archive("c3")
        store.upsertMessage(incoming("m1", "c1", 1))
        store.upsertMessage(incoming("m2", "c2", 1))
        assertFalse(row("c1").archived, "unarchived by the new message")
        assertTrue(row("c2").archived, "muted: stays archived")
        // own messages, deleted ones and edits of old ones do not bring it back
        store.upsertMessage(incoming("m3", "c3", 1, outgoing = true))
        store.upsertMessage(incoming("m4", "c3", 2, deleted = true))
        assertTrue(row("c3").archived)
        store.upsertMessage(incoming("m5", "c3", 3))
        assertFalse(row("c3").archived)
        settings.archive("c3")
        store.updateMessage("m5") { it.copy(text = "ändrad", edited = true) }
        store.upsertMessage(incoming("m5", "c3", 3))
        assertTrue(row("c3").archived, "the same message again is not a new one")
    }

    @Test fun `an expired mute no longer keeps a conversation archived`() = runBlocking<Unit> {
        settings.archive("c1")
        settings.mute("c1", MuteDuration.HOUR_1, System.currentTimeMillis() - 2 * 3_600_000L)
        store.upsertMessage(incoming("m1", "c1", 1))
        assertFalse(row("c1").archived)
    }

    @Test fun `settings survive a sync of the conversation and go with the conversation`() = runBlocking<Unit> {
        settings.pin("c1", now)
        settings.mute("c1", MuteDuration.ALWAYS, now)
        store.upsertConversation(Conversation(id = "c1", type = "direct", name = "ny", createdBy = "me", createdAt = 1, updatedAt = 99)) // what a sync does
        assertEquals(now, settings.settings("c1")!!.pinnedAt)
        store.removeConversation("c1")
        assertNull(settings.settings("c1"))
        assertFalse(settings.pin("nope", now).not(), "settings of an unknown conversation are not stored")
        assertNull(settings.settings("nope"))
    }

    @Test fun `the list puts pinned first and archived apart, each in activity order`() {
        fun item(id: String, pinned: Boolean = false, archived: Boolean = false, unread: Int = 0) = ConversationItemUi(
            id, id, false, AvatarUi(id), PreviewKind.NONE, null, null, false, DeliveryUi.NONE, 0, unread, pinned = pinned, archived = archived,
        )
        val s = arrangeConversations(listOf(item("a"), item("b", pinned = true), item("c", archived = true, unread = 2), item("d"), item("e", pinned = true), item("f", archived = true, unread = 1)))
        assertEquals(listOf("b", "e", "a", "d"), s.main.map { it.id })
        assertEquals(listOf("c", "f"), s.archived.map { it.id })
        assertEquals(3, s.archivedUnread)
    }
}
