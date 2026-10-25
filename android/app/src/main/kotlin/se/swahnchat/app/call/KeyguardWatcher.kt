package se.swahnchat.app.call

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone is locked, as a state the call screen reads, so that a name shown while the phone was unlocked disappears
 * when it locks (the keyguard state read once per recomposition would not change on its own). Between [start] and [stop]
 * (the activity's onStart and onStop) it follows `ACTION_SCREEN_OFF` (taken as locked at once: the keyguard state may lag
 * behind), `ACTION_SCREEN_ON` and `ACTION_USER_PRESENT` (read again). [isLocked] is the real keyguard state.
 */
class KeyguardWatcher(
    private val context: Context,
    private val isLocked: () -> Boolean = { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked },
) {
    private val _locked = MutableStateFlow(isLocked())

    /** True while the keyguard is up (or the screen has just been switched off). */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _locked.value = intent.action == Intent.ACTION_SCREEN_OFF || isLocked()
        }
    }

    fun start() {
        _locked.value = isLocked()
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun stop() {
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
    }
}
