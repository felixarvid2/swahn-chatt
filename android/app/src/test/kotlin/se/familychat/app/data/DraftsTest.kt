package se.familychat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.DraftEntity
import se.familychat.app.data.db.RoomLocalStore
import se.familychat.core.model.Conversation
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Drafts live in the encrypted database, next to (not inside) the conversation rows that the sync rewrites. */
@RunWith(RobolectricTestRunner::class)
class DraftsTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomLocalStore

    @Before fun open() {
        db = RoomTestDb.file(ApplicationProvider.getApplicationContext<Context>(), "drafts.db")
        store = RoomLocalStore(db)
    }

    @After fun close() = db.close()

    private fun conversation(id: String) = Conversation(id = id, type = "direct", createdBy = "me")

    @Test fun `a draft is stored per conversation, replaced on change and listed for the conversation list`() = runTest {
        val dao = db.storeDao()
        dao.putDraft(DraftEntity("c1", "Hej", 1))
        dao.putDraft(DraftEntity("c2", "Annat", 2))
        dao.putDraft(DraftEntity("c1", "Hej på dig", 3))
        assertEquals("Hej på dig", dao.draft("c1"))
        assertEquals(mapOf("c1" to "Hej på dig", "c2" to "Annat"), db.uiDao().observeDrafts().first().associate { it.conversationId to it.text })
        dao.deleteDraft("c1")
        assertNull(dao.draft("c1"))
        assertEquals("Annat", dao.draft("c2"))
    }

    @Test fun `syncing a conversation does not touch its draft`() = runTest {
        store.upsertConversation(conversation("c1"))
        db.storeDao().putDraft(DraftEntity("c1", "Skriver …", 1))
        store.upsertConversation(conversation("c1").copy(name = "Nytt namn", updatedAt = 99))
        assertEquals("Skriver …", db.storeDao().draft("c1"))
    }

    @Test fun `leaving a conversation removes its draft and a logout wipes all drafts`() = runBlocking {
        store.upsertConversation(conversation("c1"))
        store.upsertConversation(conversation("c2"))
        db.storeDao().putDraft(DraftEntity("c1", "a", 1))
        db.storeDao().putDraft(DraftEntity("c2", "b", 1))
        store.removeConversation("c1")
        assertNull(db.storeDao().draft("c1"))
        assertEquals("b", db.storeDao().draft("c2"))
        store.clearAll()
        assertNull(db.storeDao().draft("c2"), "clearAll() is what logout calls: no typed text survives it")
    }
}
