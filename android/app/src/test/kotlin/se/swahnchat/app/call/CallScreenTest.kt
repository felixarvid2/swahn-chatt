package se.swahnchat.app.call

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.theme.SwahnChatTheme
import se.swahnchat.core.client.call.CallState
import se.swahnchat.core.client.call.EndReason
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the call screen shows and offers in each phase, in Swedish. No video track is involved (the slots stay empty). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class CallScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        SwahnChatTheme(darkTheme = false, dynamicColor = false) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { block() } }
    }

    private fun state(phase: CallPhase, video: Boolean = false, hasLocalVideo: Boolean = false) =
        CallScreenState(phase, video, "Bertil Ek", AvatarUi("Bertil Ek"), elapsedSeconds = 192, hasLocalVideo = hasLocalVideo)

    @Test fun `an incoming call shows who, the kind and answer and decline`() {
        val taken = ArrayList<String>()
        content { CallScreen(state(CallPhase.INCOMING, video = true), CallScreenActions(onAnswer = { taken += "answer" }, onDecline = { taken += "decline" })) }
        compose.onNodeWithText("Bertil Ek").assertIsDisplayed()
        compose.onNodeWithText("Videosamtal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Svara").performClick()
        compose.onNodeWithContentDescription("Avvisa").performClick()
        assertEquals(listOf("answer", "decline"), taken)
        compose.onAllNodesWithContentDescription("Avsluta").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Stäng av mikrofonen").assertCountEquals(0)
    }

    @Test fun `an outgoing call rings and offers only to end it, a connecting one says so`() {
        content { CallScreen(state(CallPhase.OUTGOING), CallScreenActions()) }
        compose.onNodeWithText("Ringer …").assertIsDisplayed()
        compose.onNodeWithContentDescription("Avsluta").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Stäng av mikrofonen").assertCountEquals(0)
    }

    @Test fun `a connecting call says so`() {
        content { CallScreen(state(CallPhase.CONNECTING), CallScreenActions()) }
        compose.onNodeWithText("Ansluter …").assertIsDisplayed()
    }

    @Test fun `a connected voice call shows the clock, mute, speaker and end and no camera buttons`() {
        val taken = ArrayList<String>()
        content {
            CallScreen(
                state(CallPhase.CONNECTED),
                CallScreenActions(onMute = { taken += "mute" }, onSpeaker = { taken += "speaker" }, onHangup = { taken += "hangup" }),
            )
        }
        compose.onNodeWithText("03:12").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stäng av mikrofonen").performClick()
        compose.onNodeWithContentDescription("Högtalare på").performClick()
        compose.onNodeWithContentDescription("Avsluta").performClick()
        assertEquals(listOf("mute", "speaker", "hangup"), taken)
        compose.onAllNodesWithContentDescription("Stäng av kameran").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Byt kamera").assertCountEquals(0)
    }

    @Test fun `a muted call offers to unmute and a call on the speaker offers to switch it off`() {
        content { CallScreen(state(CallPhase.CONNECTED).copy(muted = true, speakerOn = true), CallScreenActions()) }
        compose.onNodeWithContentDescription("Slå på mikrofonen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Högtalare av").assertIsDisplayed()
    }

    @Test fun `a connected video call with a camera offers the camera buttons, without a camera it does not`() {
        val taken = ArrayList<String>()
        content {
            CallScreen(
                state(CallPhase.CONNECTED, video = true, hasLocalVideo = true),
                CallScreenActions(onCamera = { taken += "camera" }, onSwitchCamera = { taken += "switch" }),
            )
        }
        compose.onNodeWithContentDescription("Stäng av kameran").performClick()
        compose.onNodeWithContentDescription("Byt kamera").performClick()
        assertEquals(listOf("camera", "switch"), taken)
    }

    @Test fun `a video call without a camera has no camera buttons`() {
        content { CallScreen(state(CallPhase.CONNECTED, video = true, hasLocalVideo = false), CallScreenActions()) }
        compose.onAllNodesWithContentDescription("Stäng av kameran").assertCountEquals(0)
        compose.onNodeWithContentDescription("Avsluta").assertIsDisplayed()
    }

    @Test fun `an ended call says why for a moment and offers nothing`() {
        content { CallScreen(state(CallPhase.ENDED).copy(endedText = endedTextRes(EndReason.BUSY)), CallScreenActions()) }
        compose.onNodeWithText("Upptaget").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Avsluta").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Svara").assertCountEquals(0)
    }

    @Test fun `the back arrow reports the way back`() {
        var back = 0
        content { CallScreen(state(CallPhase.CONNECTED), CallScreenActions(onBack = { back++ })) }
        compose.onNodeWithContentDescription("Tillbaka").performClick()
        assertEquals(1, back)
    }

    @Test fun `call states map to screen phases and the ended reason to its text`() {
        val noName = "Inkommande samtal"
        fun screen(s: CallState, last: CallState.InCall? = null) = toScreenState(s, last, noName, AvatarUi(""), 5, false, false, true, false, false)
        assertNull(screen(CallState.Idle))
        assertEquals(CallPhase.INCOMING, screen(CallState.Incoming("c", "x", "bob", false))!!.phase)
        assertEquals(CallPhase.OUTGOING, screen(CallState.Outgoing("c", "x", "bob", true))!!.phase)
        assertEquals(CallPhase.CONNECTING, screen(CallState.Connecting("c", "x", "bob", true, true))!!.phase)
        assertEquals(CallPhase.CONNECTED, screen(CallState.Connected("c", "x", "bob", true, true, 1))!!.phase)
        // after the call the screen remembers whether it was a video call
        val ended = screen(CallState.Ended("c", EndReason.REJECTED), last = CallState.Outgoing("c", "x", "bob", true))!!
        assertEquals(CallPhase.ENDED, ended.phase)
        assertTrue(ended.video)
        assertEquals(se.swahnchat.app.R.string.call_ended_rejected, ended.endedText)
        for (reason in EndReason.entries) endedTextRes(reason) // every reason has a text
    }
}
