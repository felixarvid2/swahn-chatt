package se.swahnchat.app.call

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What rings while a call comes in: the ringtone and the vibration. [CallNotifier] starts it on an incoming call and stops it on any state change. */
interface CallRinger {
    /** Starts ringing, looping, until [stop]. Does nothing when already ringing. */
    fun start()

    /** Stops the sound and the vibration; safe to call at any time. */
    fun stop()
}

/** Sound and vibration for a ringing call, by the phone's ringer mode and do-not-disturb filter. */
data class RingPlan(val sound: Boolean, val vibrate: Boolean) {
    companion object {
        /**
         * Silent: nothing. Vibrate: vibration only. Normal: sound and vibration, but with do-not-disturb on (any filter other
         * than "all") only vibration. [interruptionFilter] is `NotificationManager.getCurrentInterruptionFilter()`; "unknown" counts as all.
         */
        fun of(ringerMode: Int, interruptionFilter: Int): RingPlan = when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> RingPlan(sound = false, vibrate = false)
            AudioManager.RINGER_MODE_VIBRATE -> RingPlan(sound = false, vibrate = true)
            else -> {
                val interrupting = interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL ||
                    interruptionFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                RingPlan(sound = interrupting, vibrate = true)
            }
        }
    }
}

/**
 * The phone's default ringtone, looping, with a repeating vibration. A notification channel's sound plays only once, so the
 * notifier rings itself (and the `calls` channel is silent). The sound uses the ringtone audio attributes, so the system
 * volume for ringtones applies.
 */
@Singleton
class AndroidCallRinger @Inject constructor(@ApplicationContext private val context: Context) : CallRinger {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)
    private var ringtone: Ringtone? = null
    private var player: MediaPlayer? = null
    private var ringing = false

    @Synchronized override fun start() {
        if (ringing) return
        ringing = true
        val plan = RingPlan.of(audio.ringerMode, notifications.currentInterruptionFilter)
        if (plan.sound) playSound()
        if (plan.vibrate) vibrate()
    }

    @Synchronized override fun stop() {
        ringing = false
        ringtone?.stop()
        ringtone = null
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        vibrator?.cancel()
    }

    private fun playSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                    audioAttributes = attributes
                    isLooping = true
                    play()
                }
            } else {
                player = MediaPlayer().apply {
                    setDataSource(context, uri)
                    setAudioAttributes(attributes)
                    isLooping = true
                    prepare()
                    start()
                }
            }
        } catch (e: java.io.IOException) {
            // no ringtone that can be played: the notification and the vibration still tell
        } catch (e: IllegalStateException) {
            // the same, on a device that refuses the player
        }
    }

    private fun vibrate() {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        v.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)) // repeat from the start until stop()
    }

    private companion object {
        /** Pause, buzz, pause, buzz: a ring-like rhythm, repeated. */
        val VIBRATION_PATTERN = longArrayOf(0, 800, 800, 800, 1_600)
    }
}
