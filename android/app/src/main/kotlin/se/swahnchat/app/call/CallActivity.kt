package se.swahnchat.app.call

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import se.swahnchat.app.MainActivity
import se.swahnchat.app.R
import se.swahnchat.app.data.ThemeMode
import se.swahnchat.app.platform.notify.Notifier
import se.swahnchat.app.ui.components.LocalAvatarLoader
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.theme.SwahnChatTheme
import se.swahnchat.core.client.call.CallState
import se.swahnchat.core.client.call.EndReason

/**
 * The call screen: the only thing it ever shows is the call. It is started to place a call ([ACTION_START]), by the full-screen
 * intent or a tap on the incoming-call notification ([showIntent]), by Answer ([ACTION_ANSWER]) and from the ongoing-call bar and
 * notification. It shows over the lock screen and turns the screen on, and from there never opens a chat: the way "back to the
 * chat" is only offered when neither the phone nor the app lock is locked, otherwise it just closes.
 *
 * Permissions are asked here, before the call is placed or answered: no microphone means no call (an incoming one is declined);
 * no camera means a video call carries on without sending video. [CallService] is started from here, while the activity is
 * visible, which is what Android 14 and 15 require for a microphone or camera service. The activity closes when the call is gone.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {
    private val vm: CallViewModel by viewModels()

    /** A launch waits for the permission dialog or for the call to be set up: an idle state must not close the screen meanwhile. */
    private var starting by mutableStateOf(false)
    private var pending: CallLaunch? = null

    /** The keyguard state, kept current while the activity is started (the name must go when the phone locks). */
    private val keyguardWatcher by lazy { KeyguardWatcher(this) }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissions(it) }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(se.swahnchat.app.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        lifecycleScope.launch {
            vm.blockScreenshots.collect { block ->
                if (block) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val accent by vm.accent.collectAsStateWithLifecycle()
            val highContrast by vm.highContrast.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val loader: suspend (String) -> java.io.File? = remember(vm) { { id -> vm.avatarFile(id) } }
            SwahnChatTheme(darkTheme = dark, accent = accent, highContrast = highContrast) {
                CompositionLocalProvider(LocalAvatarLoader provides loader) {
                    CallRoute(vm, keyguardWatcher.locked, starting = starting, onFinish = ::finish, onBack = ::leave)
                }
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onStart() {
        super.onStart()
        keyguardWatcher.start()
    }

    override fun onStop() {
        keyguardWatcher.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    private fun handle(intent: Intent) {
        val action = decideLaunch(
            intent.action, intent.getStringExtra(EXTRA_CONVERSATION_ID), intent.getBooleanExtra(EXTRA_VIDEO, false),
            intent.getStringExtra(EXTRA_CALL_ID), vm.state.value,
        )
        when (action) {
            CallLaunch.Finish -> finish()
            CallLaunch.Show -> Unit
            is CallLaunch.Place, is CallLaunch.Answer -> {
                pending = action
                starting = true
                requestPermissions(action)
            }
        }
    }

    private fun requestPermissions(action: CallLaunch) {
        val video = when (action) {
            is CallLaunch.Place -> action.video
            else -> (vm.state.value as? CallState.InCall)?.video == true
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }
        if (needed.all { granted(it) }) onPermissions(needed.associateWith { true }) else permissions.launch(needed.toTypedArray())
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun onPermissions(result: Map<String, Boolean>) {
        val action = pending ?: return
        pending = null
        if (result[Manifest.permission.RECORD_AUDIO] != true && !granted(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, R.string.call_error_microphone, Toast.LENGTH_LONG).show()
            lifecycleScope.launch {
                if (action is CallLaunch.Answer) vm.reject()
                starting = false
                finish()
            }
            return
        }
        val camera = granted(Manifest.permission.CAMERA)
        lifecycleScope.launch {
            when (action) {
                // no camera permission on a video call: it is placed as a voice call, honestly
                is CallLaunch.Place -> {
                    val video = action.video && camera
                    if (vm.startCall(action.conversationId, video)) CallService.start(this@CallActivity, video) else failed()
                }
                is CallLaunch.Answer -> {
                    val state = vm.state.value
                    if (state is CallState.Incoming && state.callId == action.callId) {
                        vm.accept()
                        CallService.start(this@CallActivity, state.video)
                    }
                }
                else -> Unit
            }
            starting = false
        }
    }

    private fun failed() {
        Toast.makeText(this, R.string.call_error_start, Toast.LENGTH_LONG).show()
        finish()
    }

    /** Back to the chat of the call, if nothing is locked; otherwise the screen just closes and the call goes on. */
    private fun leave() {
        val keyguard = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val conversation = vm.conversationId()
        if (conversation != null && vm.mayOpenChat(keyguard)) {
            startActivity(
                Intent(Intent.ACTION_VIEW, Notifier.deepLink(conversation), this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }

    companion object {
        const val ACTION_START = "se.swahnchat.app.CALL_START"
        const val ACTION_ANSWER = "se.swahnchat.app.CALL_ANSWER"
        const val ACTION_SHOW = "se.swahnchat.app.CALL_SHOW"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_VIDEO = "video"
        const val EXTRA_CALL_ID = "call_id"

        /** Places a call in the direct conversation [conversationId]. */
        fun startIntent(context: Context, conversationId: String, video: Boolean): Intent =
            Intent(context, CallActivity::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId).putExtra(EXTRA_VIDEO, video)

        /** Shows the call in progress (from a notification or the ongoing-call bar). */
        fun showIntent(context: Context): Intent =
            Intent(context, CallActivity::class.java).setAction(ACTION_SHOW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Answers the ringing call [callId]; closes at once if it is not ringing any more. */
        fun answerIntent(context: Context, callId: String): Intent =
            Intent(context, CallActivity::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_CALL_ID, callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/** The state of the call as the screen shows it; the last call is remembered for the moment after it ended. */
@Composable
private fun CallRoute(vm: CallViewModel, keyguardState: StateFlow<Boolean>, starting: Boolean, onFinish: () -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    val speaker by vm.speakerOn.collectAsStateWithLifecycle()
    val peer by vm.peer.collectAsStateWithLifecycle()
    val appLocked by vm.appLocked.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val keyguardLocked by keyguardState.collectAsStateWithLifecycle()

    // the call and the person are remembered for the moment after it ended (the state then only says why)
    val last = remember { arrayOfNulls<CallState.InCall>(1) }
    val lastPeer = remember { arrayOfNulls<PeerUi>(1) }
    (state as? CallState.InCall)?.let { last[0] = it }
    peer?.let { lastPeer[0] = it }
    LaunchedEffect(state, starting) { if (state is CallState.Idle && !starting) onFinish() }

    val elapsed by produceState(0L, state) {
        val connected = state as? CallState.Connected
        while (connected != null) {
            value = ((System.currentTimeMillis() - connected.since) / MS_PER_SECOND).coerceAtLeast(0)
            delay(MS_PER_SECOND)
        }
    }
    val remoteTrack by remember(session) { session?.remoteVideoTrack ?: MutableStateFlow(null) }.collectAsStateWithLifecycle()
    val cameraOn by remember(session) { session?.cameraEnabled ?: MutableStateFlow(false) }.collectAsStateWithLifecycle()
    val front by remember(session) { session?.cameraFront ?: MutableStateFlow(true) }.collectAsStateWithLifecycle()
    val localTrack = session?.localVideoTrack

    val shown = if (vm.mayShowCaller(keyguardLocked, appLocked)) peer ?: lastPeer[0] else null
    val screen = toScreenState(
        state, last[0], shown?.name ?: stringResource(R.string.call_incoming_title), shown?.avatar ?: AvatarUi(""),
        elapsed, muted, speaker, cameraOn, localTrack != null, remoteTrack != null, nameShown = shown != null,
    ) ?: return
    val scope = rememberCoroutineScope()
    val actions = remember(vm) {
        CallScreenActions(
            onAnswer = { scope.launch { vm.accept() } }, onDecline = { scope.launch { vm.reject() } }, onHangup = { scope.launch { vm.hangup() } },
            onMute = vm::toggleMute, onSpeaker = vm::toggleSpeaker, onCamera = vm::toggleCamera, onSwitchCamera = vm::switchCamera, onBack = onBack,
        )
    }
    CallScreen(
        screen, actions, Modifier.fillMaxSize(),
        remoteVideo = { remoteTrack?.let { VideoRenderer(it, vm.eglContext, mirror = false, overlay = false, modifier = Modifier.fillMaxSize()) } },
        localPreview = { localTrack?.let { VideoRenderer(it, vm.eglContext, mirror = front, overlay = true, modifier = Modifier.fillMaxSize()) } },
    )
}

private const val MS_PER_SECOND = 1_000L

/** The text for the moment a call has just ended. */
internal fun endedTextRes(reason: EndReason): Int = when (reason) {
    EndReason.HANGUP_LOCAL, EndReason.HANGUP_REMOTE -> R.string.call_ended
    EndReason.REJECTED -> R.string.call_ended_rejected
    EndReason.BUSY -> R.string.call_ended_busy
    EndReason.NO_ANSWER -> R.string.call_ended_no_answer
    EndReason.MISSED -> R.string.call_ended_missed
    EndReason.ANSWERED_ELSEWHERE -> R.string.call_ended_elsewhere
    EndReason.FAILED -> R.string.call_ended_failed
}

/** The screen state for a call state, or null when there is nothing to show (idle, or an ended call that was never seen). */
@Suppress("LongParameterList")
internal fun toScreenState(
    state: CallState,
    last: CallState.InCall?,
    name: String,
    avatar: AvatarUi,
    elapsedSeconds: Long,
    muted: Boolean,
    speakerOn: Boolean,
    cameraOn: Boolean,
    hasLocalVideo: Boolean,
    hasRemoteVideo: Boolean,
    nameShown: Boolean = true,
): CallScreenState? {
    val phase = when (state) {
        is CallState.Incoming -> CallPhase.INCOMING
        is CallState.Outgoing -> CallPhase.OUTGOING
        is CallState.Connecting -> CallPhase.CONNECTING
        is CallState.Connected -> CallPhase.CONNECTED
        is CallState.Ended -> CallPhase.ENDED
        CallState.Idle -> return null
    }
    val video = (state as? CallState.InCall)?.video ?: last?.video ?: false
    return CallScreenState(
        phase, video, name, avatar, elapsedSeconds, muted, speakerOn, cameraOn, hasLocalVideo, hasRemoteVideo,
        endedTextRes((state as? CallState.Ended)?.reason ?: EndReason.HANGUP_LOCAL),
        peerDeviceNew = ((state as? CallState.InCall) ?: last)?.peerDeviceNew == true,
        nameShown = nameShown,
    )
}
