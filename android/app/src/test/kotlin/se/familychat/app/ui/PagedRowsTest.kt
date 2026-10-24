package se.familychat.app.ui

import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import se.familychat.app.ui.model.ChatRow
import se.familychat.app.ui.model.ChatRowContext
import se.familychat.app.ui.model.buildChatRows
import se.familychat.app.ui.model.dateSeparatorBetween
import se.familychat.app.ui.model.isFirstOfRun
import se.familychat.app.ui.model.toChatRow
import se.familychat.app.ui.model.withSeparators
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.StoredMessage
import se.familychat.core.client.SystemEvent
import se.familychat.core.model.User
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conversation screen builds its rows page by page (newest first) instead of from one list. These tests pin that the
 * paged pipeline gives the same rows as [buildChatRows]: date separators, system notices, runs by one sender, and the
 * "unread" divider, whichever way the pages are cut.
 */
class PagedRowsTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 5, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val users = mapOf("me" to User("me", "anna", "Anna"), "bob" to User("bob", "bob", "Bob"), "cy" to User("cy", "cy", "Cy"))
    private val ctx = ChatRowContext("me", true, users, zone = zone)

    private fun m(id: String, from: String, ts: Long, seq: Long? = null) = StoredMessage(
        id = id, conversationId = "c", senderUserId = from, senderDeviceId = "d-$from", outgoing = from == "me",
        status = if (from == "me") MessageStatus.SENT else MessageStatus.RECEIVED, seq = seq, clientTs = ts, serverTs = seq?.let { ts },
        text = "text $id", replyTo = null,
    )

    private fun system(id: String, ts: Long) =
        m(id, "", ts).copy(senderUserId = "", senderDeviceId = null, systemEvent = SystemEvent.MemberAdded("cy"))

    /** Oldest first, across three days, with runs, a system notice and an own message. */
    private val messages = listOf(
        m("a", "bob", at(10, 9), 1), m("b", "bob", at(10, 10), 2), m("c", "me", at(10, 11), 3),
        m("d", "bob", at(11, 8), 4), system("sys", at(11, 9)), m("e", "bob", at(11, 10), 5),
        m("f", "cy", at(13, 23), 6), m("g", "cy", at(13, 23), 7),
    )

    private suspend fun paged(firstUnreadId: String? = null): List<ChatRow> {
        val newestFirst = messages.reversed().map { it.toChatRow(ctx) }
        return flowOf(PagingData.from(newestFirst).withSeparators(zone, firstUnreadId)).asSnapshot()
    }

    private fun shape(rows: List<ChatRow>) = rows.map {
        when (it) {
            is ChatRow.DateHeader -> "date:${it.dayStart}"
            is ChatRow.UnreadDivider -> "unread"
            is ChatRow.Message -> it.message.id
            is ChatRow.System -> it.id
        }
    }

    @Test fun `the paged rows equal the one-shot rows, read from the bottom`() = runTest {
        val oneShot = buildChatRows(messages, ctx)
        val rows = paged()
        assertEquals(shape(oneShot), shape(rows.reversed()))
        assertEquals(3 + messages.size, rows.size, "three days and every message")
    }

    @Test fun `runs by one sender start where the one-shot builder starts them`() = runTest {
        val rows = paged() // newest first: the row above a row (older) is the next index
        val flags = rows.mapIndexedNotNull { i, r ->
            (r as? ChatRow.Message)?.let { it.message.id to isFirstOfRun(it, rows.getOrNull(i + 1)) }
        }.toMap()
        val expected = buildChatRows(messages, ctx).filterIsInstance<ChatRow.Message>().associate { it.message.id to it.firstOfRun }
        assertEquals(expected, flags)
        assertEquals(false, flags["b"], "bob's second message continues the run")
        assertEquals(true, flags["d"], "a new day starts a run")
        assertEquals(true, flags["e"], "so does a system notice between")
    }

    @Test fun `the unread divider sits directly above the first unread message, under the date separator`() = runTest {
        val top = shape(paged(firstUnreadId = "d").reversed()) // top to bottom
        val at = top.indexOf("unread")
        assertEquals("d", top[at + 1])
        assertTrue(top[at - 1].startsWith("date:"), "the day's separator stays above the divider")
        assertEquals(1, top.count { it == "unread" })
        assertEquals(0, shape(paged(firstUnreadId = null)).count { it == "unread" })
        assertEquals(0, shape(paged(firstUnreadId = "not-loaded")).count { it == "unread" }, "an unknown id adds nothing")
    }

    @Test fun `a separator is between two rows only when the day changes, and ends the list`() {
        val old = m("o", "bob", at(10, 23)).toChatRow(ctx)
        val sameDay = m("p", "bob", at(10, 1)).toChatRow(ctx)
        val next = m("q", "bob", at(11, 0)).toChatRow(ctx)
        assertNull(dateSeparatorBetween(null, old, zone), "nothing above the newest message")
        assertNull(dateSeparatorBetween(sameDay.let { old }, sameDay, zone))
        assertEquals(at(11, 0) / 1, (dateSeparatorBetween(next, old, zone) as ChatRow.DateHeader).dayStart)
        assertEquals(at(10, 0), (dateSeparatorBetween(old, null, zone) as ChatRow.DateHeader).dayStart, "the oldest message gets its day")
    }

    @Test fun `midnight is the Stockholm one, not UTC`() {
        val late = ZonedDateTime.of(2026, 5, 10, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val early = ZonedDateTime.of(2026, 5, 11, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        val a = m("x", "bob", late).toChatRow(ctx)
        val b = m("y", "bob", early).toChatRow(ctx)
        assertTrue(dateSeparatorBetween(b, a, zone) is ChatRow.DateHeader)
    }
}
