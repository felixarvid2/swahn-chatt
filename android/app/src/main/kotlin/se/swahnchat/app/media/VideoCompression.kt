package se.swahnchat.app.media

import kotlinx.coroutines.CancellationException
import se.swahnchat.core.client.OutgoingAttachment
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a transcode should produce: H.264/AAC in MP4, at most [width] x [height] (null: keep the size), at these bit rates. */
data class VideoTarget(val width: Int?, val height: Int?, val videoBitrate: Int, val audioBitrate: Int)

/** The encoder or the file could not do it; the original is sent instead. */
class VideoTranscodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Transcodes a video file. The one implementation on the phone is [Media3VideoTranscoder]; tests use a fake. Runs wherever the
 * implementation needs (Media3 insists on its own looper thread), reports progress from 0 to 1, and stops when the caller is cancelled.
 *
 * @throws VideoTranscodeException when the transcode fails (no encoder, bad input, no space)
 */
interface VideoTranscoder {
    suspend fun transcode(input: File, output: File, target: VideoTarget, onProgress: (Float) -> Unit)
}

/** Why a video was sent as it was. */
enum class VideoSkip { ORIGINAL_REQUESTED, NOT_NEEDED, NOT_SMALLER }

sealed interface VideoCompression {
    /** What goes into the message: the compressed file, or the original. */
    val attachment: OutgoingAttachment

    data class Skipped(override val attachment: OutgoingAttachment, val reason: VideoSkip) : VideoCompression

    /** [attachment] is the new file; [savedBytes] is how much smaller it is. */
    data class Compressed(override val attachment: OutgoingAttachment, val savedBytes: Long) : VideoCompression

    /** The encoder failed: [attachment] is the original, and the person is told. */
    data class Failed(override val attachment: OutgoingAttachment) : VideoCompression
}

/** The rules of "Skicka i originalkvalitet": when a video is transcoded, and into what. */
object VideoCompressionPolicy {
    /** A video above this size is transcoded. */
    const val MAX_UNCOMPRESSED_BYTES = 16L * 1024 * 1024

    /** A video larger than 1080p (1920 x 1080, in either orientation) is transcoded. */
    const val FULL_HD_LONG = 1920
    const val FULL_HD_SHORT = 1080

    /** The result fits in 1280 x 720 (720 x 1280 upright), keeping the aspect ratio. */
    const val TARGET_LONG = 1280
    const val TARGET_SHORT = 720
    const val VIDEO_BITRATE = 2_500_000
    const val AUDIO_BITRATE = 128_000

    /** True if a video of [sizeBytes] and [width] x [height] (null: unknown) is worth transcoding, unless the person wants the original. */
    fun shouldCompress(sizeBytes: Long, width: Int?, height: Int?, sendOriginal: Boolean): Boolean {
        if (sendOriginal) return false
        if (sizeBytes > MAX_UNCOMPRESSED_BYTES) return true
        if (width == null || height == null) return false
        return max(width, height) > FULL_HD_LONG || min(width, height) > FULL_HD_SHORT
    }

    /** The size to encode at: [width] x [height] scaled down to fit [TARGET_LONG] x [TARGET_SHORT] (never up), even numbers, or null if unknown. */
    fun targetSize(width: Int?, height: Int?): Pair<Int, Int>? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        val scale = min(1.0, min(TARGET_LONG.toDouble() / max(width, height), TARGET_SHORT.toDouble() / min(width, height)))
        fun even(v: Double) = max(2, (v.roundToInt() / 2) * 2)
        return even(width * scale) to even(height * scale)
    }

    fun target(width: Int?, height: Int?): VideoTarget {
        val size = targetSize(width, height)
        return VideoTarget(size?.first, size?.second, VIDEO_BITRATE, AUDIO_BITRATE)
    }
}

/**
 * Shrinks a prepared video before it is uploaded. The temporary output lives in [tempDir] and is gone again on every path
 * (cancel, failure, "not smaller"); a result that is smaller is moved into [outputDir] and replaces the original file, which is
 * deleted. Nothing in here touches the main thread: [transcoder] decides where it runs.
 */
class VideoCompressor(
    private val transcoder: VideoTranscoder,
    private val tempDir: () -> File,
    private val outputDir: () -> File,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** [onProgress] gets 0..1 while the transcode runs. Cancelling the caller cancels the transcode and removes its partial file. */
    suspend fun compress(video: OutgoingAttachment, sendOriginal: Boolean, onProgress: (Float) -> Unit = {}): VideoCompression {
        val size = video.file.length()
        val needed = video.kind == "video" && VideoCompressionPolicy.shouldCompress(size, video.width, video.height, false)
        return when {
            sendOriginal -> VideoCompression.Skipped(video, VideoSkip.ORIGINAL_REQUESTED)
            !needed -> VideoCompression.Skipped(video, VideoSkip.NOT_NEEDED)
            else -> transcodeOrKeep(video, size, onProgress)
        }
    }

    private suspend fun transcodeOrKeep(video: OutgoingAttachment, size: Long, onProgress: (Float) -> Unit): VideoCompression {
        val temp = File(tempDir().also { it.mkdirs() }, "compress-${newId()}.mp4")
        return try {
            transcoder.transcode(video.file, temp, VideoCompressionPolicy.target(video.width, video.height), onProgress)
            val result = temp.length()
            if (!temp.isFile || result <= 0L || result >= size) {
                VideoCompression.Skipped(video, VideoSkip.NOT_SMALLER)
            } else {
                val compressed = moveIntoPlace(temp, video)
                video.file.delete()
                VideoCompression.Compressed(compressed, size - result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // includes VideoTranscodeException: no encoder, bad input, no space
            VideoCompression.Failed(video)
        } catch (e: RuntimeException) {
            // a platform codec that throws something unexpected must not lose the message: the original goes
            VideoCompression.Failed(video)
        } finally {
            temp.delete() // gone already when it was moved into place
        }
    }

    private fun moveIntoPlace(temp: File, video: OutgoingAttachment): OutgoingAttachment {
        val final = File(outputDir().also { it.mkdirs() }, "vid-${newId()}.mp4")
        if (!temp.renameTo(final)) temp.copyTo(final, overwrite = true)
        val size = VideoCompressionPolicy.targetSize(video.width, video.height)
        return OutgoingAttachment(
            file = final, mime = "video/mp4", kind = "video", name = mp4Name(video.name),
            width = size?.first ?: video.width, height = size?.second ?: video.height,
            durationMs = video.durationMs, thumbnailBase64 = video.thumbnailBase64,
        )
    }

    private fun mp4Name(name: String?): String? = name?.substringBeforeLast('.', name)?.take(60)?.ifEmpty { null }?.let { "$it.mp4" }
}
