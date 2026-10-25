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
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conversation list reads its messages through a Room `PagingSource` (newest first). On a real SQLite database with
 * 10,000 messages: the pages have the right size and order, nothing is skipped or repeated across pages, unsent messages
 * come first, other conversations never leak in, and one page costs milliseconds, not a scan of the whole history.
 */
@RunWith(RobolectricTestRunner::class)
class MessagePagingTest {
    private lateinit var db: AppDatabase
    private val dao get() = db.uiDao()

    @Before fun open() {
        db = RoomTestDb.file(ApplicationProvider.getApplicationContext<Context>(), "paging.db")
    }

    @After fun close() = db.close()

    private fun message(id: String, seq: Long?, ts: Long, conversation: String = "c1", outgoing: Boolean = false) = MessageEntity(
        id = id, conversationId = conversation, senderUserId = if (outgoing) "me" else "bob", senderDeviceId = "d", outgoing = outgoing,
        status = if (seq == null) "PENDING" else "RECEIVED", seq = seq, clientTs = ts, serverTs = seq?.let { ts }, text = "t $id", replyTo = null,
        attachments = "[]", edited = false, editedAt = null, deleted = false, decryptError = null, failureReason = null,
        deliveredTo = "", readBy = "", systemType = null, systemUserId = null, systemDeviceId = null, systemDeviceName = null,
        pendingEnvelopeType = null, pendingEnvelopeBody = null,
    )

    private fun fill(count: Int) {
        db.runInTransaction {
            for (i in 1..count) db.openHelper.writableDatabase.execSQL(
                "INSERT INTO messages (id, conversationId, senderUserId, senderDeviceId, outgoing, status, seq, clientTs, serverTs, text, replyTo, attachments, edited, editedAt, deleted, decryptError, failureReason, deliveredTo, readBy, systemType, systemUserId, systemDeviceId, systemDeviceName, pendingEnvelopeType, pendingEnvelopeBody) " +
                    "VALUES ('m${"%05d".format(i)}', 'c1', 'bob', 'd', 0, 'RECEIVED', $i, ${1000 + i}, ${1000 + i}, 't $i', NULL, '[]', 0, NULL, 0, NULL, NULL, '', '', NULL, NULL, NULL, NULL, NULL, NULL)",
            )
        }
    }

    private val config = PagingConfig(pageSize = 40, initialLoadSize = 80, prefetchDistance = 20, enablePlaceholders = false)

    @Test fun `10000 messages come as newest-first pages without gaps or repeats`() = runTest {
        fill(10_000)
        // an unrelated conversation must not appear
        db.openHelper.writableDatabase.execSQL("INSERT INTO conversations (id, type, name, avatarAttachmentId, createdBy, createdAt, updatedAt, lastMessageAt, lastMessageSeq, myReadSeq) VALUES ('c2', 'direct', NULL, NULL, 'me', 1, 1, 1, 1, 0)")
        db.storeDao().putMessage(message("other", 99_999, 999_999, conversation = "c2"))

        val pager = TestPager(config, dao.pagedMessages("c1"))
        val first = assertIs<PagingSource.LoadResult.Page<Int, MessageEntity>>(pager.refresh())
        assertEquals(80, first.data.size, "the initial page is initialLoadSize long")
        assertEquals("m10000", first.data.first().id, "the newest message is first")
        assertEquals("m09921", first.data.last().id)
        assertNull(first.prevKey, "nothing is newer than the first page")
        assertNotNull(first.nextKey)

        // walk down 20 pages: each continues exactly where the previous one ended
        val seen = ArrayList<String>(first.data.map { it.id })
        repeat(20) {
            val page = assertIs<PagingSource.LoadResult.Page<Int, MessageEntity>>(pager.append())
            assertEquals(40, page.data.size)
            seen += page.data.map { it.id }
        }
        assertEquals(80 + 20 * 40, seen.size)
        assertEquals(seen.size, seen.toSet().size, "no message twice")
        assertEquals((10_000 downTo 10_000 - seen.size + 1).map { "m${"%05d".format(it)}" }, seen, "strictly descending, no gap")
        assertTrue(seen.none { it == "other" })
    }

    @Test fun `the end of the history ends the paging and the last page is short`() = runTest {
        fill(95)
        val pager = TestPager(config, dao.pagedMessages("c1"))
        pager.refresh()
        val last = assertIs<PagingSource.LoadResult.Page<Int, MessageEntity>>(pager.append())
        assertEquals(15, last.data.size)
        assertNull(last.nextKey, "no older messages")
        assertEquals("m00001", last.data.last().id)
    }

    @Test fun `unsent messages are the newest and ties on the sequence number fall back to time then id`() = runTest {
        for (m in listOf(
            message("a", 1, 10), message("b", 2, 20), message("c", 2, 21), message("d", 2, 21),
            message("pending-1", null, 30, outgoing = true), message("pending-2", null, 31, outgoing = true),
        )) db.storeDao().putMessage(m)
        val page = assertIs<PagingSource.LoadResult.Page<Int, MessageEntity>>(TestPager(config, dao.pagedMessages("c1")).refresh())
        assertEquals(listOf("pending-2", "pending-1", "d", "c", "b", "a"), page.data.map { it.id })
        // the same order the older windowed query had (reversed): the screens must not disagree about "newest"
        assertEquals(page.data.reversed().map { it.id }, db.storeDao().messages("c1", 100, null).map { it.id })
    }

    @Test fun `a refresh from a key starts in the middle and can load newer messages`() = runTest {
        fill(500)
        val source = dao.pagedMessages("c1")
        val middle = assertIs<PagingSource.LoadResult.Page<Int, MessageEntity>>(
            source.load(PagingSource.LoadParams.Refresh(key = 200, loadSize = 80, placeholdersEnabled = false)),
        )
        assertEquals("m00300", middle.data.first().id, "offset 200 from the newest")
        assertEquals(200, middle.prevKey, "the 200 newer messages can still be loaded")
        val newer = assertIs<PagingSource.LoadResult.Page<Int, MessageEntity>>(
            source.load(PagingSource.LoadParams.Prepend(key = middle.prevKey!!, loadSize = 40, placeholdersEnabled = false)),
        )
        assertEquals(40, newer.data.size)
        assertEquals("m00340", newer.data.first().id)
        assertEquals("m00301", newer.data.last().id, "joins the middle page without a gap")
    }

    @Test fun `offsets of messages match their place in the pages`() = runTest {
        fill(300)
        assertEquals(0, dao.newerThan("c1", "m00300"))
        assertEquals(7, dao.newerThan("c1", "m00293"))
        assertEquals(299, dao.newerThan("c1", "m00001"))
        val source = dao.pagedMessages("c1")
        val offset = dao.newerThan("c1", "m00150")
        val page = assertIs<PagingSource.LoadResult.Page<Int, MessageEntity>>(
            source.load(PagingSource.LoadParams.Refresh(key = offset, loadSize = 10, placeholdersEnabled = false)),
        )
        assertEquals("m00150", page.data.first().id)
    }

    @Test fun `unread bookkeeping queries agree with the list counter`() = runTest {
        fill(10)
        db.openHelper.writableDatabase.execSQL("INSERT INTO conversations (id, type, name, avatarAttachmentId, createdBy, createdAt, updatedAt, lastMessageAt, lastMessageSeq, myReadSeq) VALUES ('c1', 'direct', NULL, NULL, 'me', 1, 1, 1, 10, 6)")
        assertEquals(4, db.storeDao().unreadCount("c1"))
        assertEquals("m00007", dao.firstUnreadId("c1", 6))
        assertNull(dao.firstUnreadId("c1", 10))
        assertEquals(10L, dao.maxSeq("c1"))
    }

    @Test fun `loading a page of a 10000 message conversation takes milliseconds`() = runTest {
        fill(10_000)
        val source = dao.pagedMessages("c1")
        val warm = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 80, placeholdersEnabled = false))
        assertIs<PagingSource.LoadResult.Page<Int, MessageEntity>>(warm)
        var worst = 0L
        for (offset in listOf(0, 40, 1_000, 5_000, 9_000, 9_960)) {
            val took = measureTimeMillis {
                assertIs<PagingSource.LoadResult.Page<Int, MessageEntity>>(
                    dao.pagedMessages("c1").load(PagingSource.LoadParams.Refresh(key = offset, loadSize = 40, placeholdersEnabled = false)),
                )
            }
            worst = maxOf(worst, took)
        }
        println("PAGING: worst page load of 40 rows out of 10000: ${worst} ms (Robolectric/JVM SQLite)")
        // generous bound for slow CI machines; a full-history load or a per-row query would be seconds
        assertTrue(worst < 1_500, "a page took ${worst} ms")
    }
}
