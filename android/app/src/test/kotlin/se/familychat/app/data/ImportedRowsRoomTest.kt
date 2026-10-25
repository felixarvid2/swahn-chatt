package se.familychat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.RoomLocalStore
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.StoredMessage
import se.familychat.core.model.Conversation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** On the real Room queries: imported rows are not unread, do not move the read mark; the search index forgets purged words. */
@RunWith(RobolectricTestRunner::class)
class ImportedRowsRoomTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomLocalStore

    @Before fun open() {
        db = RoomTestDb.file(ApplicationProvider.getApplicationContext<Context>(), "imported-rows.db")
        store = RoomLocalStore(db)
    }

    @After fun close() = db.close()

    private fun msg(id: String, seq: Long, imported: Boolean = false, text: String = "t") = StoredMessage(
        id = id, conversationId = "c1", senderUserId = "bob", senderDeviceId = if (imported) null else "d", outgoing = false,
        status = MessageStatus.RECEIVED, seq = seq, clientTs = seq, serverTs = seq, text = text, replyTo = null, imported = imported,
    )

    @Test fun `imported rows are not unread, not the newest incoming, not the latest and not the first unread`() = runBlocking<Unit> {
        store.upsertConversation(Conversation("c1", "direct", null, createdBy = "me", lastMessageSeq = 3))
        store.upsertMessage(msg("old-imported", 2, imported = true))
        // what a file could claim: the importer clamps it, and the queries do not trust it either
        store.upsertMessage(msg("huge-imported", Long.MAX_VALUE, imported = true))
        assertEquals(0, store.unreadCount("c1"))
        assertEquals(0, db.storeDao().unreadCount("c1"))
        assertNull(db.uiDao().observeNewestIncomingSeq("c1").first())
        assertNull(db.uiDao().observeLatest("c1").first())
        assertEquals(0, db.uiDao().observeIncomingAfter("c1", 0).first())
        assertNull(db.uiDao().firstUnreadId("c1", 0))
        assertEquals(0, db.uiDao().unreadMessages("c1", 10).size)
        assertEquals(0, db.uiDao().observeOverview().first().single().unread)
        // real messages are counted and found as before
        store.upsertMessage(msg("real-3", 3))
        assertEquals(1, store.unreadCount("c1"))
        assertEquals(3L, db.uiDao().observeNewestIncomingSeq("c1").first())
        assertEquals("real-3", db.uiDao().observeLatest("c1").first()?.id)
        assertEquals(1, db.uiDao().observeIncomingAfter("c1", 0).first())
        assertEquals("real-3", db.uiDao().firstUnreadId("c1", 0))
        assertEquals(listOf("real-3"), db.uiDao().unreadMessages("c1", 10).map { it.id })
        assertEquals(1, db.uiDao().observeOverview().first().single().unread)
    }

    /** All bytes of the full-text index's own tables. */
    private fun indexBytes(): ByteArray {
        val raw = db.openHelper.readableDatabase
        val out = java.io.ByteArrayOutputStream()
        val tables = listOf("SELECT block FROM message_fts_segments", "SELECT root FROM message_fts_segdir")
        for (sql in tables) {
            raw.query(sql).use { c -> while (c.moveToNext()) c.getBlob(0)?.let(out::write) }
        }
        return out.toByteArray()
    }

    private fun contains(haystack: ByteArray, needle: String) = String(haystack, Charsets.ISO_8859_1).contains(needle)

    @Test fun `the words of purged messages leave the search index after it is compacted`() = runBlocking<Unit> {
        val word = "zebrakakaduva"
        store.upsertMessage(msg("keep", 1, text = "behåll den här"))
        store.upsertMessage(msg("gone", 2, text = "hemligt $word i texten"))
        assertTrue(contains(indexBytes(), word), "indexed")
        // removed for good, as an expiry does it: the row and its index entry are gone, the words are still in the segments
        store.deleteMessageLocally("gone", "c1", 1)
        assertEquals(emptyList(), SearchRepository(db).search(word).map { it.messageId })
        assertTrue(contains(indexBytes(), word), "a deleted document's terms stay in the segments until they are merged")
        store.compactSearchIndex()
        assertFalse(contains(indexBytes(), word), "merged: the word is gone from the index tables")
        assertEquals(listOf("keep"), SearchRepository(db).search("behåll").map { it.messageId }, "and the index still works")
        assertEquals(emptyList(), SearchRepository(db).search(word).map { it.messageId })
    }

    @Test fun `deleted pages are overwritten`() {
        db.openHelper.readableDatabase.query("PRAGMA secure_delete").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }
}
