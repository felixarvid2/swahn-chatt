package se.familychat.app.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import se.familychat.app.data.AppPrefs
import se.familychat.app.data.SessionManager
import se.familychat.app.data.SessionState
import se.familychat.app.data.ThemeMode
import se.familychat.app.ui.components.isInsecureUrl
import se.familychat.core.client.FamilyChatEngine
import java.io.File
import javax.inject.Inject

/** State of the window around all screens: sign-in state, theme, and whether the server address is plain http. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    prefs: AppPrefs,
    private val engine: FamilyChatEngine,
) : ViewModel() {
    val session: StateFlow<SessionState> = sessionManager.state

    val theme: StateFlow<ThemeMode> = prefs.theme

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
