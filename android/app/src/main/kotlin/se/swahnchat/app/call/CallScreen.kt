package se.swahnchat.app.call

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.swahnchat.app.R
import se.swahnchat.app.ui.components.Avatar
import se.swahnchat.app.ui.model.AvatarUi

enum class CallPhase { INCOMING, OUTGOING, CONNECTING, CONNECTED, ENDED }

/** Everything the call screen shows; built from the call manager, the audio and the media session by [CallActivity]. */
@Immutable
data class CallScreenState(
    val phase: CallPhase,
    val video: Boolean,
    val name: String,
    val avatar: AvatarUi,
    /** Seconds since the call connected (the running clock). */
    val elapsedSeconds: Long = 0,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val cameraOn: Boolean = true,
    /** The phone sends video (a camera and its permission): the camera buttons and the preview are offered. */
    val hasLocalVideo: Boolean = false,
    /** The other side's video arrived: it fills the screen, else the avatar shows. */
    val hasRemoteVideo: Boolean = false,
    /** Why the call ended, shown for a moment in [CallPhase.ENDED]. */
    @StringRes val endedText: Int = R.string.call_ended,
)

class CallScreenActions(
    val onAnswer: () -> Unit = {},
    val onDecline: () -> Unit = {},
    val onHangup: () -> Unit = {},
    val onMute: () -> Unit = {},
    val onSpeaker: () -> Unit = {},
    val onCamera: () -> Unit = {},
    val onSwitchCamera: () -> Unit = {},
    val onBack: () -> Unit = {},
)

private val AnswerGreen = Color(0xFF2E7D32)
private val HangupRed = Color(0xFFC62828)
private val VideoScrim = Color(0xFF101414)

/**
 * The one screen of a call, in the app theme. [remoteVideo] fills the screen behind everything once the other side's video
 * is there ([CallScreenState.hasRemoteVideo]); [localPreview] is the small picture of our own camera in a corner. Both are slots
 * so that the screen itself holds no WebRTC view and can be drawn in tests and screenshots.
 */
@Composable
fun CallScreen(
    state: CallScreenState,
    actions: CallScreenActions,
    modifier: Modifier = Modifier,
    remoteVideo: @Composable BoxScope.() -> Unit = {},
    localPreview: @Composable BoxScope.() -> Unit = {},
) {
    val onVideo = state.video && state.phase != CallPhase.INCOMING
    val content = if (onVideo) Color.White else MaterialTheme.colorScheme.onBackground
    Box(modifier.fillMaxSize().background(if (onVideo) VideoScrim else MaterialTheme.colorScheme.background)) {
        if (state.hasRemoteVideo) remoteVideo()
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 8.dp)) {
            TopRow(state, actions, content)
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (!state.hasRemoteVideo) CallerBlock(state, content)
            }
            Controls(state, actions)
        }
        if (state.hasLocalVideo && state.cameraOn && state.phase != CallPhase.INCOMING) {
            Box(
                Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 56.dp, end = 16.dp).size(width = 96.dp, height = 144.dp),
            ) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) { localPreview() }
            }
        }
    }
}

@Composable
private fun TopRow(state: CallScreenState, actions: CallScreenActions, content: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = content)
        }
        if (state.hasRemoteVideo) {
            // over the picture, the name and the clock stay at the top
            Column(Modifier.padding(start = 8.dp)) {
                Text(state.name, color = content, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusText(state), color = content, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CallerBlock(state: CallScreenState, content: Color) {
    Avatar(state.avatar, size = 128.dp)
    Text(
        state.name, color = content, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 24.dp),
    )
    Text(statusText(state), color = content, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun statusText(state: CallScreenState): String = when (state.phase) {
    CallPhase.INCOMING -> stringResource(if (state.video) R.string.call_kind_video else R.string.call_kind_voice)
    CallPhase.OUTGOING -> stringResource(R.string.call_status_ringing)
    CallPhase.CONNECTING -> stringResource(R.string.call_status_connecting)
    CallPhase.CONNECTED -> callClock(state.elapsedSeconds)
    CallPhase.ENDED -> stringResource(state.endedText)
}

@Composable
private fun Controls(state: CallScreenState, actions: CallScreenActions) {
    when (state.phase) {
        CallPhase.INCOMING -> Row(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundButton(Icons.Filled.CallEnd, R.string.call_action_decline, HangupRed, Color.White, actions.onDecline, big = true)
            val answerIcon = if (state.video) Icons.Filled.Videocam else Icons.Filled.Call
            RoundButton(answerIcon, R.string.call_action_answer, AnswerGreen, Color.White, actions.onAnswer, big = true)
        }
        CallPhase.ENDED -> Unit
        else -> Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.phase == CallPhase.CONNECTED) {
                Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) { InCallToggles(state, actions) }
            }
            RoundButton(Icons.Filled.CallEnd, R.string.call_action_hangup, HangupRed, Color.White, actions.onHangup, big = true)
        }
    }
}

@Composable
private fun InCallToggles(state: CallScreenState, actions: CallScreenActions) {
    val idle = MaterialTheme.colorScheme.secondaryContainer
    val idleContent = MaterialTheme.colorScheme.onSecondaryContainer
    val active = MaterialTheme.colorScheme.primary
    val activeContent = MaterialTheme.colorScheme.onPrimary
    RoundButton(
        if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (state.muted) R.string.call_unmute else R.string.call_mute,
        if (state.muted) active else idle, if (state.muted) activeContent else idleContent, actions.onMute,
    )
    RoundButton(
        if (state.speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, if (state.speakerOn) R.string.call_speaker_off else R.string.call_speaker_on,
        if (state.speakerOn) active else idle, if (state.speakerOn) activeContent else idleContent, actions.onSpeaker,
    )
    if (state.video && state.hasLocalVideo) {
        RoundButton(
            if (state.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, if (state.cameraOn) R.string.call_camera_off else R.string.call_camera_on,
            if (state.cameraOn) idle else active, if (state.cameraOn) idleContent else activeContent, actions.onCamera,
        )
        RoundButton(Icons.Filled.FlipCameraAndroid, R.string.call_switch_camera, idle, idleContent, actions.onSwitchCamera)
    }
}

/** A round button with its action spoken as the content description. */
@Composable
private fun RoundButton(icon: ImageVector, @StringRes description: Int, container: Color, content: Color, onClick: () -> Unit, big: Boolean = false) {
    val label = stringResource(description)
    Surface(
        onClick = onClick, shape = CircleShape, color = container, contentColor = content,
        modifier = Modifier.size(if (big) 72.dp else 56.dp).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().clearAndSetSemantics { }) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (big) 32.dp else 26.dp))
        }
    }
}
