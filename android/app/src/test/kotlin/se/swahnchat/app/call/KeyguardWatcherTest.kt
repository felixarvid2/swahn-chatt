package se.swahnchat.app.call

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The call screen re-evaluates "may the name be shown" when the phone locks: the watcher follows the screen and keyguard broadcasts. */
@RunWith(RobolectricTestRunner::class)
class KeyguardWatcherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var keyguardUp = false

    private fun send(action: String) {
        context.sendBroadcast(Intent(action))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun `the state follows screen off, screen on and user present`() {
        val watcher = KeyguardWatcher(context) { keyguardUp }
        watcher.start()
        assertFalse(watcher.locked.value)
        send(Intent.ACTION_SCREEN_OFF) // locked at once, although the keyguard state lags behind
        assertTrue(watcher.locked.value)
        keyguardUp = true
        send(Intent.ACTION_SCREEN_ON)
        assertTrue(watcher.locked.value, "the screen is on again but still behind the keyguard")
        keyguardUp = false
        send(Intent.ACTION_USER_PRESENT)
        assertFalse(watcher.locked.value)
        watcher.stop()
    }

    @Test fun `a screen that goes off and on without a lock is not locked afterwards`() {
        val watcher = KeyguardWatcher(context) { false }
        watcher.start()
        send(Intent.ACTION_SCREEN_OFF)
        assertTrue(watcher.locked.value)
        send(Intent.ACTION_SCREEN_ON)
        assertFalse(watcher.locked.value)
        watcher.stop()
    }

    @Test fun `start reads the current state, stop ends the listening and both may be repeated`() {
        keyguardUp = true
        val watcher = KeyguardWatcher(context) { keyguardUp }
        watcher.start()
        watcher.start()
        assertTrue(watcher.locked.value)
        watcher.stop()
        keyguardUp = false
        send(Intent.ACTION_USER_PRESENT)
        assertTrue(watcher.locked.value, "stopped: no longer follows")
        watcher.stop()
        watcher.start()
        assertEquals(false, watcher.locked.value)
        watcher.stop()
    }
}
