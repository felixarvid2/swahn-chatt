package se.familychat.app.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the share target accepts from other apps: only content://, with limits, under safe names. */
@RunWith(RobolectricTestRunner::class)
class ShareIntakeTest {
    private lateinit var context: Context
    private lateinit var staging: File
    private val own = "se.familychat.app.debug"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        staging = File(context.cacheDir, "staging-test").apply { deleteRecursively(); mkdirs() }
    }

    private fun send(vararg uris: Uri, text: String? = null, multiple: Boolean = uris.size > 1) =
        Intent(if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            if (multiple) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
            else uris.firstOrNull()?.let { putExtra(Intent.EXTRA_STREAM, it) }
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }

    // --- the intent ----------------------------------------------------------------------------------------------------------------

    @Test fun `text alone, one file and several files are read`() {
        assertEquals("hej", ShareIntake.parse(send(text = "hej"), own).text)
        val one = ShareIntake.parse(send(Uri.parse("content://media/external/images/1")), own)
        assertEquals(listOf(Uri.parse("content://media/external/images/1")), one.uris)
        val many = ShareIntake.parse(send(Uri.parse("content://a/1"), Uri.parse("content://a/2"), Uri.parse("content://b/3")), own)
        assertEquals(3, many.uris.size)
        assertEquals(0, many.refused)
    }

    @Test fun `file URIs and every other scheme are refused`() {
        val parsed = ShareIntake.parse(
            send(
                Uri.parse("file:///data/data/se.familychat.app/databases/familychat.db"), Uri.parse("file:///sdcard/x.jpg"),
                Uri.parse("http://evil.example/x"), Uri.parse("android.resource://se.familychat.app/raw/x"), Uri.parse("content://ok/1"),
                Uri.parse("/just/a/path"), Uri.parse("javascript:alert(1)"),
            ),
            own,
        )
        assertEquals(listOf(Uri.parse("content://ok/1")), parsed.uris)
        assertEquals(6, parsed.refused)
        // a single refused file leaves nothing to share
        val none = ShareIntake.parse(send(Uri.parse("file:///etc/hosts")), own)
        assertTrue(none.isEmpty)
        assertEquals(1, none.refused)
    }

    @Test fun `this app's own providers are refused, so another app cannot make us read and send our own files`() {
        for (u in listOf("content://$own.files/share/x.pdf", "content://$own/x", "content://${own.uppercase()}.files/x")) {
            assertEquals(emptyList(), ShareIntake.parse(send(Uri.parse(u)), own).uris, u)
        }
        // a similar-looking foreign authority is fine
        assertEquals(1, ShareIntake.parse(send(Uri.parse("content://se.familychat.appx/1")), own).uris.size)
    }

    @Test fun `other actions are ignored, and so is nothing at all`() {
        assertTrue(ShareIntake.parse(Intent(Intent.ACTION_VIEW, Uri.parse("content://a/1")), own).isEmpty)
        assertTrue(ShareIntake.parse(Intent(Intent.ACTION_MAIN), own).isEmpty)
        assertTrue(ShareIntake.parse(null, own).isEmpty)
        assertTrue(ShareIntake.parse(send(), own).isEmpty)
    }

    @Test fun `at most ten files are taken, text is cut at its limit`() {
        val uris = (1..14).map { Uri.parse("content://a/$it") }
        val parsed = ShareIntake.parse(send(*uris.toTypedArray()), own)
        assertEquals(10, parsed.uris.size)
        assertTrue(parsed.truncated)
        assertEquals(4, parsed.refused)
        val long = "å".repeat(30_000) // two bytes each
        val text = ShareIntake.parse(send(text = long), own).text!!
        assertTrue(text.toByteArray().size <= ShareIntake.MAX_TEXT_BYTES)
        assertTrue(text.length > 19_000)
    }

    @Test fun `files that only arrive in the clip data are found, once`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://a/1"))
            clipData = ClipData.newRawUri("x", Uri.parse("content://a/1")).also { it.addItem(ClipData.Item(Uri.parse("content://a/2"))) }
        }
        assertEquals(listOf("content://a/1", "content://a/2"), ShareIntake.parse(intent, own).uris.map { it.toString() })
    }

    // --- copying ---------------------------------------------------------------------------------------------------------------------

    private fun register(uri: String, bytes: ByteArray): Uri {
        val u = Uri.parse(uri)
        Shadows.shadowOf(context.contentResolver).registerInputStream(u, ByteArrayInputStream(bytes))
        return u
    }

    @Test fun `a file is copied into a folder of its own under a sanitised name`() {
        val uri = register("content://docs/1", "pdf-bytes".toByteArray())
        val file = ShareIntake.stage(context.contentResolver, uri, staging, 1_000_000)
        assertEquals("pdf-bytes", file.readText())
        assertEquals(staging.canonicalFile, file.canonicalFile.parentFile.parentFile)
        assertTrue(file.name.isNotBlank() && '/' !in file.name)
    }

    @Test fun `a file larger than the limit is refused and leaves nothing behind`() {
        val uri = register("content://docs/big", ByteArray(5_000) { 1 })
        assertFailsWith<ShareRefusedException> { ShareIntake.stage(context.contentResolver, uri, staging, 4_999) }
        assertTrue(staging.walkTopDown().none { it.isFile }, "no partial copy")
        // exactly at the limit is fine
        val ok = register("content://docs/ok", ByteArray(5_000) { 1 })
        assertEquals(5_000L, ShareIntake.stage(context.contentResolver, ok, staging, 5_000).length())
    }

    @Test fun `no more than the limit plus one byte is ever read from the source`() {
        class Endless : InputStream() {
            var taken = 0L
            override fun read(): Int { taken++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { taken += len; java.util.Arrays.fill(b, off, off + len, 1); return len }
        }
        val source = Endless()
        val target = File(staging, "endless")
        assertFailsWith<ShareRefusedException> { ShareIntake.copyLimited(source, target, 100_000) }
        assertTrue(source.taken <= 100_001, "took ${source.taken}")
    }

    @Test fun `names with path tricks end up as plain names`() {
        for (hostile in listOf("../../../etc/passwd", "..\\..\\x", "/absolute/path.txt", ".hidden", "a\u0000b.txt", "x\u202Egnp.exe", "", "...")) {
            val safe = ShareFiles.safeName(hostile)
            val bidi = "\u202E"
            val harmless = '/' !in safe && '\\' !in safe && !safe.startsWith(".") && "\u0000" !in safe && bidi !in safe && safe.isNotBlank()
            assertTrue(harmless, "'$hostile' -> '$safe'")
        }
        assertEquals("pdf.pdf", ShareIntake.nameWithExtension("pdf", "application/pdf"))
        assertEquals("foto.jpg", ShareIntake.nameWithExtension("foto.jpg", "image/jpeg"))
    }

    @Test fun `a source that cannot be opened is a refusal, not a crash`() {
        assertFailsWith<ShareRefusedException> { ShareIntake.stage(context.contentResolver, Uri.parse("content://nobody/1"), staging, 1000) }
        assertNull(null)
        assertNotNull(staging)
        assertFalse(staging.walkTopDown().any { it.isFile })
    }
}
