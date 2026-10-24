package se.familychat.app.platform.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.familychat.app.data.AppPrefs
import se.familychat.app.data.LockTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The optional app lock: a gate in the UI (the database key does not depend on it, see SECURITY.md). While [locked] the
 * app shows only the lock screen and the window is secure. Notifications are not affected: they follow the notification
 * privacy setting.
 *
 * Rules:
 * - The app starts locked when the lock is on (cold start).
 * - Going to the background starts a timer; coming back locks if it ran for at least the [LockTimeout]. "Immediately"
 *   locks at once when the app goes to the background (so the recent apps preview is already hidden).
 * - A lock that is already showing has no timer; a successful unlock clears everything.
 * - Pickers and other apps the app starts itself (photo picker, file chooser, share sheet) are not "leaving": call
 *   [expectExternalReturn] before launching them and the next trip to the background does not lock, if the user is back
 *   within [EXTERNAL_GRACE_MS].
 *
 * [clock] must be monotonic (elapsed real time, which keeps counting while the phone sleeps).
 */
@Singleton
class AppLock internal constructor(private val prefs: AppPrefs, private val clock: () -> Long) {
    @Inject constructor(prefs: AppPrefs) : this(prefs, { SystemClock.elapsedRealtime() })

    private val _locked = MutableStateFlow(prefs.appLockEnabled.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt: Long? = null
    private var externalUntil = 0L
    private var external = false

    val enabled: Boolean get() = prefs.appLockEnabled.value

    /** The user proved themselves while turning the lock on: it starts unlocked. */
    @Synchronized fun enable() {
        prefs.setAppLockEnabled(true)
        _locked.value = false
        backgroundedAt = null
    }

    @Synchronized fun disable() {
        prefs.setAppLockEnabled(false)
        _locked.value = false
        backgroundedAt = null
    }

    @Synchronized fun unlock() {
        _locked.value = false
        backgroundedAt = null
        externalUntil = 0
    }

    @Synchronized fun expectExternalReturn() {
        externalUntil = clock() + EXTERNAL_GRACE_MS
    }

    @Synchronized fun onBackground() {
        val now = clock()
        external = now < externalUntil
        externalUntil = 0
        if (_locked.value || !enabled) {
            backgroundedAt = null
            return
        }
        backgroundedAt = now
        if (!external && prefs.lockTimeout.value == LockTimeout.IMMEDIATELY) _locked.value = true
    }

    @Synchronized fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (!enabled || _locked.value) return
        val away = clock() - since
        if (external && away < EXTERNAL_GRACE_MS) return
        if (shouldLock(away, prefs.lockTimeout.value)) _locked.value = true
    }

    companion object {
        const val EXTERNAL_GRACE_MS = 120_000L

        /** Whether an absence of [awayMillis] needs the lock. */
        fun shouldLock(awayMillis: Long, timeout: LockTimeout): Boolean = awayMillis >= timeout.millis
    }
}
