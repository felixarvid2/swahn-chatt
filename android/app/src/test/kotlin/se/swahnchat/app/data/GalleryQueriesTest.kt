package se.swahnchat.app.data

import android.content.Context
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.swahnchat.app.data.db.AppDatabase
import se.swahnchat.app.data.db.MessageEntity
import se.swahnchat.app.data.db.RoomLocalStore
import se.swahnchat.core.client.DownloadState
import se.swahnchat.core.client.LocalAttachment
import se.swahnchat.core.client.MessageStatus
import se.swahnchat.core.client.StoredMessage
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What "Media och filer" reads: photos and videos, files, and messages with web addresses of ONE conversation, newest first. */
@RunWith(RobolectricTestRunner::class)
class GalleryQueriesTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomLocalStore
    private val ui get() = db.uiDao()

    @Before fun open() {
        db = RoomTestDb.file(ApplicationProvider.getApplicationContext<Context>(), "gallery.db")
        store = RoomLocalStore(db)
    }

    @After fun close() = db.close()

    private fun att(id: String, kind: String, name: String? = null) =
        LocalAttachment(id, "k", "s", 10, "x/y", name, kind, 10, 10, null, "dGh1bWI=", null, DownloadState.NONE)

    private suspend fun put(
        id: String, seq: Long, vararg attachments: LocalAttachment, text: String? = null, conv: String = "c1", deleted: Boolean = false,
        scheduledAt: Long? = null,
    ) = store.upsertMessage(
        StoredMessage(
            id, conv, "bob", "d", false, MessageStatus.RECEIVED, seq, 1000 + seq, 1000 + seq, text, null, attachments.toList(),
            deleted = deleted, scheduledAt = scheduledAt,
        ),
    )

    private suspend fun ids(source: PagingSource<Int, MessageEntity>, size: Int = 50): List<String> {
        val page = TestPager(PagingConfig(pageSize = size, enablePlaceholders = false), source).refresh()
        return assertIs<PagingSource.LoadResult.Page<Int, MessageEntity>>(page).data.map { it.id }
    }

    @Test fun `photos and videos of the conversation come newest first and nothing else does`() = runTest {
        put("old", 1, att("a1", "image"))
        put("doc", 2, att("f1", "file", "x.pdf"))
        put("voice", 3, att("v1", "voice"))
        put("mixed", 4, att("a2", "image"), att("f2", "file", "y.pdf"), text = "kolla")
        put("video", 5, att("a3", "video"))
        put("text", 6, text = "hej")
        put("other", 7, att("a4", "image"), conv = "c2")
        assertEquals(listOf("video", "mixed", "old"), ids(ui.pagedMediaMessages("c1")))
        assertEquals(listOf("mixed", "doc"), ids(ui.pagedFileMessages("c1")))
    }

    @Test fun `a deleted, scheduled or expired message leaves the lists`() = runTest {
        put("keep", 1, att("a1", "image"))
        put("gone", 2, att("a2", "image"), deleted = true)
        put("later", 3, att("a3", "image"), scheduledAt = 9_999)
        assertEquals(listOf("keep"), ids(ui.pagedMediaMessages("c1")))
        // a message that is deleted for me, or that expires, is removed as a row: the paging source would be invalidated and reads again
        store.deleteMessageLocally("keep", "c1", 5)
        assertEquals(emptyList(), ids(ui.pagedMediaMessages("c1")))
    }

    @Test fun `messages with an address in the text are the link candidates`() = runTest {
        put("a", 1, text = "titta https://exempel.se/a")
        put("b", 2, text = "inget här")
        put("c", 3, text = "http://b.se och mer", conv = "c2")
        put("d", 4, text = "ny https://c.se", deleted = true)
        put("e", 5, text = "https://d.se")
        assertEquals(listOf("e", "a"), ids(ui.pagedLinkMessages("c1")))
    }

    @Test fun `a large chat is read in pages without gaps or repeats`() = runTest {
        for (i in 1..300) put("m%03d".format(i), i.toLong(), att("a$i", if (i % 3 == 0) "video" else "image"))
        val source = ui.pagedMediaMessages("c1")
        val pager = TestPager(PagingConfig(pageSize = 40, initialLoadSize = 80, enablePlaceholders = false), source)
        val seen = ArrayList<String>()
        seen += assertIs<PagingSource.LoadResult.Page<Int, MessageEntity>>(pager.refresh()).data.map { it.id }
        repeat(10) {
            val r = pager.append()
            if (r is PagingSource.LoadResult.Page<Int, MessageEntity>) seen += r.data.map { it.id }
        }
        assertEquals(300, seen.size)
        assertEquals(300, seen.toSet().size)
        assertEquals("m300", seen.first())
        assertEquals("m001", seen.last())
        assertTrue(ui.mediaMessages("c1").size == 300)
    }
}
