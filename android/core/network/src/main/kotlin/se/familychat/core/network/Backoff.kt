package se.familychat.core.network

import kotlin.random.Random

/**
 * Exponential backoff with jitter for reconnects: the ceiling doubles from [baseMs] up to [maxMs]; the actual delay
 * is drawn from the top quarter of the ceiling's range (`0.75..1.0 x ceiling`) so many clients do not reconnect in
 * lock step. Defaults give roughly 1 s, 2 s, 4 s ... capped at 60 s.
 */
class BackoffPolicy(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val random: Random = Random.Default,
) {
    fun ceilingMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 30)
        val raw = baseMs * (1L shl shift)
        return if (raw < 0 || raw > maxMs) maxMs else raw
    }

    fun delayMs(attempt: Int): Long {
        val c = ceilingMs(attempt)
        return (c * (0.75 + 0.25 * random.nextDouble())).toLong()
    }
}
