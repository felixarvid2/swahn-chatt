package se.familychat.app.ui.screens

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.familychat.app.data.AppPrefs
import se.familychat.app.data.ServerCheck
import se.familychat.app.data.ServerChecker
import se.familychat.app.data.SessionManager
import se.familychat.app.data.UiError
import se.familychat.app.di.AppModule
import javax.inject.Inject

enum class AuthMode { LOGIN, REDEEM }

enum class FieldError { URL, USERNAME, PASSWORD_SHORT, PASSWORD_MISMATCH, DISPLAY_NAME, CODE, DEVICE_NAME, PASSWORD_EMPTY }

@Immutable
data class LoginUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val displayName: String = "",
    val code: String = "",
    val deviceName: String = "",
    val passwordVisible: Boolean = false,
    val busy: Boolean = false,
    val error: UiError? = null,
    val fieldErrors: Set<FieldError> = emptySet(),
    /** The server ended the previous session (device revoked, password changed elsewhere). */
    val sessionEnded: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val session: SessionManager,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val _state = MutableStateFlow(
        LoginUiState(serverUrl = prefs.serverUrl.value, deviceName = AppModule.defaultDeviceName(), sessionEnded = prefs.sessionEndedNotice),
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        // The server screen can change the address while this screen is below it on the back stack.
        viewModelScope.launch { prefs.serverUrl.collect { url -> _state.update { it.copy(serverUrl = url) } } }
    }

    fun setMode(mode: AuthMode) = _state.update { it.copy(mode = mode, error = null, fieldErrors = emptySet()) }
    fun setServerUrl(v: String) { prefs.setServerUrl(v); _state.update { it.copy(serverUrl = v, error = null) } }
    fun setUsername(v: String) = _state.update { it.copy(username = v.lowercase().trim(), error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun setConfirm(v: String) = _state.update { it.copy(confirmPassword = v, error = null) }
    fun setDisplayName(v: String) = _state.update { it.copy(displayName = v, error = null) }
    fun setCode(v: String) = _state.update { it.copy(code = v, error = null) }
    fun setDeviceName(v: String) = _state.update { it.copy(deviceName = v, error = null) }
    fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun submit() {
        val s = _state.value
        if (s.busy) return
        val errors = validate(s)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = errors) }
            return
        }
        _state.update { it.copy(busy = true, error = null, fieldErrors = emptySet()) }
        viewModelScope.launch {
            val error = if (s.mode == AuthMode.LOGIN) {
                session.login(s.serverUrl, s.username, s.password, s.deviceName)
            } else {
                session.redeem(s.serverUrl, s.code, s.username, s.password, s.displayName, s.deviceName)
            }
            _state.update { it.copy(busy = false, error = error, password = if (error == null) "" else it.password, confirmPassword = if (error == null) "" else it.confirmPassword) }
        }
    }

    companion object {
        private val USERNAME = Regex("^[a-z0-9_.-]{3,32}$")

        /** Client-side checks that mirror the server's rules (docs/API.md), so most mistakes never need a round trip. */
        fun validate(s: LoginUiState): Set<FieldError> {
            val e = LinkedHashSet<FieldError>()
            if (s.serverUrl.isBlank()) e += FieldError.URL
            if (!USERNAME.matches(s.username)) e += FieldError.USERNAME
            if (s.deviceName.isBlank() || s.deviceName.trim().length > 64) e += FieldError.DEVICE_NAME
            if (s.mode == AuthMode.LOGIN) {
                if (s.password.isEmpty()) e += FieldError.PASSWORD_EMPTY
            } else {
                if (s.code.isBlank()) e += FieldError.CODE
                if (s.displayName.isBlank() || s.displayName.trim().length > 64) e += FieldError.DISPLAY_NAME
                if (s.password.length !in 10..256) e += FieldError.PASSWORD_SHORT
                if (s.password != s.confirmPassword) e += FieldError.PASSWORD_MISMATCH
            }
            return e
        }
    }
}

@Immutable
data class ServerSettingsUiState(
    val url: String = "",
    val testing: Boolean = false,
    val result: ServerCheck? = null,
)

@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    private val prefs: AppPrefs,
    private val checker: ServerChecker,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerSettingsUiState(url = prefs.serverUrl.value))
    val state: StateFlow<ServerSettingsUiState> = _state.asStateFlow()

    fun setUrl(v: String) {
        prefs.setServerUrl(v)
        _state.update { it.copy(url = v, result = null) }
    }

    fun test() {
        val url = _state.value.url
        _state.update { it.copy(testing = true, result = null) }
        viewModelScope.launch {
            val r = checker.check(url)
            _state.update { it.copy(testing = false, result = r) }
        }
    }
}
