package se.swahnchat.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio routing for the call in progress: the communication audio mode, audio focus, earpiece / speaker / headset and the
 * proximity sensor. [start] when the call connects, [stop] when it ends (both safe to repeat), driven by
 * [se.swahnchat.app.call.CallService]; the audio itself is WebRTC's.
 *
 * Routing: a connected Bluetooth or wired headset always wins; otherwise [speakerOn] chooses between loudspeaker and earpiece.
 * Video calls start on the loudspeaker (the phone is held away from the ear), audio calls on the earpiece. On Android 12+ the
 * device is chosen with `setCommunicationDevice`, before that with `isSpeakerphoneOn` and, for Bluetooth, the SCO link.
 * While an audio call is connected on the earpiece the screen turns off near the ear (proximity wake lock).
 */
@Singleton
class CallAudio @Inject constructor(@ApplicationContext private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    private val _speakerOn = MutableStateFlow(false)

    /** Whether the loudspeaker is in use (false: earpiece, or a headset). */
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    private var active = false
    private var video = false
    private var connected = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    private var proximity: PowerManager.WakeLock? = null
    private var scoOn = false
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = reroute()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = reroute()
    }

    /** Takes the audio for a call: [video] decides the default route. Does nothing when already started. */
    @Synchronized fun start(video: Boolean) {
        if (active) return
        active = true
        this.video = video
        connected = false
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
            .also { audio.requestAudioFocus(it) }
        _speakerOn.value = video && !CallRoute.headsetPresent(deviceTypes())
        audio.registerAudioDeviceCallback(deviceCallback, null)
        reroute()
    }

    /** The media connected: the proximity sensor may switch the screen off now. */
    @Synchronized fun setConnected(connected: Boolean) {
        if (!active) return
        this.connected = connected
        updateProximity()
    }

    @Synchronized fun toggleSpeaker() {
        if (!active) return
        _speakerOn.value = !_speakerOn.value
        reroute()
    }

    /** Gives the audio back: mode, focus, route and the wake lock are restored. */
    @Synchronized fun stop() {
        if (!active) return
        active = false
        connected = false
        releaseProximity()
        audio.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            if (scoOn) audio.stopBluetoothSco()
            scoOn = false
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        audio.mode = previousMode
        _speakerOn.value = false
    }

    @Synchronized private fun reroute() {
        if (!active) return
        val types = deviceTypes()
        val headset = CallRoute.headsetPresent(types)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = CallRoute.pick(types, _speakerOn.value)
            audio.availableCommunicationDevices.firstOrNull { it.type == wanted }?.let { audio.setCommunicationDevice(it) }
        } else {
            val bluetooth = types.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            val sco = bluetooth && !_speakerOn.value
            if (sco && !scoOn) audio.startBluetoothSco() else if (!sco && scoOn) audio.stopBluetoothSco()
            scoOn = sco
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = _speakerOn.value && !headset
        }
        updateProximity()
    }

    private fun deviceTypes(): List<Int> = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }

    /** The screen goes off near the ear only for an audio call on the earpiece that is connected. */
    private fun updateProximity() {
        val needed = connected && !video && !_speakerOn.value && !CallRoute.headsetPresent(deviceTypes())
        if (needed) acquireProximity() else releaseProximity()
    }

    private fun acquireProximity() {
        if (proximity?.isHeld == true || !power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximity = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "swahnchat:call-proximity").apply {
            setReferenceCounted(false)
            acquire(MAX_CALL_MS)
        }
    }

    private fun releaseProximity() {
        proximity?.let { if (it.isHeld) it.release() }
        proximity = null
    }

    private companion object {
        /** A safety net: the lock is released on [stop], but a call that never ends cannot keep the screen off for ever. */
        const val MAX_CALL_MS = 4L * 60 * 60 * 1000
    }
}

/** Which output a call uses; pure so the preference order is testable without a phone. Types are [AudioDeviceInfo] constants. */
object CallRoute {
    private val BLUETOOTH = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET)
    private val WIRED = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)

    /** A Bluetooth or wired headset is connected. */
    fun headsetPresent(types: List<Int>): Boolean = types.any { it in BLUETOOTH || it in WIRED }

    /** The type of the device to use: a Bluetooth headset first, then a wired one, else speaker or earpiece by [speaker]. */
    fun pick(types: List<Int>, speaker: Boolean): Int =
        BLUETOOTH.firstOrNull { it in types } ?: WIRED.firstOrNull { it in types }
            ?: if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
}

/** `AudioDeviceInfo.TYPE_BLE_HEADSET` (API 31), as a constant so the table above also loads on older Android versions. */
private const val TYPE_BLE_HEADSET = 26
