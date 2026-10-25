package se.swahnchat.app.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.swahnchat.app.MainActivity
import se.swahnchat.app.R
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.NotificationPrivacy
import se.swahnchat.app.di.ApplicationScope
import se.swahnchat.app.platform.AppForeground
import se.swahnchat.app.platform.notify.Notifier
import se.swahnchat.app.platform.work.WorkScheduler
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.CallState
import se.swahnchat.core.client.call.EndReason
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The incoming call as the person sees and hears it while no call screen is up: a call-style notification with Answer and
 * Decline, a full-screen intent to [CallActivity], the ringtone ([CallRinger]) and, when the app is in the background, the
 * [se.swahnchat.app.platform.work.CallRingWorker] that keeps the process alive while the phone rings. Started from
 * [se.swahnchat.app.AppStarter] next to the call manager; it only observes [CallManager.state].
 *
 * No foreground service runs while ringing: Android 14 and 15 do not allow a microphone or camera service to start from the
 * background, and an arriving push is no exemption. Answer is a [PendingIntent] to [CallActivity], which accepts after the
 * permissions are in and starts [CallService] from the visible activity; Decline goes to [CallDeclineReceiver].
 *
 * Who is calling follows [NotificationPrivacy]: with [NotificationPrivacy.HIDDEN] the notification says only "Inkommande
 * samtal" (and a missed call "Missat samtal"), as a hidden message notification names no sender. The caller's picture is not
 * shown in the notification.
 */
@Singleton
class CallNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calls: CallManager,
    private val store: LocalStore,
    private val prefs: AppPrefs,
    private val foreground: AppForeground,
    private val ringer: CallRinger,
    private val work: WorkScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val manager = NotificationManagerCompat.from(context)
    private var lastIncoming: CallState.Incoming? = null

    /** Call once from [se.swahnchat.app.AppStarter]. */
    fun start() {
        createChannel()
        scope.launch { calls.state.collect { onState(it) } }
    }

    /** The `calls` channel is silent: the ringtone is played by [ringer], because a channel sound plays only once. */
    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_CALLS, context.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_calls_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    internal suspend fun onState(state: CallState) {
        val previous = lastIncoming
        if (state is CallState.Incoming) {
            lastIncoming = state
            ringer.start()
            showIncoming(state)
            if (!foreground.isForeground.value) work.enqueueCallRing()
            return
        }
        lastIncoming = null
        ringer.stop()
        if (previous == null) return
        manager.cancel(ID_INCOMING)
        if (state is CallState.Ended && state.callId == previous.callId && state.reason == EndReason.MISSED) showMissed(previous)
    }

    private suspend fun callerName(userId: String): String? =
        if (prefs.notificationPrivacy.value == NotificationPrivacy.HIDDEN) null else store.getUser(userId)?.displayName

    @android.annotation.SuppressLint("MissingPermission") // checked with areNotificationsEnabled()
    private suspend fun showIncoming(call: CallState.Incoming) {
        if (!manager.areNotificationsEnabled()) return
        val name = callerName(call.peerUserId)
        val shownName = name ?: context.getString(R.string.call_incoming_title)
        val person = Person.Builder().setName(shownName).setImportant(true).build()
        val kind = context.getString(if (call.video) R.string.call_kind_video else R.string.call_kind_voice)
        val open = CallActivity.showIntent(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(shownName)
            .setContentText(kind)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, declineIntent(call), answerIntent(call)))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicIncoming(kind))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activity(REQ_OPEN, open))
            // A call-style notification needs a full-screen intent (or a foreground service) on Android 12+, or posting it throws. Always set;
            // without the permission the system just shows the notification instead of launching the screen.
            .setFullScreenIntent(activity(REQ_FULL_SCREEN, open), true)
            .setTimeoutAfter(RING_NOTIFICATION_TIMEOUT_MS)
        manager.notify(ID_INCOMING, builder.build())
    }

    private fun publicIncoming(kind: String) = NotificationCompat.Builder(context, CHANNEL_CALLS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.call_incoming_title))
        .setContentText(kind)
        .build()

    @android.annotation.SuppressLint("MissingPermission") // checked with areNotificationsEnabled()
    private suspend fun showMissed(call: CallState.Incoming) {
        if (!manager.areNotificationsEnabled()) return
        val name = callerName(call.peerUserId)
        val title = if (name != null) context.getString(R.string.call_missed_from, name) else context.getString(R.string.call_missed_hidden)
        val open = Intent(Intent.ACTION_VIEW, Notifier.deepLink(call.conversationId), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        manager.notify(
            ID_MISSED_BASE + (call.conversationId.hashCode() and ID_MASK),
            NotificationCompat.Builder(context, Notifier.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(context.getString(if (call.video) R.string.call_kind_video else R.string.call_kind_voice))
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, REQ_MISSED, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build(),
        )
    }

    private fun activity(requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun answerIntent(call: CallState.Incoming): PendingIntent = activity(REQ_ANSWER, CallActivity.answerIntent(context, call.callId))

    private fun declineIntent(call: CallState.Incoming): PendingIntent {
        val intent = Intent(context, CallDeclineReceiver::class.java).setAction(CallDeclineReceiver.ACTION)
            .putExtra(CallActivity.EXTRA_CALL_ID, call.callId)
        return PendingIntent.getBroadcast(context, REQ_DECLINE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_CALLS = "calls"
        const val ID_INCOMING = 3
        const val ID_ONGOING = 4
        private const val ID_MISSED_BASE = 5_000_000
        private const val ID_MASK = 0x0FFFFFFF
        private const val REQ_OPEN = 1
        private const val REQ_FULL_SCREEN = 2
        private const val REQ_ANSWER = 3
        private const val REQ_DECLINE = 4
        private const val REQ_MISSED = 5

        /** Longer than the ring time: the call ends the notification, this only clears a stale one if the process was killed. */
        private const val RING_NOTIFICATION_TIMEOUT_MS = 60_000L
    }
}
