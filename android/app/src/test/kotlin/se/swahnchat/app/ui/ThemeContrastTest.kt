package se.swahnchat.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Test
import se.swahnchat.app.data.Accent
import se.swahnchat.app.ui.nav.isTwoPane
import se.swahnchat.app.ui.theme.appColorScheme
import se.swahnchat.app.ui.theme.contrastRatio
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** "Hög kontrast": every text colour reaches 7:1 on every surface it is drawn on, for all accents, light and dark. */
class ThemeContrastTest {
    private fun pairs(s: ColorScheme): List<Triple<String, Color, Color>> {
        val surfaces = listOf(
            "background" to s.background, "surface" to s.surface, "surfaceVariant" to s.surfaceVariant,
            "containerLowest" to s.surfaceContainerLowest, "containerLow" to s.surfaceContainerLow, "container" to s.surfaceContainer,
            "containerHigh" to s.surfaceContainerHigh, "containerHighest" to s.surfaceContainerHighest,
        )
        return buildList {
            for ((name, bg) in surfaces) {
                add(Triple("onSurface on $name", s.onSurface, bg))
                add(Triple("onSurfaceVariant on $name", s.onSurfaceVariant, bg))
                add(Triple("primary on $name", s.primary, bg))
                add(Triple("error on $name", s.error, bg))
                add(Triple("outline on $name", s.outline, bg))
            }
            add(Triple("onBackground on background", s.onBackground, s.background))
            add(Triple("onPrimary on primary", s.onPrimary, s.primary))
            add(Triple("onPrimaryContainer on primaryContainer", s.onPrimaryContainer, s.primaryContainer))
            add(Triple("onSecondary on secondary", s.onSecondary, s.secondary))
            add(Triple("onSecondaryContainer on secondaryContainer", s.onSecondaryContainer, s.secondaryContainer))
            add(Triple("onTertiary on tertiary", s.onTertiary, s.tertiary))
            add(Triple("onTertiaryContainer on tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer))
            add(Triple("onError on error", s.onError, s.error))
            add(Triple("onErrorContainer on errorContainer", s.onErrorContainer, s.errorContainer))
        }
    }

    @Test fun `high contrast reaches 7 to 1 for text in every accent, light and dark`() {
        for (accent in Accent.entries) for (dark in listOf(false, true)) {
            val scheme = appColorScheme(dark, accent, highContrast = true)
            for ((name, fg, bg) in pairs(scheme)) {
                val ratio = contrastRatio(fg, bg)
                assertTrue(ratio >= 7f, "$accent ${if (dark) "dark" else "light"}: $name is only ${"%.2f".format(ratio)}:1")
            }
            // the outline between a bubble and the page is a real line
            assertTrue(contrastRatio(scheme.outline, scheme.background) >= 7f)
            assertTrue(contrastRatio(scheme.outlineVariant, scheme.background) >= 4.5f)
        }
    }

    @Test fun `high contrast is pure white or black underneath`() {
        assertEquals(Color.White, appColorScheme(false, Accent.BLUE, true).background)
        assertEquals(Color.Black, appColorScheme(true, Accent.BLUE, true).background)
        assertEquals(Color.Black, appColorScheme(false, Accent.BLUE, true).onSurface)
        assertEquals(Color.White, appColorScheme(true, Accent.BLUE, true).onSurface)
    }

    @Test fun `the normal themes are unchanged without a choice and each accent changes the primary colour`() {
        val default = appColorScheme(false)
        assertEquals(appColorScheme(false, Accent.TEAL), default)
        val primaries = Accent.entries.filter { it != Accent.SYSTEM && it != Accent.TEAL }.map { appColorScheme(false, it).primary }
        assertEquals(primaries.size, primaries.toSet().size)
        primaries.forEach { assertNotEquals(default.primary, it) }
        for (accent in Accent.entries) for (dark in listOf(false, true)) {
            val s = appColorScheme(dark, accent)
            assertTrue(contrastRatio(s.onPrimary, s.primary) >= 4.5f, "$accent button text")
            assertTrue(contrastRatio(s.onPrimaryContainer, s.primaryContainer) >= 4.5f, "$accent bubble text")
            assertTrue(contrastRatio(s.onSurface, s.background) >= 4.5f, "$accent body text")
        }
    }

    @Test fun `two panes from 840 dp, or 600 dp in landscape, on a tablet or an unfolded foldable only`() {
        // tablets and foldables (smallest side 600 dp or more)
        assertFalse(isTwoPane(599, landscape = true, smallestWidthDp = 600))
        assertTrue(isTwoPane(600, landscape = true, smallestWidthDp = 600))
        assertFalse(isTwoPane(600, landscape = false, smallestWidthDp = 600))
        assertFalse(isTwoPane(839, landscape = false, smallestWidthDp = 700))
        assertTrue(isTwoPane(840, landscape = false, smallestWidthDp = 700))
        assertTrue(isTwoPane(1280, landscape = true, smallestWidthDp = 800))
        // phones stay as they are, also when turned sideways (a 915 x 412 dp window is wider than 840)
        assertFalse(isTwoPane(411, landscape = false, smallestWidthDp = 411))
        assertFalse(isTwoPane(915, landscape = true, smallestWidthDp = 412))
        assertFalse(isTwoPane(700, landscape = true, smallestWidthDp = 360))
    }
}
