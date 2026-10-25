package se.swahnchat.app.media

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `dot names and hidden names become harmless`() {
        assertEquals("file", ShareFiles.safeName("."))
        assertEquals("file", ShareFiles.safeName(".."))
        assertEquals("file", ShareFiles.safeName("..."))
        assertEquals("file", ShareFiles.safeName(""))
        assertEquals("file", ShareFiles.safeName("   "))
        assertEquals("file", ShareFiles.safeName(null))
        assertEquals("_.hidden", ShareFiles.safeName(".hidden"))
        assertEquals("_.bashrc", ShareFiles.safeName(".bashrc"))
        assertEquals("fallback", ShareFiles.safeName("..", fallback = "fallback"))
    }

    @Test fun `separators and control characters are replaced`() {
        for (evil in listOf("../../etc/passwd", "a/b", "a\\b", "x\u0000y", "line\nbreak", "tab\tname", "rtl\u202Egnp.exe", "..\\..\\x")) {
            val safe = ShareFiles.safeName(evil)
            assertFalse(safe.any { it == '/' || it == '\\' || it.isISOControl() || it == '\u202E' }, "unsafe: ${safe.map { it.code }}")
            assertFalse(safe.startsWith("."), safe)
        }
        assertEquals("holiday.pdf", ShareFiles.safeName("holiday.pdf"))
        assertEquals("båt (1).jpg".replace("(", "_").replace(")", "_"), ShareFiles.safeName("båt (1).jpg"))
    }

    @Test fun `long names keep their extension`() {
        val safe = ShareFiles.safeName("a".repeat(300) + ".pdf")
        assertEquals(100, safe.length)
        assertTrue(safe.endsWith(".pdf"))
    }

    @Test fun `copying with a hostile name stays inside the share directory`() {
        val src = tmp.newFile("source.bin").apply { writeText("data") }
        val dir = File(tmp.root, "share")
        for (name in listOf(".", "..", "../escape", "/abs/path", ".hidden", "ok.txt", null, "")) {
            val copy = ShareFiles.copyForSharing(src, dir, name)
            assertEquals(dir.canonicalFile, copy.canonicalFile.parentFile, "name=$name")
            assertEquals("data", copy.readText())
            assertEquals(listOf(copy.name), dir.list()!!.toList(), "older copies are removed")
        }
        assertFalse(File(tmp.root, "escape").exists())
    }

    @Test fun `a missing source is an IOException the caller can report`() {
        assertFailsWith<IOException> { ShareFiles.copyForSharing(File(tmp.root, "nope"), File(tmp.root, "share"), "x") }
    }
}
