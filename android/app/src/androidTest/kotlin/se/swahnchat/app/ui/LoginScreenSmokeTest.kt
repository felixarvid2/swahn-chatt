package se.swahnchat.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.swahnchat.app.MainActivity
import se.swahnchat.app.R

/** A freshly installed app (no account) shows the login screen with its way to the server settings. */
@RunWith(AndroidJUnit4::class)
class LoginScreenSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = compose.activity.getString(id)

    @Test fun loginScreenAppearsOnFirstLaunch() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(text(R.string.login_intro)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.login_intro)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.login_tab_invite)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connection_settings)).assertIsDisplayed()
    }
}
