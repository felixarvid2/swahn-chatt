package se.swahnchat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AutoDownloadTest {
    private val defaults = AutoDownloadSettings()
    private val mb = 1024L * 1024

    @Test fun `defaults - photos and voice always, video and files only on an unmetered network`() {
        assertEquals(AutoDownloadMode.ALWAYS, defaults.modeFor("image"))
        assertEquals(AutoDownloadMode.ALWAYS, defaults.modeFor("voice"))
        assertEquals(AutoDownloadMode.WIFI_ONLY, defaults.modeFor("video"))
        assertEquals(AutoDownloadMode.WIFI_ONLY, defaults.modeFor("file"))
        assertEquals(AutoDownloadMode.WIFI_ONLY, defaults.modeFor("something-new"), "an unknown kind is treated as a file")
    }

    @Test fun `the decision follows the mode and whether the network is metered`() {
        for (metered in listOf(true, false)) {
            assertTrue(AutoDownloadPolicy.shouldDownload(defaults, "image", mb, metered))
            assertTrue(AutoDownloadPolicy.shouldDownload(defaults, "voice", mb, metered))
        }
        assertFalse(AutoDownloadPolicy.shouldDownload(defaults, "video", mb, metered = true))
        assertTrue(AutoDownloadPolicy.shouldDownload(defaults, "video", mb, metered = false))
        assertFalse(AutoDownloadPolicy.shouldDownload(defaults, "file", mb, metered = true))
        assertTrue(AutoDownloadPolicy.shouldDownload(defaults, "file", mb, metered = false))

        val never = AutoDownloadSettings(AutoDownloadMode.NEVER, AutoDownloadMode.NEVER, AutoDownloadMode.NEVER, AutoDownloadMode.NEVER)
        for (kind in listOf("image", "voice", "video", "file")) for (metered in listOf(true, false)) {
            assertFalse(AutoDownloadPolicy.shouldDownload(never, kind, 1, metered), "$kind never")
        }
        val always = AutoDownloadSettings(AutoDownloadMode.ALWAYS, AutoDownloadMode.ALWAYS, AutoDownloadMode.ALWAYS, AutoDownloadMode.ALWAYS)
        for (kind in listOf("image", "voice", "video", "file")) for (metered in listOf(true, false)) {
            assertTrue(AutoDownloadPolicy.shouldDownload(always, kind, 1, metered), "$kind always")
        }
    }

    @Test fun `big attachments are never fetched by themselves, whatever the mode`() {
        val always = AutoDownloadSettings(AutoDownloadMode.ALWAYS, AutoDownloadMode.ALWAYS, AutoDownloadMode.ALWAYS, AutoDownloadMode.ALWAYS)
        for (kind in listOf("image", "voice", "video", "file")) {
            val limit = AutoDownloadPolicy.maxBytes(kind)
            assertTrue(AutoDownloadPolicy.shouldDownload(always, kind, limit, metered = false), kind)
            assertFalse(AutoDownloadPolicy.shouldDownload(always, kind, limit + 1, metered = false), kind)
        }
    }

    @Test fun `changing one kind leaves the others`() {
        val s = defaults.with("video", AutoDownloadMode.NEVER).with("file", AutoDownloadMode.ALWAYS).with("image", AutoDownloadMode.WIFI_ONLY)
        assertEquals(AutoDownloadSettings(AutoDownloadMode.WIFI_ONLY, AutoDownloadMode.ALWAYS, AutoDownloadMode.NEVER, AutoDownloadMode.ALWAYS), s)
    }

    @Test fun `the settings are remembered, a damaged value falls back to the default`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPrefs(context)
        assertEquals(defaults, prefs.autoDownload.value)
        prefs.setAutoDownload("video", AutoDownloadMode.NEVER)
        prefs.setAutoDownload("image", AutoDownloadMode.WIFI_ONLY)
        assertEquals(AutoDownloadMode.NEVER, prefs.autoDownload.value.video)
        // a new AppPrefs (app restart) reads them back
        val again = AppPrefs(context).autoDownload.value
        assertEquals(AutoDownloadMode.NEVER, again.video)
        assertEquals(AutoDownloadMode.WIFI_ONLY, again.images)
        assertEquals(AutoDownloadMode.ALWAYS, again.voice)
        assertEquals(AutoDownloadMode.WIFI_ONLY, again.files)
        context.getSharedPreferences("swahnchat_prefs", Context.MODE_PRIVATE).edit().putString("auto_download_video", "SOMETIMES").commit()
        assertEquals(AutoDownloadMode.WIFI_ONLY, AppPrefs(context).autoDownload.value.video)
    }
}
