package se.swahnchat.app.call

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import se.swahnchat.app.R
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.NotificationPrivacy
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.CallState
import javax.inject.Inject

/**
 * The foreground service of a call in progress (type phoneCall | microphone, plus camera for a video call with the camera
 * permission): it keeps the process and the microphone alive when the screen is off or the app is in the background, shows the
 * ongoing-call notification with Hang up, and runs [CallAudio] for the duration. It stops itself when the call ends.
 *
 * Started by [CallActivity] only, once the call exists (placed or accepted) and the permissions are granted: Android 14 and 15
 * refuse to start a microphone or camera service from the background, and the visible activity is what allows it. Never started
 * while a call only rings (see [CallNotifier]).
 */
@AndroidEntryPoint
class CallService : Service() {
    @Inject lateinit var calls: CallManager
    @Inject lateinit var audio: CallAudio
    @Inject lateinit var store: LocalStore
    @Inject lateinit var prefs: AppPrefs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            scope.launch { calls.hangup() }
            return START_NOT_STICKY
        }
        // startForeground at once, whatever the state is: the system gives a service that was started as a foreground service seconds to do it
        val state = calls.state.value
        val video = (state as? CallState.InCall)?.video ?: intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ServiceCompat.startForeground(
            this, CallNotifier.ID_ONGOING, OngoingCallNotification.build(this, state, null, hangupIntent(this)),
            callServiceTypes(Build.VERSION.SDK_INT, video, cameraGranted),
        )
        if (!observing) {
            observing = true
            scope.launch { observe() }
        }
        return START_NOT_STICKY
    }

    private suspend fun observe() {
        calls.state.collect { state ->
            when (state) {
                is CallState.InCall -> {
                    if (state is CallState.Connecting || state is CallState.Connected) audio.start(state.video)
                    if (state is CallState.Connected) audio.setConnected(true)
                    val name = if (prefs.notificationPrivacy.value == NotificationPrivacy.HIDDEN) null else store.getUser(state.peerUserId)?.displayName
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(CallNotifier.ID_ONGOING, OngoingCallNotification.build(this, state, name, hangupIntent(this)))
                }
                else -> {
                    audio.stop()
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        audio.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_HANGUP = "se.swahnchat.app.CALL_HANGUP"
        const val EXTRA_VIDEO = "video"

        /** Starts the service as a foreground service. Only from the visible [CallActivity] (see the class comment). */
        fun start(context: Context, video: Boolean) {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).putExtra(EXTRA_VIDEO, video))
        }

        private fun hangupIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 0, Intent(context, CallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * The service types of a call: phone call alone on Android 10, plus microphone from Android 11 (the constants do not exist
 * before), plus camera only for a video call whose camera permission is granted (the system refuses a camera service
 * without it). Before Android 10 services have no type.
 */
@android.annotation.SuppressLint("InlinedApi") // each constant is only read on a version that has it (see the `when`)
internal fun callServiceTypes(sdk: Int, video: Boolean, cameraGranted: Boolean): Int = when {
    sdk < Build.VERSION_CODES.Q -> 0
    sdk < Build.VERSION_CODES.R -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
    else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
        (if (video && cameraGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
}

/** The notification of the call in progress: call style with Hang up, the clock running from the moment it connected. */
internal object OngoingCallNotification {
    fun build(context: Context, state: CallState, name: String?, hangup: PendingIntent): Notification {
        val shownName = name ?: context.getString(R.string.call_service_title)
        val person = Person.Builder().setName(shownName).build()
        val status = when (state) {
            is CallState.Outgoing -> R.string.call_status_ringing
            is CallState.Connecting -> R.string.call_status_connecting
            else -> R.string.call_status_connected
        }
        val builder = NotificationCompat.Builder(context, CallNotifier.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(shownName)
            .setContentText(context.getString(status))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangup))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, CallActivity.showIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state is CallState.Connected) builder.setUsesChronometer(true).setShowWhen(true).setWhen(state.since)
        return builder.build()
    }
}
