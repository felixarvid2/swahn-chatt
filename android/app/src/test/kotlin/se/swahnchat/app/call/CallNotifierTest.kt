package se.swahnchat.app.call

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.NotificationPrivacy
import se.swahnchat.app.platform.AppForeground
import se.swahnchat.app.platform.notify.Notifier
import se.swahnchat.app.platform.work.WorkScheduler
import se.swahnchat.core.client.InMemoryLocalStore
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.CallState
import se.swahnchat.core.model.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A ringer that only counts. */
class CountingRinger : CallRinger {
    var starts = 0
    var stops = 0
    var ringing = false

    override fun start() {
        starts++
        ringing = true
    }

    override fun stop() {
        stops++
        ringing = false
    }
}

/**
 * The incoming-call notification and what goes with it, with the real call manager on fake signalling: the call-style
 * notification with Answer and Decline, the privacy setting, the missed-call notification, the ringtone through the ringer
 * interface, and the ring worker request for a hidden app. Robolectric does not run the system's check that a call-style
 * notification has a full-screen intent or a foreground service (it happens in system_server), so the "does not throw without the
 * permission" test proves that the app sets a full-screen intent on every call notification and that building it needs no
 * permission; it cannot prove how a real device treats it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "sv")
class CallNotifierTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var prefs: AppPrefs
    private lateinit var foreground: AppForeground
    private lateinit var signalling: TestSignalling
    private lateinit var calls: CallManager
    private lateinit var ringer: CountingRinger
    private lateinit var notifier: CallNotifier
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @Before fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        manager = context.getSystemService(NotificationManager::class.java)
        context.getSharedPreferences("swahnchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPrefs(context)
        foreground = AppForeground()
        signalling = TestSignalling()
        calls = CallManager(signalling, TestMedia(), scope)
        calls.start()
        val store = InMemoryLocalStore()
        store.upsertUsers(listOf(User("bob", "bob", "Bertil")))
        ringer = CountingRinger()
        notifier = CallNotifier(context, calls, store, prefs, foreground, ringer, WorkScheduler(context), scope)
        notifier.start()
    }

    @After fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun until(cond: () -> Boolean) {
        withTimeout(5_000) { while (!cond()) delay(10) }
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun incoming(): Notification? = posted().firstOrNull { it.channelId == CallNotifier.CHANNEL_CALLS }

    private fun title(n: Notification) = shadowOf(n).contentTitle?.toString()

    private fun ring(id: String = "call-1", video: Boolean = false) = runBlocking {
        signalling.offer(id, video)
        until { incoming() != null }
    }

    @Test fun `an incoming call posts a call style notification with answer and decline and a full screen intent`() {
        ring()
        val n = incoming()!!
        assertEquals("Bertil", title(n))
        assertEquals("Röstsamtal", shadowOf(n).contentText?.toString())
        assertEquals(NotificationCompat.CATEGORY_CALL, n.category)
        assertEquals(listOf("Avvisa", "Svara"), n.actions.map { it.title.toString() })
        assertNotNull(n.fullScreenIntent, "a call-style notification needs a full-screen intent")
        assertNotNull(n.contentIntent)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(NotificationCompat.CallStyle.CALL_TYPE_INCOMING, n.extras.getInt("android.callType"))
        // the pending intents are explicit and immutable, and the receiver is private
        assertTrue(shadowOf(n.actions[1].actionIntent).isActivityIntent)
        assertTrue(shadowOf(n.actions[0].actionIntent).isBroadcastIntent)
        val info = context.packageManager.getReceiverInfo(android.content.ComponentName(context, CallDeclineReceiver::class.java), 0)
        assertFalse(info.exported)
    }

    @Test fun `a call via a new unverified device says so in the subtitle and the privacy setting still applies to the name`() {
        signalling.newDevice = true
        ring()
        assertEquals("Röstsamtal – ny enhet", shadowOf(incoming()!!).contentText?.toString())
        assertEquals("Bertil", title(incoming()!!))
    }

    @Test fun `a new device reveals no name when notifications are hidden`() {
        signalling.newDevice = true
        prefs.setNotificationPrivacy(NotificationPrivacy.HIDDEN)
        ring("call-2", video = true)
        val n = incoming()!!
        assertEquals("Inkommande samtal", title(n))
        assertEquals("Videosamtal – ny enhet", shadowOf(n).contentText?.toString())
    }

    @Test fun `a video call says so`() {
        ring(video = true)
        assertEquals("Videosamtal", shadowOf(incoming()!!).contentText?.toString())
    }

    @Test fun `the calls channel is silent because the ringtone is played by the ringer`() {
        ring()
        val channel = manager.getNotificationChannel(CallNotifier.CHANNEL_CALLS)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    @Test fun `hidden notification privacy shows no name`() {
        prefs.setNotificationPrivacy(NotificationPrivacy.HIDDEN)
        ring()
        val n = incoming()!!
        assertEquals("Inkommande samtal", title(n))
        assertFalse(n.extras.keySet().any { n.extras.get(it)?.toString()?.contains("Bertil") == true }, "the name must not be anywhere in the notification")
    }

    @Test fun `name only privacy still names the caller`() {
        prefs.setNotificationPrivacy(NotificationPrivacy.NAME_ONLY)
        ring()
        assertEquals("Bertil", title(incoming()!!))
    }

    @Config(sdk = [34])
    @Test fun `posting the incoming call notification without the full screen permission does not throw`() {
        // (Robolectric has no switch for the permission; the point is that the notification is built and posted for any state of it)
        ring()
        assertNotNull(incoming()!!.fullScreenIntent)
    }

    @Test fun `the ringer starts with the call and stops with any state change`() = runBlocking<Unit> {
        ring()
        assertTrue(ringer.ringing)
        assertEquals(1, ringer.starts)
        calls.reject()
        until { !ringer.ringing }
        assertEquals(CallState.Ended("call-1", se.swahnchat.core.client.call.EndReason.REJECTED), calls.state.value)
        assertNull(incoming(), "a rejected call takes the notification down")
    }

    @Test fun `a call the caller gives up leaves a missed call notification on the messages channel and nothing else`() = runBlocking<Unit> {
        ring()
        signalling.hangup("call-1")
        until { incoming() == null && posted().any { it.channelId == Notifier.CHANNEL_MESSAGES } }
        val missed = posted().single { it.channelId == Notifier.CHANNEL_MESSAGES }
        assertEquals("Missat samtal från Bertil", title(missed))
        assertEquals(NotificationCompat.CATEGORY_MISSED_CALL, missed.category)
        assertNotNull(missed.contentIntent)
        assertTrue(missed.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertFalse(ringer.ringing)
    }

    @Test fun `a missed call respects the privacy setting`() = runBlocking<Unit> {
        prefs.setNotificationPrivacy(NotificationPrivacy.HIDDEN)
        ring()
        signalling.hangup("call-1")
        until { posted().any { it.channelId == Notifier.CHANNEL_MESSAGES } }
        assertEquals("Missat samtal", title(posted().single { it.channelId == Notifier.CHANNEL_MESSAGES }))
    }

    @Test fun `answered on another device takes the notification down without a missed call`() = runBlocking<Unit> {
        ring()
        signalling.answeredElsewhere("call-1")
        until { incoming() == null }
        assertTrue(posted().none { it.channelId == Notifier.CHANNEL_MESSAGES })
        assertFalse(ringer.ringing)
    }

    @Test fun `accepting takes the ringing notification down`() = runBlocking<Unit> {
        ring()
        calls.accept()
        until { incoming() == null }
        assertTrue(posted().isEmpty())
        assertFalse(ringer.ringing)
    }

    @Test fun `in the background the ring worker is requested, in the foreground it is not`() = runBlocking<Unit> {
        foreground.set(true)
        ring("fg")
        calls.reject()
        until { incoming() == null }
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkScheduler.CALL_RING).get().isEmpty())
        foreground.set(false)
        ring("bg")
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkScheduler.CALL_RING).get()
        // (the test WorkManager has no Hilt worker factory, so the request may already have failed; that it was made is the point)
        assertEquals(1, infos.size)
    }
}
