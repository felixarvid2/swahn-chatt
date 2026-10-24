package se.familychat.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A finished recording. */
class Recording(val file: File, val durationMs: Long)

/** Records voice messages as AAC in an MPEG-4 container (`.m4a`, mime `audio/mp4`) with [MediaRecorder]. */
@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preparer: AttachmentPreparer,
) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Elapsed time of the running recording. */
    fun elapsedMs(): Long = if (recorder == null) 0 else SystemClock.elapsedRealtime() - startedAt

    /** Starts recording. Needs the RECORD_AUDIO permission. Returns false if the microphone cannot be used. */
    @Synchronized
    fun start(): Boolean {
        if (recorder != null) return true
        val out = File(preparer.outgoingDir, "voice-${UUID.randomUUID()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setMaxDuration(MAX_DURATION_MS)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            out.delete()
            false
        }
    }

    /** Stops and returns the recording, or null if it was too short to be a message or failed. */
    @Synchronized
    fun finish(): Recording? {
        val r = recorder ?: return null
        val out = file
        val duration = SystemClock.elapsedRealtime() - startedAt
        recorder = null
        file = null
        return try {
            r.stop()
            r.release()
            if (out != null && out.length() > 0 && duration >= MIN_DURATION_MS) Recording(out, duration) else { out?.delete(); null }
        } catch (e: RuntimeException) {
            // stop() throws if no audio data arrived (a tap instead of a hold)
            runCatching { r.release() }
            out?.delete()
            null
        }
    }

    /** Stops and throws the recording away. */
    @Synchronized
    fun cancel() {
        val r = recorder ?: return
        val out = file
        recorder = null
        file = null
        runCatching { r.stop() }
        runCatching { r.release() }
        out?.delete()
    }

    companion object {
        const val MIN_DURATION_MS = 700L
        const val MAX_DURATION_MS = 15 * 60 * 1000
    }
}
