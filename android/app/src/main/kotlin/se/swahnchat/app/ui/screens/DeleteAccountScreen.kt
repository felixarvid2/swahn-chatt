package se.swahnchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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
import se.swahnchat.app.data.SessionManager
import se.swahnchat.app.data.UiError
import javax.inject.Inject

enum class DeleteStep { EXPLAIN, PASSWORD }

data class DeleteAccountUiState(
    val step: DeleteStep = DeleteStep.EXPLAIN,
    val password: String = "",
    val passwordVisible: Boolean = false,
    val busy: Boolean = false,
    val error: UiError? = null,
)

class DeleteAccountCallbacks(
    val onBack: () -> Unit = {},
    val onContinue: () -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onTogglePassword: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/**
 * Two steps: what deleting does, then the password. Nothing leaves the phone before the second step, and the phone is wiped only when
 * the server has accepted (a wrong password, `last_admin` or a rate limit leave everything as it was).
 */
@HiltViewModel
class DeleteAccountViewModel @Inject constructor(private val session: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow(DeleteAccountUiState())
    val state: StateFlow<DeleteAccountUiState> = _state.asStateFlow()

    fun onContinue() = _state.update { it.copy(step = DeleteStep.PASSWORD, error = null) }
    fun onBackToExplanation() = _state.update { it.copy(step = DeleteStep.EXPLAIN, password = "", error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun togglePassword() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun delete() {
        val s = _state.value
        if (s.busy || s.password.isEmpty()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val error = session.deleteAccount(s.password)
            // on success the session turns to signed out and the app shows the start screen; this screen goes with it
            _state.update { it.copy(busy = false, error = error, password = if (error == null) "" else it.password) }
        }
    }
}

@Composable
fun DeleteAccountScreen(onBack: () -> Unit, vm: DeleteAccountViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    DeleteAccountContent(
        state,
        DeleteAccountCallbacks(
            onBack = { if (state.step == DeleteStep.PASSWORD && !state.busy) vm.onBackToExplanation() else onBack() },
            onContinue = vm::onContinue, onPassword = vm::setPassword, onTogglePassword = vm::togglePassword, onDelete = vm::delete,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteAccountContent(state: DeleteAccountUiState, cb: DeleteAccountCallbacks, modifier: Modifier = Modifier) {
    val errorText = errorTexts()
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.delete_account_title)) }, navigationIcon = { BackButton(cb.onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
                Text(
                    stringResource(if (state.step == DeleteStep.EXPLAIN) R.string.delete_account_step1 else R.string.delete_account_step2),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp).semantics { heading() },
                )
            }
            when (state.step) {
                DeleteStep.EXPLAIN -> {
                    Text(stringResource(R.string.delete_account_intro), style = MaterialTheme.typography.bodyLarge)
                    listOf(
                        R.string.delete_account_effect_devices, R.string.delete_account_effect_chats, R.string.delete_account_effect_messages,
                        R.string.delete_account_effect_files, R.string.delete_account_effect_phone, R.string.delete_account_effect_admin,
                    ).forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                    Text(stringResource(R.string.delete_account_irreversible), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = cb.onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text(stringResource(R.string.delete_account_continue)) }
                    OutlinedButton(onClick = cb.onBack,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
                }
                DeleteStep.PASSWORD -> {
                    Text(stringResource(R.string.delete_account_password_text), style = MaterialTheme.typography.bodyLarge)
                    PasswordField(
                        state.password, cb.onPassword, R.string.password, state.passwordVisible, cb.onTogglePassword,
                        error = state.error == UiError.WRONG_PASSWORD, errorRes = R.string.error_wrong_password, imeAction = ImeAction.Done,
                    )
                    state.error?.takeIf { it != UiError.WRONG_PASSWORD }?.let {
                        Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = cb.onDelete, enabled = !state.busy && state.password.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError),
                    ) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                        else Text(stringResource(R.string.delete_account_confirm))
                    }
                    OutlinedButton(onClick = cb.onBack, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.action_back)) }
                }
            }
        }
    }
}
