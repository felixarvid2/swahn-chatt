package se.familychat.app.data.history

import se.familychat.app.ui.components.EmojiCatalog
import se.familychat.core.model.Emoji
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PassphraseStrengthTest {
    @Test fun `shorter than 12 characters is too short, whatever it contains`() {
        assertEquals(Strength.TOO_SHORT, PassphraseStrength.rate(""))
        assertEquals(Strength.TOO_SHORT, PassphraseStrength.rate("aB3\$xY7!qZ9"))
        assertTrue(PassphraseStrength.rate("aB3\$xY7!qZ9m") != Strength.TOO_SHORT)
    }

    @Test fun `repeats, runs and common words rate low`() {
        assertEquals(Strength.WEAK, PassphraseStrength.rate("aaaaaaaaaaaaaaaa"))
        assertEquals(Strength.WEAK, PassphraseStrength.rate("1234567890123456"))
        assertEquals(Strength.WEAK, PassphraseStrength.rate("passwordpassword"))
        assertEquals(Strength.WEAK, PassphraseStrength.rate("abcabcabcabcabc"))
    }

    @Test fun `longer and more varied rates higher`() {
        val fair = PassphraseStrength.rate("Sommar2026!hus")
        val good = PassphraseStrength.rate("blå kaffekopp springer fort")
        val strong = PassphraseStrength.rate("vXk9#mQ2\$zLp8&Rt4wNj7!Bd")
        assertTrue(fair.ordinal < strong.ordinal, "$fair < $strong")
        assertTrue(good.ordinal >= Strength.FAIR.ordinal, "$good")
        assertEquals(Strength.STRONG, strong)
    }

    @Test fun `the estimate grows with length`() {
        assertTrue(PassphraseStrength.estimateBits("abcdefghijklmnopqrstuvwxyz") <= PassphraseStrength.estimateBits("bQ7kXm2pLw9RtY4uNj6vZ3sHd8"))
        assertTrue(PassphraseStrength.estimateBits("kfjdhgslqpwo") < PassphraseStrength.estimateBits("kfjdhgslqpwokfjdhgslqpwo".reversed() + "pq"))
    }
}

class EmojiCatalogTest {
    @Test fun `every emoji of the catalogue is one valid emoji of at most 16 bytes - the same check the engine applies`() {
        assertTrue(EmojiCatalog.all.size > 80, "a useful number of emojis: ${EmojiCatalog.all.size}")
        for (e in EmojiCatalog.all) assertTrue(Emoji.isValid(e), "invalid: $e ${e.map { it.code.toString(16) }}")
        assertEquals(EmojiCatalog.all.size, EmojiCatalog.all.toSet().size + EmojiCatalog.quick.size, "only the quick ones repeat")
    }

    @Test fun `the quick bar has six reactions`() {
        assertEquals(listOf("👍", "❤️", "😂", "😮", "😢", "🙏"), EmojiCatalog.quick)
    }
}
