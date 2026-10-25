package se.swahnchat.app.call

import kotlin.test.Test
import kotlin.test.assertEquals

class CallFormatTest {
    @Test fun `a duration reads in seconds, minutes with seconds, or hours with minutes`() {
        assertEquals("0 s", callDurationLabel(0))
        assertEquals("45 s", callDurationLabel(45))
        assertEquals("1 min 0 s", callDurationLabel(60))
        assertEquals("3 min 12 s", callDurationLabel(192))
        assertEquals("1 h 2 min", callDurationLabel(3_725))
        assertEquals("0 s", callDurationLabel(-5))
    }

    @Test fun `the running clock has minutes and seconds, and hours from an hour on`() {
        assertEquals("00:00", callClock(0))
        assertEquals("03:12", callClock(192))
        assertEquals("1:02:05", callClock(3_725))
    }
}
