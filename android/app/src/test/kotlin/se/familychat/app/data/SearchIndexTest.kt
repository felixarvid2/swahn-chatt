package se.familychat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import kotlin.test.assertTrue

/** The full-text index: what is found, and that it follows every edit, delete and removal of a message. */
@RunWith(RobolectricTestRunner::class)
class SearchIndexTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomLocalStore
    private lateinit var search: SearchRepository

    @Before fun open() {
        db = RoomTestDb.file(ApplicationProvider.getApplicationContext<Context>(), "search.db")
        store = RoomLocalStore(db)
        search = SearchRepository(db)
    }

    @After fun close() = db.close()

    private fun msg(id: String, text: String?, conversation: String = "c1", seq: Long = 1, outgoing: Boolean = false, deleted: Boolean = false) = StoredMessage(
        id = id, conversationId = conversation, senderUserId = if (outgoing) "me" else "bob", senderDeviceId = "d", outgoing = outgoing,
        status = MessageStatus.RECEIVED, seq = seq, clientTs = seq * 1000, serverTs = seq * 1000, text = text, replyTo = null, attachments = emptyList(),
        deleted = deleted,
    )

    private fun ids(q: String, conversation: String? = null) = runBlocking { search.search(q, conversation).map { it.messageId } }

    @Test fun `the unicode tokenizer is available and folds Swedish letters`() = runBlocking<Unit> {
        store.upsertMessage(msg("m1", "Hej Åsa, vi ses på Östermalm i övermorgon"))
        assertEquals(listOf("m1"), ids("åsa"))
        assertEquals(listOf("m1"), ids("ÅSA"))
        assertEquals(listOf("m1"), ids("östermalm"))
        assertEquals(listOf("m1"), ids("ÖVER"), "prefix of a word")
        assertEquals(emptyList(), ids("asa"), "diacritics are kept: a is not å")
    }

    @Test fun `all words must occur and a word matches by its beginning`() = runBlocking<Unit> {
        store.upsertMessage(msg("m1", "Middag hos farmor på söndag", seq = 1))
        store.upsertMessage(msg("m2", "Middag på lördag", seq = 2))
        assertEquals(listOf("m2", "m1"), ids("middag"), "newest first")
        assertEquals(listOf("m1"), ids("middag farm"))
        assertEquals(emptyList(), ids("middag taxi"))
        assertEquals(emptyList(), ids("iddag"), "not in the middle of a word")
    }

    @Test fun `input that is syntax for the index is only text`() = runBlocking<Unit> {
        store.upsertMessage(msg("m1", "OR NOT NEAR \"citat\" e-post"))
        for (q in listOf("OR", "NOT", "NEAR/2", "\"", "\"citat", "citat\"", "-citat", "(", "*", "a AND", "e-post", ":", "text:citat")) {
            ids(q) // must not throw
        }
        assertEquals(listOf("m1"), ids("\"citat\""))
        assertEquals(listOf("m1"), ids("e-post"))
        assertEquals(emptyList(), ids("   "))
        assertEquals(emptyList(), ids("!!!"))
        assertEquals(null, SearchQuery.toMatch("?!"))
        assertEquals("\"Hej*\" \"p*\"", SearchQuery.toMatch(" Hej, \"p\" "))
    }

    @Test fun `a search can be limited to one conversation`() = runBlocking<Unit> {
        store.upsertMessage(msg("m1", "gemensam text", conversation = "c1"))
        store.upsertMessage(msg("m2", "gemensam text", conversation = "c2", seq = 2))
        assertEquals(listOf("m2", "m1"), ids("gemensam"))
        assertEquals(listOf("m2"), ids("gemensam", "c2"))
    }

    @Test fun `an edit replaces what is found`() = runBlocking<Unit> {
        store.upsertMessage(msg("m1", "vi ses klockan fem"))
        assertEquals(listOf("m1"), ids("fem"))
        // the same id again with another text, as the engine writes an edit (SendPipeline / SyncProcessor)
        store.updateMessage("m1") { it.copy(text = "vi ses klockan sex", edited = true) }
        assertEquals(emptyList(), ids("fem"), "the old text is gone from the index")
        assertEquals(listOf("m1"), ids("sex"))
        store.upsertMessage(msg("m1", "tre"))
        assertEquals(emptyList(), ids("sex"))
        assertEquals(listOf("m1"), ids("tre"))
    }

    @Test fun `a deleted message is no longer found`() = runBlocking<Unit> {
        store.upsertMessage(msg("m1", "hemligt"))
        // a delete clears the text and sets the flag (SyncProcessor / SendPipeline)
        store.updateMessage("m1") { it.copy(text = null, deleted = true) }
        assertEquals(emptyList(), ids("hemligt"))
        // even a tombstone that still carried its text would not be a hit
        store.upsertMessage(msg("m2", "kvarglömd", deleted = true))
        assertEquals(emptyList(), ids("kvarglömd"))
    }

    @Test fun `system notices and messages without text are not hits`() = runBlocking<Unit> {
        store.upsertMessage(msg("m1", null))
        store.upsertMessage(msg("m2", "bild").copy(systemEvent = se.familychat.core.client.SystemEvent.MemberAdded("bob")))
        assertEquals(emptyList(), ids("bild"))
    }

    @Test fun `removing a conversation removes its messages from the index`() = runBlocking<Unit> {
        store.upsertConversation(Conversation(id = "c1", type = "direct", name = null, createdBy = "me", createdAt = 1, updatedAt = 1))
        store.upsertMessage(msg("m1", "försvinner"))
        store.removeConversation("c1")
        assertEquals(emptyList(), ids("försvinner"))
    }

    @Test fun `logging out leaves no tokens behind in the index`() = runBlocking<Unit> {
        store.upsertMessage(msg("m1", "känsligt innehåll"))
        assertEquals(listOf("m1"), ids("känsligt"))
        store.clearAll()
        assertEquals(emptyList(), ids("känsligt"))
        // the index tables themselves are empty, not just unreachable through the messages
        for (shadow in listOf("message_fts_segments", "message_fts_segdir", "message_fts_docsize", "message_fts_stat")) {
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $shadow").use { c ->
                assertTrue(c.moveToFirst())
                if (shadow != "message_fts_stat") assertEquals(0L, c.getLong(0), shadow)
            }
        }
    }

    @Test fun `snippets show the context of the first hit and mark every matching word start`() {
        val long = "Det här är en ganska lång text som går på i flera meningar innan vi till slut kommer fram till att middag hos farmor blir bäst, middag igen"
        val s = Snippets.of(long, SearchQuery.terms("middag"), maxLength = 40, lead = 20)
        assertTrue(s.text.startsWith("…"), s.text)
        assertTrue(s.text.endsWith("…"), s.text)
        assertTrue(s.highlights.isNotEmpty())
        for (r in s.highlights) assertEquals("middag", s.text.substring(r.first, r.last + 1).lowercase())
        val short = Snippets.of("Hej Åsa!\nVi ses", SearchQuery.terms("åsa vi"))
        assertEquals("Hej Åsa! Vi ses", short.text)
        assertEquals(listOf("Åsa", "Vi"), short.highlights.map { short.text.substring(it.first, it.last + 1) })
        val prefix = Snippets.of("Anna ringde", SearchQuery.terms("ann"))
        assertEquals(listOf("Ann"), prefix.highlights.map { prefix.text.substring(it.first, it.last + 1) })
    }
}
