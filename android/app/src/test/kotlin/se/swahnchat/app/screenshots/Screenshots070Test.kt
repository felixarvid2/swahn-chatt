@file:Suppress("MaxLineLength") // fixtures and one-line assertions

package se.swahnchat.app.screenshots

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.swahnchat.app.call.CallPhase
import se.swahnchat.app.call.CallScreen
import se.swahnchat.app.call.CallScreenActions
import se.swahnchat.app.call.CallScreenState
import se.swahnchat.app.data.Accent
import se.swahnchat.app.data.ChatBackground
import se.swahnchat.app.data.LockTimeout
import se.swahnchat.app.data.NotificationPrivacy
import se.swahnchat.app.data.QuietHoursSettings
import se.swahnchat.app.data.ThemeMode
import se.swahnchat.app.ui.components.BubbleActions
import se.swahnchat.app.ui.components.LocalChatBackground
import se.swahnchat.app.ui.components.LocalNowMillis
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.ui.nav.AppFrame
import se.swahnchat.app.ui.nav.PaneLayout
import se.swahnchat.app.ui.screens.ConversationCallbacks
import se.swahnchat.app.ui.screens.ConversationContent
import se.swahnchat.app.ui.screens.ConversationUiState
import se.swahnchat.app.ui.screens.ConversationsContent
import se.swahnchat.app.ui.screens.DeviceUi
import se.swahnchat.app.ui.screens.DevicesUi
import se.swahnchat.app.ui.screens.SettingsCallbacks
import se.swahnchat.app.ui.screens.SettingsContent
import se.swahnchat.app.ui.screens.SettingsUiState
import se.swahnchat.app.ui.theme.SwahnChatTheme
import se.swahnchat.core.client.Presence
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/** What the screenshot tests of batch 7 share: the theme options, the fixtures and writing the picture (names start with `70`-`74`). */
abstract class ScreenshotBase070 {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dir = File("screenshots").also { it.mkdirs() }

    @Before fun fixedWorld() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    protected fun shot(
        name: String, dark: Boolean, accent: Accent = Accent.SYSTEM, highContrast: Boolean = false,
        background: ChatBackground = ChatBackground.PLAIN, content: @Composable () -> Unit,
    ) {
        compose.setContent {
            SwahnChatTheme(darkTheme = dark, dynamicColor = false, accent = accent, highContrast = highContrast) {
                CompositionLocalProvider(LocalNowMillis provides Fixtures.NOW, LocalChatBackground provides background) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppFrame(false, content) }
                }
            }
        }
        compose.waitForIdle()
        val image = compose.activity.window.decorView.drawToBitmap()
        FileOutputStream(File(dir, "${name}_${if (dark) "dark" else "light"}.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    protected fun both(
        name: String, accent: Accent = Accent.SYSTEM, highContrast: Boolean = false, background: ChatBackground = ChatBackground.PLAIN,
        content: @Composable () -> Unit,
    ) {
        shot(name, false, accent, highContrast, background, content)
        compose.activityRule.scenario.recreate()
        shot(name, true, accent, highContrast, background, content)
    }

    protected val direct = ConversationUiState(
        title = "Bertil Ek", isGroup = false, avatar = Fixtures.bob.avatar, loaded = true, otherUserId = "bob", draft = "",
        latestId = "last",
    )

    @Composable protected fun Chat(state: ConversationUiState = direct, rows: List<ChatRow> = Fixtures.directRows) {
        val items = flowOf(Fixtures.pages(rows)).collectAsLazyPagingItems()
        ConversationContent(
            state.copy(latestId = (rows.lastOrNull { it is ChatRow.Message } as? ChatRow.Message)?.message?.id), items, Fixtures.idle,
            BubbleActions(), ConversationCallbacks(),
        )
    }

    @Composable protected fun Conversations(selectedId: String? = null) =
        ConversationsContent(Fixtures.conversationItems, false, {}, {}, {}, {}, selectedId = selectedId)

    protected val settings = SettingsUiState(
        displayName = "Anna Lindqvist", username = "anna", avatar = Fixtures.anna.avatar, isAdmin = false, serverUrl = "https://hemma.tail1234.ts.net",
        deviceName = "Google Pixel 8",
        devices = DevicesUi.Loaded(listOf(DeviceUi("d1", "Google Pixel 8", Fixtures.NOW, true))),
        pushStatus = Fixtures.pushActive, distributors = Fixtures.distributors, selectedDistributor = "io.heckel.ntfy",
        theme = ThemeMode.SYSTEM, versionName = "0.7.0", notificationPrivacy = NotificationPrivacy.NAME_ONLY, appLockEnabled = true,
        lockTimeout = LockTimeout.MINUTES_5,
        quietHours = QuietHoursSettings(enabled = true), sendReadReceipts = false, showTyping = true, sharePresence = true,
        accent = Accent.BLUE, chatBackground = ChatBackground.DOTS,
    )
}

/** Phone size: the new look of the chat (accent colours, backgrounds, high contrast, online status) in light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class Screenshots070Test : ScreenshotBase070() {
    @Test fun accentBlue() = both("70_chat_accent_blue", accent = Accent.BLUE) { Chat() }
    @Test fun accentRose() = both("70_chat_accent_rose", accent = Accent.ROSE) { Chat() }
    @Test fun accentOrange() = both("70_chat_accent_orange", accent = Accent.ORANGE) { Chat() }
    @Test fun accentPurpleList() = both("70_conversations_accent_purple", accent = Accent.PURPLE) { Conversations() }
    @Test fun accentGreenList() = both("70_conversations_accent_green", accent = Accent.GREEN) { Conversations() }

    @Test fun backgroundDots() = both("71_chat_background_dots", background = ChatBackground.DOTS) { Chat() }
    @Test fun backgroundGrid() = both("71_chat_background_grid", background = ChatBackground.GRID) { Chat() }
    @Test fun backgroundDiagonal() = both("71_chat_background_lines", background = ChatBackground.DIAGONAL) { Chat() }
    @Test fun backgroundTint() = both("71_chat_background_tint", background = ChatBackground.TINT) { Chat() }

    @Test fun highContrastChat() = both("72_chat_high_contrast", highContrast = true, background = ChatBackground.DOTS) { Chat() }
    @Test fun highContrastList() = both("72_conversations_high_contrast", highContrast = true) { Conversations() }

    @Test fun online() = both("73_chat_online") { Chat(direct.copy(presence = Presence(online = true, lastSeenAt = Fixtures.NOW))) }
    @Test fun lastSeen() = both("73_chat_last_seen") { Chat(direct.copy(presence = Presence(online = false, lastSeenAt = Fixtures.at(10, 24, 14, 5)))) }
}

/** The settings with the new rows (tall window: the whole page). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2100dp-xhdpi")
class Screenshots070SettingsTest : ScreenshotBase070() {
    @Test fun settings() = both("70_settings") { SettingsContent(settings, SettingsCallbacks()) }
    @Test fun settingsHighContrast() = both("72_settings_high_contrast", highContrast = true) { SettingsContent(settings.copy(highContrast = true), SettingsCallbacks()) }
}

/** A tablet in landscape: the list on the left, the open chat on the right. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w1024dp-h768dp-land-xhdpi")
class Screenshots070TabletTest : ScreenshotBase070() {
    @Test fun twoPane() = both("74_tablet_two_pane") {
        PaneLayout(twoPane = true, list = { Conversations(selectedId = "bob") }) { Chat() }
    }

    @Test fun twoPaneNoChat() = both("74_tablet_no_chat") {
        PaneLayout(twoPane = true, list = { Conversations() }) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(androidx.compose.ui.res.stringResource(se.swahnchat.app.R.string.select_conversation), style = MaterialTheme.typography.titleMedium)
                Text(androidx.compose.ui.res.stringResource(se.swahnchat.app.R.string.select_conversation_hint), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Test fun twoPaneAccent() = both("74_tablet_two_pane_rose", accent = Accent.ROSE, background = ChatBackground.TINT) {
        PaneLayout(twoPane = true, list = { Conversations(selectedId = "bob") }) { Chat() }
    }
}

/** The main screens with the text at 200 %: nothing may be cut off or overlap. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi", fontScale = 2.0f)
class Screenshots070FontScaleTest : ScreenshotBase070() {
    @Test fun conversations() = both("75_font200_conversations") { Conversations() }
    @Test fun chat() = both("75_font200_chat") { Chat() }
    @Test fun call() = both("75_font200_call") {
        CallScreen(CallScreenState(CallPhase.INCOMING, video = false, name = "Bertil Ek", avatar = AvatarUi("Bertil Ek")), CallScreenActions())
    }
    @Test fun callConnected() = both("75_font200_call_connected") {
        CallScreen(
            CallScreenState(CallPhase.CONNECTED, video = false, name = "Bertil Ek", avatar = AvatarUi("Bertil Ek"), elapsedSeconds = 192, speakerOn = false),
            CallScreenActions(),
        )
    }
}

/** Settings at 200 % (a taller window, as the page is long). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h3200dp-xhdpi", fontScale = 2.0f)
class Screenshots070FontScaleSettingsTest : ScreenshotBase070() {
    @Test fun settings() = both("75_font200_settings") { SettingsContent(settings, SettingsCallbacks()) }
}
