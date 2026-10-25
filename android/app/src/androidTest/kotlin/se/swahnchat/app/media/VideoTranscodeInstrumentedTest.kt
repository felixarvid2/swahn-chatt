package se.swahnchat.app.media

import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.swahnchat.app.appContext
import se.swahnchat.core.client.OutgoingAttachment
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The video compression on a real codec stack (and under R8 in the qa build): a tiny generated MP4 goes through the real Media3
 * Transformer. An emulator without a usable H.264 encoder makes the transcode fail; then the test asserts what the app does in that
 * case, which is the fallback to the original file (nothing lost, nothing left behind).
 */
@RunWith(AndroidJUnit4::class)
class VideoTranscodeInstrumentedTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(appContext.cacheDir, "video-test-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    /** About a second of coloured frames, H.264 in MP4; null when this device cannot encode (the test then uses a file that is not a video). */
    private fun generateMp4(file: File, w: Int, h: Int, frames: Int): File? = try {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            if (end) codec.signalEndOfInputStream()
            while (true) {
                val i = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                    i >= 0 -> {
                        val buffer = codec.getOutputBuffer(i)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        repeat(frames) { n ->
            val canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.rgb((n * 17) % 256, 90, 200 - n * 5))
            surface.unlockCanvasAndPost(canvas)
            drain(false)
            Thread.sleep(40)
        }
        drain(true)
        codec.stop(); codec.release(); surface.release()
        muxer.stop(); muxer.release()
        file.takeIf { it.length() > 0 }
    } catch (e: Exception) {
        null
    }

    private fun videoSize(file: File): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") null else width to height
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    @Test fun theTranscoderScalesATinyVideoOrFailsCleanlyWithoutAnEncoder() = runBlocking {
        val input = generateMp4(File(dir, "in.mp4"), 320, 240, 15)
        val output = File(dir, "out.mp4")
        val transcoder = Media3VideoTranscoder(appContext)
        val progress = ArrayList<Float>()
        if (input == null) {
            // no encoder on this emulator: a transcode of a file that is not a video must fail with our exception, never crash
            val junk = File(dir, "junk.mp4").apply { writeBytes(ByteArray(4096) { 3 }) }
            val failure = runCatching { transcoder.transcode(junk, output, VideoTarget(160, 120, 2_500_000, 128_000)) { progress += it } }.exceptionOrNull()
            assertTrue(failure is VideoTranscodeException, "got $failure")
            return@runBlocking
        }
        val failure = runCatching { transcoder.transcode(input, output, VideoTarget(160, 120, 2_500_000, 128_000)) { progress += it } }.exceptionOrNull()
        if (failure != null) {
            assertTrue(failure is VideoTranscodeException, "got $failure") // the device's encoder refused: the compressor falls back (next test)
            return@runBlocking
        }
        assertEquals(160 to 120, videoSize(output), "scaled to the target size, still a video")
        assertTrue(progress.isEmpty() || progress.last() == 1f)
    }

    @Test fun theCompressorNeverLosesTheOriginalWhateverTheDeviceCanDo() = runBlocking {
        val generated = generateMp4(File(dir, "in.mp4"), 320, 240, 15)
        val original = generated ?: File(dir, "in.mp4").apply { writeBytes(ByteArray(4096) { 3 }) }
        // claims 4K so the policy asks for a transcode; the real file is tiny
        val attachment = OutgoingAttachment(original, "video/mp4", "video", "in.mp4", 3840, 2160, 1000)
        val compressor = VideoCompressor(Media3VideoTranscoder(appContext), tempDir = { File(dir, "tmp") }, outputDir = { File(dir, "out") })
        val result = compressor.compress(attachment, sendOriginal = false)
        when (result) {
            is VideoCompression.Compressed -> {
                assertTrue(result.attachment.file.isFile && result.savedBytes > 0)
                assertFalse(original.exists(), "the original is replaced")
                assertEquals("video/mp4", result.attachment.mime)
            }
            // not smaller, or no usable encoder: the original goes on as it is
            is VideoCompression.Skipped, is VideoCompression.Failed -> {
                assertTrue(result.attachment.file.isFile && result.attachment.file == original, "the original is kept")
            }
        }
        assertTrue(File(dir, "tmp").listFiles().orEmpty().isEmpty(), "no temporary file is left")
        // "originalkvalitet" never touches it
        assertEquals(VideoSkip.ORIGINAL_REQUESTED, (compressor.compress(attachment, sendOriginal = true) as? VideoCompression.Skipped)?.reason)
    }
}
