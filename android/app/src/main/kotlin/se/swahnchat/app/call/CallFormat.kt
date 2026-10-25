package se.swahnchat.app.call

import java.util.Locale

/** "45 s", "3 min 12 s", "1 h 2 min": how long a call lasted, in the history notice. The units read the same in Swedish and English. */
fun callDurationLabel(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < SECONDS_PER_MINUTE -> "$s s"
        s < SECONDS_PER_HOUR -> "${s / SECONDS_PER_MINUTE} min ${s % SECONDS_PER_MINUTE} s"
        else -> "${s / SECONDS_PER_HOUR} h ${s % SECONDS_PER_HOUR / SECONDS_PER_MINUTE} min"
    }
}

/** "03:12" or, from an hour on, "1:02:03": the running clock of a call. */
fun callClock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / SECONDS_PER_HOUR
    val m = s % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val sec = s % SECONDS_PER_MINUTE
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%02d:%02d", m, sec)
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
