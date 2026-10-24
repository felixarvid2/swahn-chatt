package se.familychat.app.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Whether any activity of the app is visible (ProcessLifecycleOwner: STARTED = foreground). */
@Singleton
class AppForeground @Inject constructor() {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /** Must be called on the main thread (Application.onCreate). */
    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { _isForeground.value = true }
            override fun onStop(owner: LifecycleOwner) { _isForeground.value = false }
        })
    }

    /** For tests. */
    fun set(foreground: Boolean) { _isForeground.value = foreground }
}

/** The conversation currently on screen, for the notification suppression. */
@Singleton
class ActiveConversation @Inject constructor() {
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    fun enter(conversationId: String) { _current.value = conversationId }

    /** Only clears if [conversationId] is still the active one (screens overlap during transitions). */
    fun leave(conversationId: String) { _current.compareAndSet(conversationId, null) }
}
