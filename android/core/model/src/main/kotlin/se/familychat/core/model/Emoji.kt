package se.familychat.core.model

/**
 * Validation of the emoji a reaction carries: exactly one emoji (a "grapheme"), at most [MAX_BYTES] bytes of UTF-8.
 *
 * Not a general grapheme splitter. The JVM's `\X` and `BreakIterator` differ between JDK versions and Android's ICU for
 * ZWJ sequences, and the same input must be accepted or refused the same way on every phone and in the tests, so the
 * grammar is spelled out here: an element is a flag (two regional indicators), a keycap (`0-9 # *`, optional VS16,
 * U+20E3) or a pictograph with an optional VS16 and skin tone; elements are joined with ZWJ (U+200D). Anything else, plain
 * text, several emojis, control characters or whitespace, is refused.
 */
object Emoji {
    const val MAX_BYTES = 16

    private const val ZWJ = 0x200D
    private const val VS16 = 0xFE0F
    private const val VS15 = 0xFE0E
    private const val KEYCAP = 0x20E3

    @Suppress("ReturnCount") // a grammar: every early exit is a rule that is not met
    fun isValid(value: String?): Boolean {
        if (value.isNullOrEmpty() || value.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
        val cps = value.codePoints().toArray()
        var i = 0
        while (true) {
            i = element(cps, i)
            if (i < 0) return false
            if (i == cps.size) return true
            if (cps[i] != ZWJ) return false
            i++
            if (i == cps.size) return false // a dangling joiner
        }
    }

    /** Index after the element starting at [start], or -1 if none starts there. */
    @Suppress("ReturnCount") // a grammar: every early exit is a rule that is not met
    private fun element(cps: IntArray, start: Int): Int {
        if (start >= cps.size) return -1
        val c = cps[start]
        var i = start + 1
        if (isRegionalIndicator(c)) return if (i < cps.size && isRegionalIndicator(cps[i])) i + 1 else -1
        if (c in '0'.code..'9'.code || c == '#'.code || c == '*'.code) {
            if (i < cps.size && cps[i] == VS16) i++
            return if (i < cps.size && cps[i] == KEYCAP) i + 1 else -1
        }
        if (!isPictograph(c)) return -1
        if (i < cps.size && (cps[i] == VS16 || cps[i] == VS15)) i++
        if (i < cps.size && cps[i] in 0x1F3FB..0x1F3FF) i++
        return i
    }

    private fun isRegionalIndicator(c: Int) = c in 0x1F1E6..0x1F1FF

    private fun isPictograph(c: Int): Boolean = when (c) {
        0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x24C2, 0x3030, 0x303D, 0x3297, 0x3299 -> true
        in 0x2190..0x21FF, in 0x231A..0x23FF, in 0x25A0..0x25FF, in 0x2600..0x27BF, in 0x2900..0x297F, in 0x2B00..0x2BFF -> true
        in 0x1F000..0x1F1E5, in 0x1F200..0x1FAFF -> true
        else -> false
    }
}
