package se.swahnchat.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Image handling with Robolectric's native graphics: real decoding and encoding on the build machine. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var preparer: AttachmentPreparer

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preparer = AttachmentPreparer(context)
    }

    @After fun clean() {
        preparer.outgoingDir.deleteRecursively()
    }

    private fun noisyBitmap(w: Int, h: Int): Bitmap {
        val random = Random(7)
        val pixels = IntArray(w * h) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun write(bitmap: Bitmap, name: String, format: Bitmap.CompressFormat, quality: Int = 95): File =
        tmp.newFile(name).also { f -> FileOutputStream(f).use { bitmap.compress(format, quality, it) } }

    @Test fun `a large photo is scaled to 2048 pixels and re-encoded as jpeg with a small thumbnail`() = runBlocking {
        val source = write(noisyBitmap(3000, 1500), "big.jpg", Bitmap.CompressFormat.JPEG)
        val out = preparer.prepare(Uri.fromFile(source))
        assertEquals("image", out.kind)
        assertEquals("image/jpeg", out.mime)
        assertEquals(2048, out.width)
        assertEquals(1024, out.height)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(out.file.path, it) }
        assertEquals("image/jpeg", bounds.outMimeType)
        assertEquals(2048, bounds.outWidth)
        assertTrue(out.file.startsWith(preparer.outgoingDir), "kept in app-private storage")
        val thumb = out.thumbnailBase64!!
        assertTrue(thumb.length <= 10 * 1024, "thumbnail is ${thumb.length} characters of base64")
        val thumbBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { val b = Base64.getDecoder().decode(thumb); BitmapFactory.decodeByteArray(b, 0, b.size, it) }
        assertTrue(maxOf(thumbBounds.outWidth, thumbBounds.outHeight) <= 160)
    }

    @Test fun `the location, camera and time stamps of a photo are not sent along`() = runBlocking {
        val source = write(noisyBitmap(400, 300), "geo.jpg", Bitmap.CompressFormat.JPEG)
        ExifInterface(source.path).apply {
            setLatLong(59.3293, 18.0686)
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "12/1")
            setAttribute(ExifInterface.TAG_MAKE, "Fairphone")
            setAttribute(ExifInterface.TAG_MODEL, "FP5")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:24 14:30:00")
            setAttribute(ExifInterface.TAG_SOFTWARE, "Camera 1.0")
            saveAttributes()
        }
        val before = ExifInterface(source.path)
        assertNotNull(before.latLong, "the test picture does carry a position")
        val out = preparer.prepare(Uri.fromFile(source))
        val after = ExifInterface(out.file.path)
        assertNull(after.latLong)
        for (tag in listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_SOFTWARE,
        )) assertNull(after.getAttribute(tag), tag)
        // not even as bytes: no Exif segment, no GPS tag name, and the thumbnail is clean too
        val bytes = out.file.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Exif"), "no Exif segment in the file that is uploaded")
        val thumb = Base64.getDecoder().decode(out.thumbnailBase64!!)
        assertFalse(String(thumb, Charsets.ISO_8859_1).contains("Exif"))
        // a rotated camera picture comes out upright instead (the orientation is applied, not copied)
        val rotated = write(noisyBitmap(400, 200), "rot.jpg", Bitmap.CompressFormat.JPEG)
        ExifInterface(rotated.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val upright = preparer.prepare(Uri.fromFile(rotated))
        assertEquals(200, upright.width); assertEquals(400, upright.height)
        val orientation = ExifInterface(upright.file.path).getAttribute(ExifInterface.TAG_ORIENTATION)
        assertNull(orientation?.takeIf { it != "1" && it != "0" })
    }

    @Test fun `a small photo keeps its size and png transparency is flattened onto white`() = runBlocking {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888) // fully transparent
        val source = write(bitmap, "transparent.png", Bitmap.CompressFormat.PNG)
        val out = preparer.prepare(Uri.fromFile(source))
        assertEquals(64, out.width); assertEquals(32, out.height)
        val decoded = BitmapFactory.decodeFile(out.file.path)
        assertTrue(Color.red(decoded.getPixel(5, 5)) > 240 && Color.green(decoded.getPixel(5, 5)) > 240, "transparent pixels become white")
    }

    @Test fun `a thumbnail never exceeds the size limit even for noise`() {
        val thumb = preparer.thumbnailOf(noisyBitmap(1200, 900))
        assertNotNull(thumb)
        assertTrue(thumb.length <= AttachmentPreparer.MAX_THUMBNAIL_BASE64)
    }

    @Test fun `a document is copied with its name and a generic type`() = runBlocking {
        val doc = tmp.newFile("report.pdf").apply { writeBytes(ByteArray(2000) { it.toByte() }) }
        val out = preparer.prepare(Uri.fromFile(doc))
        assertEquals("file", out.kind)
        assertEquals("report.pdf", out.name)
        assertEquals(2000L, out.file.length())
        assertNull(out.thumbnailBase64)
    }

    @Test fun `an unreadable image is refused instead of crashing`() {
        val garbage = tmp.newFile("broken.jpg").apply { writeBytes(ByteArray(500) { 7 }) }
        assertFailsWith<java.io.IOException> { runBlocking { preparer.prepare(Uri.fromFile(garbage)) } }
    }

    @Test fun `avatars are small upright jpegs`() = runBlocking {
        val source = write(noisyBitmap(1600, 800), "me.png", Bitmap.CompressFormat.PNG)
        val bytes = preparer.avatarJpeg(Uri.fromFile(source))
        assertTrue(bytes.size < 2 * 1024 * 1024)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
        assertEquals("image/jpeg", o.outMimeType)
        assertEquals(512, o.outWidth)
    }

    @Test fun `cleanup removes only old orphans`() {
        val dir = preparer.outgoingDir
        val keep = File(dir, "keep.bin").apply { writeText("x"); setLastModified(0) }
        val orphan = File(dir, "orphan.bin").apply { writeText("x"); setLastModified(0) }
        val fresh = File(dir, "fresh.bin").apply { writeText("x") }
        preparer.cleanup(setOf(keep.absolutePath))
        assertTrue(keep.exists()); assertFalse(orphan.exists()); assertTrue(fresh.exists())
    }

    @Test fun `validation accepts real images and rejects disguised files`() {
        val png = write(noisyBitmap(40, 30), "real.png", Bitmap.CompressFormat.PNG)
        assertTrue(MediaValidator.isValid(png, "image"))
        val text = tmp.newFile("fake.jpg").apply { writeText("<html>not an image</html>") }
        assertFalse(MediaValidator.isValid(text, "image"))
        assertFalse(MediaValidator.isValid(tmp.newFile("empty.jpg"), "image"))
        assertFalse(MediaValidator.isValid(File(tmp.root, "missing.jpg"), "image"))
        assertFalse(MediaValidator.isValid(png, "video"), "a picture is not a video")
        assertTrue(MediaValidator.isValid(text, "file"), "generic files are never rendered, so their content is not judged")
    }
}
