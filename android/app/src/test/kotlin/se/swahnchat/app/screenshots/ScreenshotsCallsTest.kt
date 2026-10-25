package se.swahnchat.app.screenshots

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import se.swahnchat.app.call.CallPhase
import se.swahnchat.app.call.CallScreen
import se.swahnchat.app.call.CallScreenActions
import se.swahnchat.app.call.CallScreenState
import se.swahnchat.app.ui.components.BubbleActions
import se.swahnchat.app.ui.components.LocalNowMillis
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.ui.model.SystemKind
import se.swahnchat.app.ui.nav.AppFrame
import se.swahnchat.app.ui.screens.ConversationCallbacks
import se.swahnchat.app.ui.screens.ConversationContent
import se.swahnchat.app.ui.screens.ConversationUiState
import se.swahnchat.app.ui.theme.SwahnChatTheme
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/**
 * Screenshots of the call screen and the call notices (same method as [ScreenshotTest]; written to `app/screenshots/`). The video
 * call is drawn without a video track (the avatar shows where the remote picture will be): Robolectric cannot load the WebRTC
 * natives, and the renderers are slots of [CallScreen] that stay empty here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class ScreenshotsCallsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = File("screenshots").also { it.mkdirs() }

    @Before fun fixedWorld() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            SwahnChatTheme(darkTheme = dark, dynamicColor = false) {
                CompositionLocalProvider(LocalNowMillis provides Fixtures.NOW) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppFrame(false, content) }
                }
            }
        }
        compose.waitForIdle()
        val image = compose.activity.window.decorView.drawToBitmap()
        FileOutputStream(File(dir, "${name}_${if (dark) "dark" else "light"}.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val bertil = AvatarUi("Bertil Ek")
    private val incoming = CallScreenState(CallPhase.INCOMING, video = false, name = "Bertil Ek", avatar = bertil)
    private val audio = CallScreenState(CallPhase.CONNECTED, video = false, name = "Bertil Ek", avatar = bertil, elapsedSeconds = 192, speakerOn = false)
    private val video = CallScreenState(
        CallPhase.CONNECTED, video = true, name = "Bertil Ek", avatar = bertil, elapsedSeconds = 75, speakerOn = true, hasLocalVideo = true,
    )

    @Test fun incomingLight() = shot("40_call_incoming", false) { CallScreen(incoming, CallScreenActions()) }
    @Test fun incomingDark() = shot("40_call_incoming", true) { CallScreen(incoming, CallScreenActions()) }

    @Test fun connectedAudioLight() = shot("40b_call_connected_audio", false) { CallScreen(audio.copy(muted = true), CallScreenActions()) }
    @Test fun connectedAudioDark() = shot("40b_call_connected_audio", true) { CallScreen(audio.copy(muted = true), CallScreenActions()) }

    @Test fun connectedVideoLight() = shot("40c_call_connected_video", false) { CallScreen(video, CallScreenActions()) }
    @Test fun connectedVideoDark() = shot("40c_call_connected_video", true) { CallScreen(video, CallScreenActions()) }

    @Test fun outgoingLight() = shot("40d_call_outgoing", false) { CallScreen(audio.copy(phase = CallPhase.OUTGOING), CallScreenActions()) }
    @Test fun outgoingDark() = shot("40d_call_outgoing", true) { CallScreen(audio.copy(phase = CallPhase.OUTGOING), CallScreenActions()) }

    private fun notices(): List<ChatRow> = listOf(
        ChatRow.DateHeader(Fixtures.at(10, 24, 0)),
        ChatRow.System("n1", SystemKind.CALL_COMPLETED, "bob", "Bertil Ek", null, Fixtures.at(10, 24, 9, 12), value = 192),
        ChatRow.System("n2", SystemKind.CALL_COMPLETED, "bob", "Bertil Ek", null, Fixtures.at(10, 24, 11, 3), value = 45, video = true),
        ChatRow.System("n3", SystemKind.CALL_MISSED, "bob", "Bertil Ek", null, Fixtures.at(10, 24, 13, 30)),
        ChatRow.System("n4", SystemKind.CALL_NO_ANSWER, "bob", "Bertil Ek", null, Fixtures.at(10, 24, 14, 0)),
        ChatRow.System("n5", SystemKind.CALL_BUSY, "bob", "Bertil Ek", null, Fixtures.at(10, 24, 14, 5)),
        ChatRow.System("n6", SystemKind.CALL_REJECTED, "bob", "Bertil Ek", null, Fixtures.at(10, 24, 14, 6)),
    )

    /** A direct conversation: the call buttons in the top bar, and the history lines of the calls in the list. */
    @Composable private fun DirectConversation(rows: List<ChatRow>, callActive: Boolean = false) {
        val items = flowOf(Fixtures.pages(rows)).collectAsLazyPagingItems()
        ConversationContent(
            ConversationUiState(title = "Bertil Ek", avatar = bertil, otherUserId = "bob", loaded = true, latestId = "n6", callActive = callActive),
            items, Fixtures.idle, BubbleActions(), ConversationCallbacks(),
        )
    }

    @Test fun callNoticesLight() = shot("40e_call_notices", false) { DirectConversation(notices()) }
    @Test fun callNoticesDark() = shot("40e_call_notices", true) { DirectConversation(notices()) }
}
