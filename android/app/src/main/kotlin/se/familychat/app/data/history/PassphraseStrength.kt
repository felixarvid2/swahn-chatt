package se.familychat.app.data.history

import kotlin.math.log2

/** How good a history passphrase looks. Only a hint for the person typing; the one hard rule is the minimum length. */
enum class Strength { TOO_SHORT, WEAK, FAIR, GOOD, STRONG }

object PassphraseStrength {
    /**
     * A rough estimate: length times the size of the character pool in use, in bits, scaled down because people do not pick
     * characters at random, less a penalty for repeats, runs and a repeated block ("aaaa", "1234", "abcabc") and for a few
     * common words. Four or five words in a row, or a long mix, reach [Strength.GOOD] or better.
     */
    fun rate(passphrase: CharSequence): Strength {
        if (passphrase.length < HistoryFormat.MIN_PASSPHRASE_LENGTH) return Strength.TOO_SHORT
        val bits = estimateBits(passphrase)
        return when {
            bits < 45 -> Strength.WEAK
            bits < 60 -> Strength.FAIR
            bits < 80 -> Strength.GOOD
            else -> Strength.STRONG
        }
    }

    fun estimateBits(passphrase: CharSequence): Double {
        val s = passphrase.toString()
        var pool = 0
        if (s.any { it.isLowerCase() }) pool += 26
        if (s.any { it.isUpperCase() }) pool += 26
        if (s.any { it.isDigit() }) pool += 10
        if (s.any { !it.isLetterOrDigit() && !it.isWhitespace() }) pool += 33
        if (s.any { it.isWhitespace() }) pool += 1
        if (s.any { it.code > 127 && it.isLetter() }) pool += 30
        if (pool == 0) return 0.0
        // characters that add little: a repeat of the previous one, or a further step up or down by one ("abc", "321")
        var distinctish = 0
        for (i in s.indices) {
            if (i == 0) {
                distinctish++
                continue
            }
            val delta = s[i].code - s[i - 1].code
            val previousDelta = if (i >= 2) s[i - 1].code - s[i - 2].code else Int.MIN_VALUE
            val repeat = delta == 0
            val run = (delta == 1 || delta == -1) && delta == previousDelta
            if (!repeat && !run) distinctish++
        }
        // a block that repeats ("passwordpassword") is worth only one copy of itself
        val unit = (1..s.length / 2).firstOrNull { p -> s.length % p == 0 && s == s.take(p).repeat(s.length / p) }
        if (unit != null) distinctish = minOf(distinctish, unit)
        var bits = distinctish * log2(pool.toDouble()) * HUMAN_FACTOR
        val lower = s.lowercase()
        for (word in COMMON) if (word in lower) bits -= 25
        // a passphrase made of whole words gets less credit per letter than random characters
        if (s.all { it.isLetter() || it.isWhitespace() }) bits *= 0.75
        return bits.coerceAtLeast(0.0)
    }

    private const val HUMAN_FACTOR = 0.65
    private val COMMON = listOf("password", "lösenord", "qwerty", "123456", "familj", "family", "admin", "letmein", "hemligt")
}
