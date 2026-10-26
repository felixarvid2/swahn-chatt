package se.swahnchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.swahnchat.app.R
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.SessionManager
import se.swahnchat.app.data.UiError
import se.swahnchat.app.di.AppModule
import javax.inject.Inject

data class ResetPasswordUiState(
    val serverUrl: String = "",
    val code: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val deviceName: String = "",
    val passwordVisible: Boolean = false,
    val busy: Boolean = false,
    val error: UiError? = null,
    val fieldErrors: Set<FieldError> = emptySet(),
)

class ResetPasswordCallbacks(
    val onBack: () -> Unit = {},
    val onServer: (String) -> Unit = {},
    val onCode: (String) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onConfirm: (String) -> Unit = {},
    val onDeviceName: (String) -> Unit = {},
    val onTogglePassword: () -> Unit = {},
    val onSubmit: () -> Unit = {},
)

/**
 * "Glömt lösenordet?": redeems the code an administrator made (`POST /auth/password/reset {code, new_password, device_name}`). The code
 * identifies the account, so there is no username to type. The server revokes every old device and signs this phone in as a new device,
 * which the other people have to approve like any new device; this screen only collects the fields and never keeps the code or the password.
 */
@HiltViewModel
class ResetPasswordViewModel @Inject constructor(
    private val session: SessionManager,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val _state = MutableStateFlow(ResetPasswordUiState(serverUrl = prefs.serverUrl.value, deviceName = AppModule.defaultDeviceName()))
    val state: StateFlow<ResetPasswordUiState> = _state.asStateFlow()

    fun setServerUrl(v: String) {
        prefs.setServerUrl(v)
        _state.update { it.copy(serverUrl = v, error = null) }
    }

    fun setCode(v: String) = _state.update { it.copy(code = v, error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun setConfirm(v: String) = _state.update { it.copy(confirmPassword = v, error = null) }
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
            val error = session.resetPassword(s.serverUrl, s.code, s.password, s.deviceName)
            // on success the session turns to signed in and the app moves on; nothing typed is kept
            _state.update {
                it.copy(busy = false, error = error, password = if (error == null) "" else it.password,
                    confirmPassword = if (error == null) "" else it.confirmPassword, code = if (error == null) "" else it.code)
            }
        }
    }

    companion object {
        /** The rules of the registration form (docs/API.md): a password of 10 to 256 characters, typed twice. */
        fun validate(s: ResetPasswordUiState): Set<FieldError> {
            val e = LinkedHashSet<FieldError>()
            if (s.serverUrl.isBlank()) e += FieldError.URL
            if (s.code.isBlank()) e += FieldError.CODE
            if (s.password.length !in 10..256) e += FieldError.PASSWORD_SHORT
            if (s.password != s.confirmPassword) e += FieldError.PASSWORD_MISMATCH
            if (s.deviceName.isBlank() || s.deviceName.trim().length > 64) e += FieldError.DEVICE_NAME
            return e
        }
    }
}

@Composable
fun ResetPasswordScreen(onBack: () -> Unit, vm: ResetPasswordViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ResetPasswordContent(
        state,
        ResetPasswordCallbacks(
            onBack = onBack, onServer = vm::setServerUrl, onCode = vm::setCode, onPassword = vm::setPassword, onConfirm = vm::setConfirm,
            onDeviceName = vm::setDeviceName, onTogglePassword = vm::togglePasswordVisible, onSubmit = vm::submit,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetPasswordContent(state: ResetPasswordUiState, cb: ResetPasswordCallbacks, modifier: Modifier = Modifier) {
    val errorText = errorTexts()
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.reset_title)) }, navigationIcon = { BackButton(cb.onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.reset_intro), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.reset_consequences), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Field(
                state.serverUrl, cb.onServer, R.string.server_address, KeyboardType.Uri, error = FieldError.URL in state.fieldErrors,
                supporting = R.string.server_address_hint, capitalization = KeyboardCapitalization.None,
            )
            Field(
                state.code, cb.onCode, R.string.reset_code, KeyboardType.Password, error = FieldError.CODE in state.fieldErrors,
                supporting = R.string.reset_code_hint, capitalization = KeyboardCapitalization.None,
            )
            PasswordField(
                state.password, cb.onPassword, R.string.new_password, state.passwordVisible, cb.onTogglePassword,
                error = FieldError.PASSWORD_SHORT in state.fieldErrors, errorRes = R.string.field_error_password_short, supporting = R.string.password_hint,
            )
            PasswordField(
                state.confirmPassword, cb.onConfirm, R.string.confirm_password, state.passwordVisible, cb.onTogglePassword,
                error = FieldError.PASSWORD_MISMATCH in state.fieldErrors, errorRes = R.string.field_error_password_mismatch,
            )
            Field(
                state.deviceName, cb.onDeviceName, R.string.device_name, KeyboardType.Text, error = FieldError.DEVICE_NAME in state.fieldErrors,
                errorRes = R.string.field_error_device_name, supporting = R.string.device_name_hint, capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done, onDone = cb.onSubmit,
            )
            state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = cb.onSubmit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.reset_submit))
            }
        }
    }
}
