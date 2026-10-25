package se.swahnchat.app.ui.screens

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.swahnchat.app.BuildConfig
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.ServerCheck
import se.swahnchat.app.data.ServerChecker
import se.swahnchat.app.data.SessionManager
import se.swahnchat.app.data.UiError
import se.swahnchat.app.di.AppModule
import se.swahnchat.app.qr.InviteLink
import se.swahnchat.app.qr.InviteParse
import se.swahnchat.app.qr.InviteQr
import se.swahnchat.app.qr.InviteRejection
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
    /**
     * An invitation read from a QR code. It belongs to the invitation tab only: there it replaces the server address and the
     * code, and it is never copied into [serverUrl] (the login tab) or saved. Nothing is sent until the person presses the button.
     */
    val invite: InviteLink? = null,
    /** Why the last scanned code was refused (shown under the scan button), or null. */
    val scanRejection: InviteRejection? = null,
) {
    /** The server address the form uses: the invitation's on the invitation tab while one was scanned, else the typed one. */
    val effectiveServer: String get() = if (mode == AuthMode.REDEEM && invite != null) invite.server else serverUrl
}

/**
 * The state after a QR code was scanned on the login screen. Only the invitation form is touched (tab, server and code of the
 * invitation); a code that is not a valid invitation changes nothing but the message. Never submits.
 */
fun LoginUiState.withScannedInvite(text: String, allowInsecureLan: Boolean): LoginUiState = when (val r = InviteQr.parse(text, allowInsecureLan)) {
    is InviteParse.Ok -> copy(mode = AuthMode.REDEEM, invite = r.invite, code = r.invite.code, error = null, fieldErrors = emptySet(), scanRejection = null)
    is InviteParse.Rejected -> copy(scanRejection = r.reason)
}

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
    fun setServerUrl(v: String) {
        // on the invitation tab with a scanned invitation the field shows (and edits) the invitation's server, not the saved login address
        if (_state.value.mode == AuthMode.REDEEM && _state.value.invite != null) {
            _state.update { s ->
                val edited = s.invite?.copy(server = v, host = InviteQr.hostOf(v).orEmpty(), suspiciousHost = InviteQr.isSuspiciousServer(v))
                s.copy(invite = edited, error = null)
            }
            return
        }
        prefs.setServerUrl(v)
        _state.update { it.copy(serverUrl = v, error = null) }
    }

    /** A QR code was scanned: pre-fills the invitation form if it is a valid invitation; the person still has to press the button. */
    fun onInviteScanned(text: String) = _state.update { it.withScannedInvite(text, BuildConfig.ALLOW_INSECURE_LAN) }

    fun dismissScanRejection() = _state.update { it.copy(scanRejection = null) }
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
                session.redeem(s.effectiveServer, s.code, s.username, s.password, s.displayName, s.deviceName)
            }
            _state.update { it.copy(busy = false, error = error, password = if (error == null) "" else it.password, confirmPassword = if (error == null) "" else it.confirmPassword) }
        }
    }

    companion object {
        private val USERNAME = Regex("^[a-z0-9_.-]{3,32}$")

        /** Client-side checks that mirror the server's rules (docs/API.md), so most mistakes never need a round trip. */
        fun validate(s: LoginUiState): Set<FieldError> {
            val e = LinkedHashSet<FieldError>()
            if (s.effectiveServer.isBlank()) e += FieldError.URL
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
