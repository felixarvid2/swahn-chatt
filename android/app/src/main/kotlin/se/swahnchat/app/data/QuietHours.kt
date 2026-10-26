package se.swahnchat.app.data

import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * "Tysta timmar": between [startMinutes] and [endMinutes] (minutes after local midnight) on the chosen [days] message
 * notifications arrive silently (no sound, vibration or heads-up) but are still listed. A window that crosses midnight
 * (the default, 22:00-07:00) belongs to the day it starts: with only Friday chosen, Friday 23:00 and Saturday 06:00 are quiet,
 * Saturday 23:00 is not. [muteCalls] also silences the ringing of incoming calls.
 */
data class QuietHoursSettings(
    val enabled: Boolean = false,
    val startMinutes: Int = 22 * 60,
    val endMinutes: Int = 7 * 60,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val muteCalls: Boolean = false,
)

object QuietHours {
    const val MINUTES_PER_DAY = 24 * 60

    /**
     * Whether [at] is inside the quiet window. The window is read on the wall clock of [at]'s zone, so a daylight saving
     * change moves it with the clock instead of shifting it by an hour: the window 22:00-07:00 is 8 or 10 hours long on the nights of the change.
     * Start equal to end is an empty window (never quiet).
     */
    fun isActive(s: QuietHoursSettings, at: ZonedDateTime): Boolean {
        if (!s.enabled || s.days.isEmpty() || s.startMinutes == s.endMinutes) return false
        val minute = at.hour * 60 + at.minute
        val today = at.dayOfWeek
        return if (s.startMinutes < s.endMinutes) {
            today in s.days && minute >= s.startMinutes && minute < s.endMinutes
        } else {
            // crosses midnight: the evening part belongs to today, the morning part to the day before
            (today in s.days && minute >= s.startMinutes) || (today.minus(1) in s.days && minute < s.endMinutes)
        }
    }

    /** "22:00" for 1320. */
    fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}
