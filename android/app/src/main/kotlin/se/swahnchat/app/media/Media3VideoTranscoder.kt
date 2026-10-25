package se.swahnchat.app.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [VideoTranscoder] on Media3 Transformer: H.264 video and AAC audio in an MP4, scaled to the target, at the target bit rates.
 *
 * Transformer must be created, started, polled and cancelled on one thread that has a Looper, so all of it runs on the main
 * thread; the actual decoding and encoding happen on the codec's and Transformer's own threads, never on the main thread. A
 * listener is called by native/media code, so nothing in it may throw: it only records the outcome and resumes the coroutine.
 */
@Singleton
@androidx.annotation.OptIn(UnstableApi::class) // Media3 marks Transformer as unstable API; lint wants this form, not kotlin.OptIn
class Media3VideoTranscoder @Inject constructor(@ApplicationContext private val context: Context) : VideoTranscoder {
    override suspend fun transcode(input: File, output: File, target: VideoTarget, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            coroutineScope {
                val transformerRef = arrayOfNulls<Transformer>(1)
                val poller = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        val t = transformerRef[0]
                        if (t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                try {
                    suspendCancellableCoroutine { cont ->
                        val listener = object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(VideoTranscodeException("Transcode failed", exportException))
                            }
                        }
                        try {
                            val transformer = build(target, listener)
                            transformerRef[0] = transformer
                            output.delete()
                            transformer.start(edited(input, target), output.absolutePath)
                            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { runCatching { transformer.cancel() } } }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(VideoTranscodeException("Cannot start the transcode", e))
                        }
                    }
                } finally {
                    poller.cancel()
                }
                onProgress(1f)
            }
        }
    }

    private fun build(target: VideoTarget, listener: Transformer.Listener): Transformer {
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(target.videoBitrate).build())
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(target.audioBitrate).build())
            .build()
        return Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
            .addListener(listener)
            .build()
    }

    private fun edited(input: File, target: VideoTarget): EditedMediaItem {
        val video = ArrayList<Effect>()
        if (target.width != null && target.height != null) {
            video += Presentation.createForWidthAndHeight(target.width, target.height, Presentation.LAYOUT_SCALE_TO_FIT)
        }
        return EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).setEffects(Effects(emptyList(), video)).build()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
