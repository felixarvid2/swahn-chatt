package se.swahnchat.app.data

import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The quiet-hours window: plain, over midnight, per day, and across the daylight saving changes of 2026 (Europe/Stockholm). */
class QuietHoursTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private val night = QuietHoursSettings(enabled = true) // 22:00-07:00, every day

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: ZoneId = stockholm) = ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone)

    @Test fun `off means never quiet`() {
        assertFalse(QuietHours.isActive(night.copy(enabled = false), at(2026, 10, 20, 23)))
    }

    @Test fun `a window over midnight covers evening and early morning but not the day`() {
        assertTrue(QuietHours.isActive(night, at(2026, 10, 20, 22, 0)))
        assertTrue(QuietHours.isActive(night, at(2026, 10, 20, 23, 59)))
        assertTrue(QuietHours.isActive(night, at(2026, 10, 21, 0, 0)))
        assertTrue(QuietHours.isActive(night, at(2026, 10, 21, 6, 59)))
        assertFalse(QuietHours.isActive(night, at(2026, 10, 21, 7, 0)), "the end is exclusive")
        assertFalse(QuietHours.isActive(night, at(2026, 10, 21, 12, 0)))
        assertFalse(QuietHours.isActive(night, at(2026, 10, 20, 21, 59)))
    }

    @Test fun `a window inside one day`() {
        val lunch = QuietHoursSettings(enabled = true, startMinutes = 12 * 60, endMinutes = 13 * 60 + 30)
        assertFalse(QuietHours.isActive(lunch, at(2026, 10, 20, 11, 59)))
        assertTrue(QuietHours.isActive(lunch, at(2026, 10, 20, 12, 0)))
        assertTrue(QuietHours.isActive(lunch, at(2026, 10, 20, 13, 29)))
        assertFalse(QuietHours.isActive(lunch, at(2026, 10, 20, 13, 30)))
    }

    @Test fun `the morning part of a night belongs to the day the night started`() {
        // only Friday: Friday 23:00 and Saturday 06:00 are quiet; Saturday 23:00 and Friday 06:00 are not
        val friday = night.copy(days = setOf(DayOfWeek.FRIDAY))
        assertTrue(QuietHours.isActive(friday, at(2026, 10, 23, 23))) // Friday evening
        assertTrue(QuietHours.isActive(friday, at(2026, 10, 24, 6))) // Saturday morning
        assertFalse(QuietHours.isActive(friday, at(2026, 10, 24, 23))) // Saturday evening
        assertFalse(QuietHours.isActive(friday, at(2026, 10, 23, 6))) // Friday morning belongs to Thursday night
        assertEquals(DayOfWeek.FRIDAY, at(2026, 10, 23, 23).dayOfWeek)
    }

    @Test fun `sunday night continues into monday`() {
        val sunday = night.copy(days = setOf(DayOfWeek.SUNDAY))
        assertTrue(QuietHours.isActive(sunday, at(2026, 10, 25, 23))) // Sunday
        assertTrue(QuietHours.isActive(sunday, at(2026, 10, 26, 5))) // Monday morning wraps around the week
    }

    @Test fun `no days and an empty window are never quiet`() {
        assertFalse(QuietHours.isActive(night.copy(days = emptySet()), at(2026, 10, 20, 23)))
        assertFalse(QuietHours.isActive(night.copy(startMinutes = 600, endMinutes = 600), at(2026, 10, 20, 10)))
    }

    @Test fun `spring forward the window follows the wall clock - 29 March 2026, 02 00 becomes 03 00`() {
        // the night of the change is one hour shorter in real time, and still ends at 07:00 on the clock
        val before = at(2026, 3, 29, 1, 59)
        val after = ZonedDateTime.of(LocalDateTime.of(2026, 3, 29, 2, 30), stockholm) // does not exist: resolved to 03:30
        assertEquals(3, after.hour)
        assertTrue(QuietHours.isActive(night, before))
        assertTrue(QuietHours.isActive(night, after))
        assertTrue(QuietHours.isActive(night, at(2026, 3, 29, 6, 59)))
        assertFalse(QuietHours.isActive(night, at(2026, 3, 29, 7, 0)))
        // a window that starts in the missing hour begins at the first minute that exists, never stays off
        val small = QuietHoursSettings(enabled = true, startMinutes = 2 * 60 + 30, endMinutes = 3 * 60 + 30)
        assertTrue(QuietHours.isActive(small, ZonedDateTime.of(LocalDateTime.of(2026, 3, 29, 3, 0), stockholm)))
        assertFalse(QuietHours.isActive(small, ZonedDateTime.of(LocalDateTime.of(2026, 3, 29, 3, 30), stockholm)))
    }

    @Test fun `fall back the repeated hour is quiet both times - 25 October 2026, 03 00 becomes 02 00`() {
        val first = at(2026, 10, 25, 2, 30).withEarlierOffsetAtOverlap()
        val second = at(2026, 10, 25, 2, 30).withLaterOffsetAtOverlap()
        assertTrue(first.offset != second.offset, "the hour 02:00-03:00 happens twice")
        assertTrue(QuietHours.isActive(night, first))
        assertTrue(QuietHours.isActive(night, second))
        assertTrue(QuietHours.isActive(night, at(2026, 10, 25, 6, 59)))
        assertFalse(QuietHours.isActive(night, at(2026, 10, 25, 7, 0)))
        // a window of exactly the repeated hour is quiet during both passes
        val repeated = QuietHoursSettings(enabled = true, startMinutes = 2 * 60, endMinutes = 3 * 60)
        assertTrue(QuietHours.isActive(repeated, first))
        assertTrue(QuietHours.isActive(repeated, second))
    }

    @Test fun `the same instant is quiet or not depending on the phone's time zone`() {
        val instant = at(2026, 10, 20, 21, 30).toInstant() // 21:30 in Stockholm
        assertFalse(QuietHours.isActive(night, instant.atZone(stockholm)))
        assertTrue(QuietHours.isActive(night, instant.atZone(ZoneId.of("Asia/Tokyo")))) // 04:30 there
    }

    @Test fun `clock formats minutes`() {
        assertEquals("22:00", QuietHours.clock(22 * 60))
        assertEquals("07:05", QuietHours.clock(7 * 60 + 5))
    }
}
