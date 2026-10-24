package se.familychat.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.familychat.app.data.AppPrefs
import se.familychat.app.data.VoiceSpeed
import se.familychat.app.ui.components.speedLabel
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class VoiceSpeedTest {
    @Test fun `the toggle goes 1x, 1_5x, 2x and around`() {
        assertEquals(1.5f, VoiceSpeed.next(1f))
        assertEquals(2f, VoiceSpeed.next(1.5f))
        assertEquals(1f, VoiceSpeed.next(2f))
        assertEquals(1.5f, VoiceSpeed.next(0.7f), "an unknown value counts as normal speed")
    }

    @Test fun `labels`() {
        assertEquals("1x", speedLabel(1f)); assertEquals("1.5x", speedLabel(1.5f)); assertEquals("2x", speedLabel(2f))
    }

    @Test fun `the chosen speed is remembered globally, also by a new process`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("familychat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = AppPrefs(context)
        assertEquals(1f, prefs.voiceSpeed.value)
        prefs.setVoiceSpeed(VoiceSpeed.next(prefs.voiceSpeed.value))
        assertEquals(1.5f, prefs.voiceSpeed.value)
        assertEquals(1.5f, AppPrefs(context).voiceSpeed.value, "read back from the preference file")
        prefs.setVoiceSpeed(3.3f)
        assertEquals(1f, AppPrefs(context).voiceSpeed.value, "garbage in the file falls back to normal speed")
    }
}
