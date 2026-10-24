package se.familychat.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.familychat.app.data.AppPrefs
import se.familychat.app.data.LockTimeout
import se.familychat.app.platform.security.AppLock
import se.familychat.app.platform.security.Authenticator
import se.familychat.app.windowSecure
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the app lock closes: the timeout logic, with a clock the test moves. */
@RunWith(RobolectricTestRunner::class)
class AppLockTest {
    private lateinit var context: Context
    private lateinit var prefs: AppPrefs
    private var clock = 10_000L
    private val minute = 60_000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("familychat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPrefs(context)
    }

    private fun lock() = AppLock(prefs) { clock }

    private fun enabled(timeout: LockTimeout): AppLock {
        prefs.setLockTimeout(timeout)
        prefs.setAppLockEnabled(true)
        return lock().also { it.unlock() } // as after the first unlock
    }

    private fun AppLock.away(millis: Long) {
        onBackground(); clock += millis; onForeground()
    }

    @Test fun `the shared decision`() {
        assertTrue(AppLock.shouldLock(0, LockTimeout.IMMEDIATELY))
        assertFalse(AppLock.shouldLock(minute - 1, LockTimeout.MINUTE_1))
        assertTrue(AppLock.shouldLock(minute, LockTimeout.MINUTE_1))
        assertFalse(AppLock.shouldLock(5 * minute - 1, LockTimeout.MINUTES_5))
        assertTrue(AppLock.shouldLock(5 * minute, LockTimeout.MINUTES_5))
        assertFalse(AppLock.shouldLock(30 * minute - 1, LockTimeout.MINUTES_30))
        assertTrue(AppLock.shouldLock(30 * minute, LockTimeout.MINUTES_30))
    }

    @Test fun `without the lock nothing ever locks`() {
        val l = lock()
        l.away(100 * minute)
        assertFalse(l.locked.value)
    }

    @Test fun `with the lock on the app starts locked`() {
        prefs.setAppLockEnabled(true)
        assertTrue(lock().locked.value)
    }

    @Test fun `immediately locks as soon as the app goes to the background`() {
        val l = enabled(LockTimeout.IMMEDIATELY)
        assertFalse(l.locked.value)
        l.onBackground()
        assertTrue(l.locked.value, "locked before the recent apps preview is taken")
    }

    @Test fun `a timeout locks only after that long in the background`() {
        val l = enabled(LockTimeout.MINUTES_5)
        l.away(4 * minute)
        assertFalse(l.locked.value)
        l.away(5 * minute)
        assertTrue(l.locked.value)
        l.unlock()
        assertFalse(l.locked.value)
    }

    @Test fun `short trips add up to nothing and the timer starts again each time`() {
        val l = enabled(LockTimeout.MINUTE_1)
        repeat(5) { l.away(50_000) }
        assertFalse(l.locked.value)
    }

    @Test fun `a lock that is showing has no timer and a successful unlock is final`() {
        val l = enabled(LockTimeout.MINUTE_1)
        l.away(2 * minute)
        assertTrue(l.locked.value)
        l.onBackground() // e.g. the system's credential screen on old Android
        clock += 10 * minute
        l.unlock()
        l.onForeground()
        assertFalse(l.locked.value, "returning from the credential screen does not lock again")
    }

    @Test fun `a picker started by the app does not lock when the user comes back soon, but a long absence does`() {
        val l = enabled(LockTimeout.IMMEDIATELY)
        l.expectExternalReturn()
        l.away(30_000)
        assertFalse(l.locked.value)
        // the allowance is used up: the next trip locks
        l.away(1_000)
        assertTrue(l.locked.value)
        l.unlock()
        l.expectExternalReturn()
        l.away(AppLock.EXTERNAL_GRACE_MS)
        assertTrue(l.locked.value, "gone for longer than the allowance")
    }

    @Test fun `an allowance that was not used does not weaken a later trip`() {
        val l = enabled(LockTimeout.IMMEDIATELY)
        l.expectExternalReturn()
        clock += 3 * minute // the picker never opened or was long closed
        l.away(1_000)
        assertTrue(l.locked.value)
    }

    @Test fun `turning the lock off or on unlocks and the setting is remembered`() {
        val l = lock()
        l.enable()
        assertFalse(l.locked.value)
        assertTrue(AppPrefs(context).appLockEnabled.value)
        l.disable()
        assertFalse(AppPrefs(context).appLockEnabled.value)
        assertFalse(lock().locked.value)
    }

    @Test fun `the window is secure when screenshots are blocked or the app is locked`() {
        assertFalse(windowSecure(blockScreenshots = false, locked = false))
        assertTrue(windowSecure(blockScreenshots = true, locked = false))
        assertTrue(windowSecure(blockScreenshots = false, locked = true))
        assertTrue(windowSecure(blockScreenshots = true, locked = true))
    }

    @Test fun `preferences have safe defaults and survive a restart`() {
        assertEquals(se.familychat.app.data.NotificationPrivacy.FULL, prefs.notificationPrivacy.value)
        assertFalse(prefs.blockScreenshots.value)
        assertFalse(prefs.appLockEnabled.value)
        assertEquals(LockTimeout.IMMEDIATELY, prefs.lockTimeout.value)
        prefs.setNotificationPrivacy(se.familychat.app.data.NotificationPrivacy.HIDDEN)
        prefs.setBlockScreenshots(true)
        prefs.setLockTimeout(LockTimeout.MINUTES_30)
        val again = AppPrefs(context)
        assertEquals(se.familychat.app.data.NotificationPrivacy.HIDDEN, again.notificationPrivacy.value)
        assertTrue(again.blockScreenshots.value)
        assertEquals(LockTimeout.MINUTES_30, again.lockTimeout.value)
        context.getSharedPreferences("familychat_prefs", Context.MODE_PRIVATE).edit().putString("notification_privacy", "garbage").commit()
        assertEquals(se.familychat.app.data.NotificationPrivacy.FULL, AppPrefs(context).notificationPrivacy.value, "a damaged value yields the default")
    }

    @Test fun `which authenticators the lock asks for depends on the Android version`() {
        assertEquals(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            Authenticator.authenticators(30),
        )
        assertEquals(androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL, Authenticator.authenticators(26))
        assertEquals(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            Authenticator.authenticators(29),
        )
    }
}
