package se.swahnchat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.swahnchat.app.ui.screens.SettingsCallbacks
import se.swahnchat.app.ui.screens.SettingsContent
import se.swahnchat.app.ui.screens.SettingsUiState
import se.swahnchat.app.ui.theme.SwahnChatTheme
import kotlin.test.assertEquals

/** The call rows of the settings: the relay switch, and the full-screen row that only appears when the permission is off. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2400dp-xhdpi")
class SettingsCallsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        SwahnChatTheme(darkTheme = false, dynamicColor = false) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { block() } }
    }

    @Test fun `the relay switch shows its explanation and reports the change`() {
        val changes = ArrayList<Boolean>()
        content { SettingsContent(SettingsUiState(callsRelayOnly = false), SettingsCallbacks(onCallsRelayOnly = { changes += it })) }
        compose.onNodeWithText("Koppla samtal via servern").performScrollTo().performClick()
        assertEquals(listOf(true), changes)
        compose.onNodeWithText("Döljer din IP-adress", substring = true).performScrollTo()
    }

    @Test fun `the full screen row is only there when incoming calls may not take over the screen`() {
        content { SettingsContent(SettingsUiState(), SettingsCallbacks(fullScreenIntentAllowed = true)) }
        compose.onAllNodesWithText("Visa inkommande samtal på hela skärmen").assertCountEquals(0)
    }

    @Test fun `without the permission a row opens the system setting`() {
        var opened = 0
        content { SettingsContent(SettingsUiState(), SettingsCallbacks(fullScreenIntentAllowed = false, onOpenFullScreenIntentSettings = { opened++ })) }
        compose.onNodeWithText("Visa inkommande samtal på hela skärmen").performScrollTo().performClick()
        assertEquals(1, opened)
    }
}
