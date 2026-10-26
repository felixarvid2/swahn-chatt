package se.swahnchat.app

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.swahnchat.app.data.Accent
import se.swahnchat.app.data.AppLanguage
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.ChatBackground
import se.swahnchat.app.data.QuietHoursSettings
import java.time.DayOfWeek
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The in-app language (item 91) and the other preferences of batch 7: defaults, persistence, and that the strings really switch. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "sv")
class AppLocaleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        context.getSharedPreferences("swahnchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun settingsTitle(c: Context) = c.getString(R.string.settings)

    @Test fun `the phone is Swedish and the default language follows it`() {
        assertEquals("Inställningar", settingsTitle(context))
        assertEquals(AppLanguage.SYSTEM, AppPrefs(context).language)
    }

    @Test fun `Android 12 and older - the chosen language is put on top of the base context`() {
        val prefs = AppPrefs(context)
        prefs.language = AppLanguage.ENGLISH
        assertEquals("Settings", settingsTitle(AppLocale.wrap(context, sdk = 31)))
        prefs.language = AppLanguage.SWEDISH
        assertEquals("Inställningar", settingsTitle(AppLocale.wrap(context, sdk = 31)))
        prefs.language = AppLanguage.SYSTEM
        assertSame(context, AppLocale.wrap(context, sdk = 31), "the phone's own language leaves the context alone")
    }

    @Test fun `Android 13 and newer - the system does it, the context is left alone`() {
        AppPrefs(context).language = AppLanguage.ENGLISH
        assertSame(context, AppLocale.wrap(context, sdk = 33))
    }

    @Test fun `the choice is kept and read back by a new start`() {
        AppPrefs(context).language = AppLanguage.ENGLISH
        assertEquals(AppLanguage.ENGLISH, AppPrefs(context).language)
        assertEquals("Settings", settingsTitle(AppLocale.wrap(context, sdk = 30)), "read before Hilt exists, straight from the file")
    }

    @Test fun `a damaged preference leaves the phone's language`() {
        context.getSharedPreferences("swahnchat_prefs", Context.MODE_PRIVATE).edit().putString("language", "KLINGON").commit()
        assertEquals(AppLanguage.SYSTEM, AppPrefs(context).language)
        assertSame(context, AppLocale.wrap(context, sdk = 30))
    }

    @Test fun `set on Android 13 changes the application locales and current reports them`() {
        val prefs = AppPrefs(context)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppLocale.set(activity, prefs, AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, AppLocale.current(activity))
        assertEquals(AppLanguage.ENGLISH, prefs.language)
        AppLocale.set(activity, prefs, AppLanguage.SYSTEM)
        assertEquals(AppLanguage.SYSTEM, AppLocale.current(activity))
    }

    @Test fun `language tags map back`() {
        assertEquals(AppLanguage.SWEDISH, AppLanguage.fromTag("sv"))
        assertEquals(AppLanguage.SWEDISH, AppLanguage.fromTag("sv-SE"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-GB"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
    }

    @Test fun `privacy and appearance preferences have their defaults and survive a restart`() {
        val p = AppPrefs(context)
        assertTrue(p.sendReadReceipts.value)
        assertTrue(p.showTyping.value)
        assertFalse(p.highContrast.value)
        assertEquals(Accent.SYSTEM, p.accent.value)
        assertEquals(ChatBackground.PLAIN, p.chatBackground.value)
        assertEquals(QuietHoursSettings(), p.quietHours.value)
        p.setSendReadReceipts(false); p.setShowTyping(false); p.setHighContrast(true)
        p.setAccent(Accent.ROSE); p.setChatBackground(ChatBackground.DOTS)
        p.setQuietHours(QuietHoursSettings(true, 23 * 60 + 30, 6 * 60 + 15, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), muteCalls = true))
        val again = AppPrefs(context)
        assertFalse(again.sendReadReceipts.value)
        assertFalse(again.showTyping.value)
        assertTrue(again.highContrast.value)
        assertEquals(Accent.ROSE, again.accent.value)
        assertEquals(ChatBackground.DOTS, again.chatBackground.value)
        assertEquals(QuietHoursSettings(true, 23 * 60 + 30, 6 * 60 + 15, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), true), again.quietHours.value)
    }

    @Test fun `every string has an English translation`() {
        // the keys that batch 7 added exist in both languages (a missing English one would fall back to Swedish)
        val en = AppLocale.wrap(AppPrefs(context).also { it.language = AppLanguage.ENGLISH }.let { context }, sdk = 30)
        for (id in listOf(R.string.quiet_hours, R.string.read_receipts, R.string.show_typing, R.string.share_presence, R.string.high_contrast,
            R.string.accent_color, R.string.chat_background, R.string.language_system, R.string.select_conversation, R.string.presence_online)) {
            val sameInBoth = id == R.string.language_system || id == R.string.presence_online
            assertFalse(en.getString(id) == context.getString(id) && !sameInBoth, "no English text for $id")
        }
    }
}
