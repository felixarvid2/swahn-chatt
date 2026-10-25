package se.swahnchat.app.call

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.swahnchat.app.realtimeWanted
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.CallState
import se.swahnchat.core.client.call.EndReason
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pure decisions of the call code (ringing plan, audio route, service types, realtime rule) and the manifest entries. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "sv")
class CallPlatformTest {
    // --- ringing plan ---------------------------------------------------------------------------------------------------------

    @Test fun `a silent phone does not ring or vibrate, a vibrating one only vibrates`() {
        assertEquals(RingPlan(sound = false, vibrate = false), RingPlan.of(AudioManager.RINGER_MODE_SILENT, NotificationManager.INTERRUPTION_FILTER_ALL))
        assertEquals(RingPlan(sound = false, vibrate = true), RingPlan.of(AudioManager.RINGER_MODE_VIBRATE, NotificationManager.INTERRUPTION_FILTER_ALL))
    }

    @Test fun `a normal phone rings and vibrates unless do not disturb is on, then it only vibrates`() {
        assertEquals(RingPlan(sound = true, vibrate = true), RingPlan.of(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL))
        assertEquals(RingPlan(sound = true, vibrate = true), RingPlan.of(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
        val filters = listOf(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_NONE, NotificationManager.INTERRUPTION_FILTER_ALARMS,
        )
        for (filter in filters) {
            assertEquals(RingPlan(sound = false, vibrate = true), RingPlan.of(AudioManager.RINGER_MODE_NORMAL, filter), "filter $filter")
        }
        // do not disturb never makes a silent phone vibrate
        assertEquals(RingPlan(sound = false, vibrate = false), RingPlan.of(AudioManager.RINGER_MODE_SILENT, NotificationManager.INTERRUPTION_FILTER_NONE))
    }

    // --- audio route ----------------------------------------------------------------------------------------------------------

    @Test fun `a headset wins over speaker and earpiece, bluetooth over wired`() {
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val earpiece = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        assertEquals(earpiece, CallRoute.pick(listOf(speaker, earpiece), speaker = false))
        assertEquals(speaker, CallRoute.pick(listOf(speaker, earpiece), speaker = true))
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, CallRoute.pick(listOf(speaker, earpiece, AudioDeviceInfo.TYPE_WIRED_HEADSET), speaker = true))
        assertEquals(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            CallRoute.pick(listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, speaker), speaker = false),
        )
        assertTrue(CallRoute.headsetPresent(listOf(AudioDeviceInfo.TYPE_USB_HEADSET)))
        assertFalse(CallRoute.headsetPresent(listOf(speaker, earpiece)))
    }

    @Test fun `the audio mode and the route are set during the call and restored after it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
        val call = CallAudio(context)
        call.start(video = true)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertTrue(call.speakerOn.value, "a video call starts on the speaker")
        call.toggleSpeaker()
        assertFalse(call.speakerOn.value)
        call.stop()
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertFalse(call.speakerOn.value)
        // an audio call starts on the earpiece; start and stop can be repeated and the proximity lock is only taken while connected
        call.start(video = false)
        assertFalse(call.speakerOn.value)
        call.setConnected(true)
        call.stop()
        call.stop()
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }

    // --- foreground service -----------------------------------------------------------------------------------------------------

    @Test fun `the service types follow the Android version and the camera permission`() {
        val phone = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val camera = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        assertEquals(0, callServiceTypes(Build.VERSION_CODES.P, video = true, cameraGranted = true))
        assertEquals(phone, callServiceTypes(Build.VERSION_CODES.Q, video = true, cameraGranted = true))
        assertEquals(phone or mic, callServiceTypes(Build.VERSION_CODES.R, video = false, cameraGranted = true))
        assertEquals(phone or mic, callServiceTypes(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, video = true, cameraGranted = false))
        assertEquals(phone or mic or camera, callServiceTypes(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, video = true, cameraGranted = true))
        assertEquals(phone or mic, callServiceTypes(35, video = false, cameraGranted = true))
    }

    @Test fun `the ongoing notification is call style with hang up and a running clock from the connection`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = android.content.Intent(context, CallService::class.java)
        val hangup = android.app.PendingIntent.getService(context, 0, service, android.app.PendingIntent.FLAG_IMMUTABLE)
        val connected = CallState.Connected("c", "conv", "bob", video = false, outgoing = true, since = 1_700_000_000_000)
        val n = OngoingCallNotification.build(context, connected, "Bertil", hangup)
        assertEquals("Bertil", shadowOf(n).contentTitle?.toString())
        assertEquals(1, n.actions.size, "one action: hang up (its label is the system's)")
        assertEquals(NotificationCompat.CallStyle.CALL_TYPE_ONGOING, n.extras.getInt("android.callType"))
        assertTrue(n.extras.getBoolean(Notification_EXTRA_SHOW_CHRONOMETER))
        assertEquals(1_700_000_000_000, n.`when`)
        assertNotNull(n.contentIntent)
        assertEquals(CallNotifier.CHANNEL_CALLS, n.channelId)
        // ringing out: no clock yet, and the notification names the name hidden by privacy as the service title
        val ringing = OngoingCallNotification.build(context, CallState.Outgoing("c", "conv", "bob", false), null, hangup)
        assertFalse(ringing.extras.getBoolean(Notification_EXTRA_SHOW_CHRONOMETER))
        assertEquals("Ringer …", shadowOf(ringing).contentText?.toString())
        assertEquals("Pågående samtal", shadowOf(ringing).contentTitle?.toString())
    }

    // --- realtime while a call is active in the background -----------------------------------------------------------------------

    @Test fun `realtime stays up for a call in the background and stops again when it ends`() {
        val incoming = CallState.Incoming("c", "conv", "bob", false)
        val connected = CallState.Connected("c", "conv", "bob", false, true, 1)
        assertFalse(realtimeWanted(foreground = false, signedIn = true, call = CallState.Idle))
        assertTrue(realtimeWanted(foreground = false, signedIn = true, call = incoming))
        assertTrue(realtimeWanted(foreground = false, signedIn = true, call = CallState.Outgoing("c", "conv", "bob", false)))
        assertTrue(realtimeWanted(foreground = false, signedIn = true, call = CallState.Connecting("c", "conv", "bob", false, true)))
        assertTrue(realtimeWanted(foreground = false, signedIn = true, call = connected))
        assertFalse(realtimeWanted(foreground = false, signedIn = true, call = CallState.Ended("c", EndReason.HANGUP_REMOTE)))
        // the app in the foreground is as before; nobody signed in never connects
        assertTrue(realtimeWanted(foreground = true, signedIn = true, call = CallState.Idle))
        assertFalse(realtimeWanted(foreground = true, signedIn = false, call = CallState.Idle))
        assertFalse(realtimeWanted(foreground = false, signedIn = false, call = incoming))
    }

    // --- decline ---------------------------------------------------------------------------------------------------------------

    @Test fun `declining rejects the ringing call on the application scope, finishes the receiver and never blocks`() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val signalling = TestSignalling()
        val calls = CallManager(signalling, TestMedia(), scope).also { it.start() }
        signalling.offer("c1")
        withTimeout(5_000) { while (calls.state.value !is CallState.Incoming) kotlinx.coroutines.delay(10) }
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        declineAsync(calls, scope, "other-call") { } // a decline for another call does nothing
        kotlinx.coroutines.delay(100)
        assertTrue(calls.state.value is CallState.Incoming)
        declineAsync(calls, scope, "c1") { finished.complete(Unit) }
        withTimeout(5_000) { finished.await() }
        assertEquals(CallState.Ended("c1", EndReason.REJECTED), calls.state.value)
        scope.coroutineContext[Job]?.cancel()
    }

    // --- manifest ----------------------------------------------------------------------------------------------------------------

    @Test fun `the call components are private to the app and the service declares its types`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        assertFalse(pm.getActivityInfo(ComponentName(context, CallActivity::class.java), 0).exported)
        assertFalse(pm.getReceiverInfo(ComponentName(context, CallDeclineReceiver::class.java), 0).exported)
        val service = pm.getServiceInfo(ComponentName(context, CallService::class.java), 0)
        assertFalse(service.exported)
        val expected = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        assertEquals(expected, service.foregroundServiceType)
        val requested = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        for (p in listOf(
            "FOREGROUND_SERVICE_PHONE_CALL", "FOREGROUND_SERVICE_MICROPHONE", "FOREGROUND_SERVICE_CAMERA", "MANAGE_OWN_CALLS",
            "USE_FULL_SCREEN_INTENT", "VIBRATE", "WAKE_LOCK", "MODIFY_AUDIO_SETTINGS",
        )) assertTrue("android.permission.$p" in requested, p)
    }

    private companion object {
        const val Notification_EXTRA_SHOW_CHRONOMETER = "android.showChronometer"
    }
}
