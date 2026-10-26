@file:Suppress("MaxLineLength") // fixtures and one-line assertions

package se.swahnchat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.SecureFlagPolicy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.swahnchat.app.screenshots.Fixtures
import se.swahnchat.app.ui.screens.SecureDialogProperties
import se.swahnchat.app.ui.screens.ShareScreen
import se.swahnchat.app.ui.screens.ShareState
import se.swahnchat.app.ui.screens.shareHighlight
import se.swahnchat.app.ui.theme.SwahnChatTheme
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A shortcut id in the share intent (any app can send one) only marks a chat in the picker; the user still taps it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2400dp-xhdpi")
class ShareHighlightTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val ready = ShareState.Ready("hej", emptyList<File>(), refused = 0, truncated = false)

    @Test fun `only the accounts own conversations can be marked`() {
        assertEquals("d1", shareHighlight("d1", Fixtures.conversationItems))
        assertNull(shareHighlight("not-mine", Fixtures.conversationItems))
        assertNull(shareHighlight("d1", null))
        assertNull(shareHighlight(null, Fixtures.conversationItems))
    }

    @Test fun `a named chat is marked but nothing is chosen until the user taps it`() {
        val chosen = ArrayList<String>()
        compose.setContent {
            SwahnChatTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ShareScreen(ready, Fixtures.conversationItems, onChoose = { chosen += it }, onClose = {}, highlighted = "d1")
                }
            }
        }
        compose.waitForIdle()
        assertTrue(chosen.isEmpty(), "the extra alone must not send anything anywhere")
        compose.onNodeWithText("Bertil Ek").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText("Bertil Ek").performClick()
        assertEquals(listOf("d1"), chosen)
    }

    @Test fun `dialogs that show a code or a QR are secure windows`() {
        assertEquals(SecureFlagPolicy.SecureOn, SecureDialogProperties.securePolicy)
    }
}
