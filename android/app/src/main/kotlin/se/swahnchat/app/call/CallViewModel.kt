package se.swahnchat.app.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.webrtc.EglBase
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.NotificationPrivacy
import se.swahnchat.app.data.ThemeMode
import se.swahnchat.app.platform.security.AppLock
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.CallState
import javax.inject.Inject

/** Who the call is with, as the call screen shows them (unless [CallViewModel.mayShowCaller] says no). */
data class PeerUi(val name: String, val avatar: AvatarUi)

/**
 * The state and the actions of the call screen: the call manager, the audio routing and the media session of the call
 * in progress. The media session's video members are the app-only API of [WebRtcMediaSession].
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val calls: CallManager,
    private val audio: CallAudio,
    private val media: WebRtcMediaSessionFactory,
    private val store: LocalStore,
    private val prefs: AppPrefs,
    private val lock: AppLock,
    private val engine: SwahnChatEngine,
) : ViewModel() {
    val state: StateFlow<CallState> = calls.state
    val muted: StateFlow<Boolean> = calls.muted
    val speakerOn: StateFlow<Boolean> = audio.speakerOn
    val theme: StateFlow<ThemeMode> = prefs.theme
    val accent: StateFlow<se.swahnchat.app.data.Accent> = prefs.accent
    val highContrast: StateFlow<Boolean> = prefs.highContrast
    val blockScreenshots: StateFlow<Boolean> = prefs.blockScreenshots

    /** The media session of the call in progress; null before it is set up and after the call. */
    val session: StateFlow<WebRtcMediaSession?> = media.current

    /** The shared EGL context of the renderers; created on first use (a video call). */
    val eglContext: EglBase.Context get() = media.eglBase.eglBaseContext

    /** True while the app lock hides the app. */
    val appLocked: StateFlow<Boolean> = lock.locked

    /** Whether the person on the screen may be named: not with [NotificationPrivacy.HIDDEN] on a locked phone or a locked app. */
    fun mayShowCaller(keyguardLocked: Boolean, appLocked: Boolean): Boolean =
        prefs.notificationPrivacy.value != NotificationPrivacy.HIDDEN || !(keyguardLocked || appLocked)

    /** The person of the current call, looked up each time the call changes (null between calls). */
    val peer: StateFlow<PeerUi?> = calls.state
        .map { (it as? CallState.InCall)?.peerUserId }
        .distinctUntilChanged()
        .map { userId -> userId?.let { lookup(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun lookup(userId: String): PeerUi {
        val user = store.getUser(userId)
        val name = user?.displayName.orEmpty()
        return PeerUi(name, AvatarUi(name, user?.avatarAttachmentId))
    }

    /** Where to go back to: the conversation of the current call. */
    fun conversationId(): String? = (calls.state.value as? CallState.InCall)?.conversationId

    /** True when the screen may offer a way into the chat: neither the phone nor the app lock is locked. */
    fun mayOpenChat(keyguardLocked: Boolean): Boolean = !keyguardLocked && !lock.locked.value

    suspend fun startCall(conversationId: String, video: Boolean): Boolean = try {
        calls.startCall(conversationId, video)
        true
    } catch (e: IllegalStateException) {
        false // another call is in progress
    } catch (e: IllegalArgumentException) {
        false // not a direct conversation
    }

    /** The picture file of an avatar, for the caller's picture; null when it cannot be fetched. */
    suspend fun avatarFile(attachmentId: String): java.io.File? = try {
        engine.avatarFile(attachmentId)
    } catch (e: Exception) {
        null
    }

    suspend fun accept() = calls.accept()

    suspend fun reject() = calls.reject()

    suspend fun hangup() = calls.hangup()

    fun toggleMute() = calls.setMuted(!calls.muted.value)

    fun toggleSpeaker() = audio.toggleSpeaker()

    fun toggleCamera() {
        session.value?.let { it.setCameraEnabled(!it.cameraEnabled.value) }
    }

    fun switchCamera() {
        session.value?.switchCamera()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
