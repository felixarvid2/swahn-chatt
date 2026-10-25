package se.swahnchat.app.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.swahnchat.core.client.OutgoingAttachment
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MB = 1024L * 1024

/** When a video is transcoded, what it becomes, and what happens to the files; the Transformer sits behind a fake. */
class VideoCompressionTest {
    @get:Rule val tmp = TemporaryFolder()

    // --- the decision --------------------------------------------------------------------------------------------------

    @Test fun `a video above 16 MB is compressed, one at 16 MB or less is not`() {
        assertTrue(VideoCompressionPolicy.shouldCompress(16 * MB + 1, 640, 360, sendOriginal = false))
        assertFalse(VideoCompressionPolicy.shouldCompress(16 * MB, 640, 360, sendOriginal = false))
        assertFalse(VideoCompressionPolicy.shouldCompress(1 * MB, 1280, 720, sendOriginal = false))
    }

    @Test fun `a video above 1080p is compressed whatever its size, in either orientation`() {
        assertFalse(VideoCompressionPolicy.shouldCompress(5 * MB, 1920, 1080, false), "1080p itself stays")
        assertFalse(VideoCompressionPolicy.shouldCompress(5 * MB, 1080, 1920, false), "upright 1080p stays")
        assertTrue(VideoCompressionPolicy.shouldCompress(5 * MB, 3840, 2160, false))
        assertTrue(VideoCompressionPolicy.shouldCompress(5 * MB, 2160, 3840, false))
        assertTrue(VideoCompressionPolicy.shouldCompress(5 * MB, 1921, 1080, false))
        assertTrue(VideoCompressionPolicy.shouldCompress(5 * MB, 1920, 1081, false))
    }

    @Test fun `unknown dimensions are judged by size alone`() {
        assertFalse(VideoCompressionPolicy.shouldCompress(5 * MB, null, null, false))
        assertTrue(VideoCompressionPolicy.shouldCompress(17 * MB, null, null, false))
    }

    @Test fun `the original toggle always wins`() {
        assertFalse(VideoCompressionPolicy.shouldCompress(500 * MB, 3840, 2160, sendOriginal = true))
    }

    @Test fun `the target keeps the aspect ratio, fits 1280 x 720 or 720 x 1280, never scales up and uses even sizes`() {
        assertEquals(1280 to 720, VideoCompressionPolicy.targetSize(1920, 1080))
        assertEquals(1280 to 720, VideoCompressionPolicy.targetSize(3840, 2160))
        assertEquals(720 to 1280, VideoCompressionPolicy.targetSize(2160, 3840))
        assertEquals(720 to 1280, VideoCompressionPolicy.targetSize(1080, 1920))
        assertEquals(640 to 360, VideoCompressionPolicy.targetSize(640, 360), "no scaling up")
        assertEquals(1280 to 540, VideoCompressionPolicy.targetSize(2560, 1080), "ultra-wide: the long edge limits")
        val odd = VideoCompressionPolicy.targetSize(1921, 1081)!!
        assertTrue(odd.first % 2 == 0 && odd.second % 2 == 0, "$odd")
        assertNull(VideoCompressionPolicy.targetSize(null, 100))
        assertNull(VideoCompressionPolicy.targetSize(0, 0))
        val t = VideoCompressionPolicy.target(1920, 1080)
        assertEquals(2_500_000, t.videoBitrate); assertEquals(128_000, t.audioBitrate)
    }

    // --- the orchestration ---------------------------------------------------------------------------------------------

    private class FakeTranscoder(var behaviour: suspend (File, File, VideoTarget, (Float) -> Unit) -> Unit) : VideoTranscoder {
        var targets = ArrayList<VideoTarget>()
        override suspend fun transcode(input: File, output: File, target: VideoTarget, onProgress: (Float) -> Unit) {
            targets += target
            behaviour(input, output, target, onProgress)
        }
    }

    private fun writer(bytes: Long): suspend (File, File, VideoTarget, (Float) -> Unit) -> Unit = { _, out, _, progress ->
        progress(0.5f); out.writeBytes(ByteArray(bytes.toInt())); progress(1f)
    }

    private val temp get() = File(tmp.root, "temp")
    private val outDir get() = File(tmp.root, "out")
    private var id = 0
    private fun compressor(t: VideoTranscoder) = VideoCompressor(t, { temp }, { outDir }, { "id${id++}" })

    private fun video(size: Long, w: Int? = 1920, h: Int? = 1080, name: String? = "Klipp.MOV"): OutgoingAttachment {
        val f = File(tmp.newFolder().also { it.mkdirs() }, "vid-in.mov").apply { writeBytes(ByteArray(size.toInt())) }
        return OutgoingAttachment(f, "video/quicktime", "video", name, w, h, 12_000L, "thumb")
    }

    private fun leftovers() = temp.listFiles().orEmpty().toList()

    @Test fun `a large video is transcoded to a smaller mp4 that replaces the original`() = runTest {
        val progress = ArrayList<Float>()
        val t = FakeTranscoder(writer(3 * MB))
        val input = video(20 * MB, 3840, 2160)
        val result = compressor(t).compress(input, sendOriginal = false) { progress += it }
        val done = assertIs<VideoCompression.Compressed>(result)
        assertEquals(17 * MB, done.savedBytes)
        assertEquals("video/mp4", done.attachment.mime); assertEquals("video", done.attachment.kind)
        assertEquals("Klipp.mp4", done.attachment.name)
        assertEquals(1280, done.attachment.width); assertEquals(720, done.attachment.height)
        assertEquals(12_000L, done.attachment.durationMs); assertEquals("thumb", done.attachment.thumbnailBase64)
        assertTrue(done.attachment.file.isFile && done.attachment.file.parentFile == outDir)
        assertEquals(3 * MB, done.attachment.file.length())
        assertFalse(input.file.exists(), "the original copy is gone")
        assertTrue(leftovers().isEmpty(), "no temporary file is left")
        assertEquals(listOf(1280 to 720), t.targets.map { it.width to it.height })
        assertEquals(listOf(0.5f, 1f), progress)
    }

    @Test fun `the original toggle sends the file as it is and never starts the transcoder`() = runTest {
        val t = FakeTranscoder { _, _, _, _ -> error("must not run") }
        val input = video(20 * MB, 3840, 2160)
        val result = compressor(t).compress(input, sendOriginal = true)
        assertEquals(VideoCompression.Skipped(input, VideoSkip.ORIGINAL_REQUESTED), result)
        assertTrue(input.file.isFile); assertTrue(t.targets.isEmpty())
    }

    @Test fun `a small video at 1080p or less is left alone`() = runTest {
        val t = FakeTranscoder { _, _, _, _ -> error("must not run") }
        val input = video(4 * MB, 1920, 1080)
        assertEquals(VideoCompression.Skipped(input, VideoSkip.NOT_NEEDED), compressor(t).compress(input, false))
    }

    @Test fun `a result that is not smaller is dropped and the original is kept`() = runTest {
        val input = video(17 * MB, 1280, 720)
        val t = FakeTranscoder(writer(18 * MB))
        val result = compressor(t).compress(input, false)
        assertEquals(VideoCompression.Skipped(input, VideoSkip.NOT_SMALLER), result)
        assertTrue(input.file.isFile, "the original stays")
        assertTrue(leftovers().isEmpty())
        assertTrue(outDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun `an encoder failure falls back to the original and says so`() = runTest {
        val input = video(30 * MB, 3840, 2160)
        val t = FakeTranscoder { _, out, _, _ -> out.writeBytes(ByteArray(1000)); throw VideoTranscodeException("no encoder") }
        val result = compressor(t).compress(input, false)
        assertEquals(VideoCompression.Failed(input), result)
        assertTrue(input.file.isFile)
        assertTrue(leftovers().isEmpty(), "the partial output is removed")
    }

    @Test fun `a codec that throws something unexpected also falls back`() = runTest {
        val input = video(30 * MB, 3840, 2160)
        val t = FakeTranscoder { _, _, _, _ -> throw IllegalStateException("codec died") }
        assertEquals(VideoCompression.Failed(input), compressor(t).compress(input, false))
        val t2 = FakeTranscoder { _, _, _, _ -> throw java.io.IOException("disk full") }
        assertEquals(VideoCompression.Failed(input), compressor(t2).compress(input, false))
        assertTrue(input.file.isFile)
    }

    @Test fun `cancelling stops the transcode, removes the partial file and keeps the original`() = runTest {
        val input = video(30 * MB, 3840, 2160)
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val t = FakeTranscoder { _, out, _, _ ->
            out.writeBytes(ByteArray(100)); started.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } catch (e: CancellationException) {
                cancelled = true; throw e
            }
        }
        val job = launch { compressor(t).compress(input, false) }
        started.await()
        job.cancel()
        job.join()
        assertTrue(cancelled)
        assertTrue(input.file.isFile)
        assertTrue(leftovers().isEmpty())
    }

    @Test fun `a file that is not a video is never touched`() = runTest {
        val f = File(tmp.newFolder(), "doc.pdf").apply { writeBytes(ByteArray((20 * MB).toInt())) }
        val a = OutgoingAttachment(f, "application/pdf", "file", "doc.pdf")
        val t = FakeTranscoder { _, _, _, _ -> error("must not run") }
        assertEquals(VideoCompression.Skipped(a, VideoSkip.NOT_NEEDED), compressor(t).compress(a, false))
        assertNotNull(f.takeIf { it.isFile })
    }
}
