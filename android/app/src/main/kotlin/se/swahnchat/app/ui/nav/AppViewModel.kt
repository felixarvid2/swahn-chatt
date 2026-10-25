package se.swahnchat.app.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.SessionManager
import se.swahnchat.app.data.SessionState
import se.swahnchat.app.data.ThemeMode
import se.swahnchat.app.platform.security.AppLock
import se.swahnchat.app.ui.components.isInsecureUrl
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.CallState
import java.io.File
import javax.inject.Inject

/** State of the window around all screens: sign-in state, theme, and whether the server address is plain http. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    prefs: AppPrefs,
    private val engine: SwahnChatEngine,
    private val lock: AppLock,
    calls: CallManager,
) : ViewModel() {
    val session: StateFlow<SessionState> = sessionManager.state

    val theme: StateFlow<ThemeMode> = prefs.theme

    /** True while the app lock hides the app (only with an account). */
    val locked: StateFlow<Boolean> = combine(lock.locked, sessionManager.state) { l, s -> l && s is SessionState.SignedIn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, lock.locked.value)

    /** The call in progress (ringing or running), for the bar at the top of the app; null between calls. */
    val activeCall: StateFlow<CallState.InCall?> = calls.state.map { it as? CallState.InCall }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun unlock() = lock.unlock()

    fun retryStorage() = sessionManager.retryRestore()

    /** True while the address in use (the account's, or the one typed on the login screen) starts with http://. */
    val insecure: StateFlow<Boolean> = combine(sessionManager.state, prefs.serverUrl) { s, typed ->
        isInsecureUrl(if (s is SessionState.SignedIn) s.account.serverUrl else typed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    suspend fun avatarFile(attachmentId: String): File? = try {
        engine.avatarFile(attachmentId)
    } catch (e: Exception) {
        null
    }
}
