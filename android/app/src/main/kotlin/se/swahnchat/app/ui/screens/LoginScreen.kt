package se.swahnchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.swahnchat.app.R
import se.swahnchat.app.qr.InviteLink
import se.swahnchat.app.qr.InviteQr
import se.swahnchat.app.qr.InviteRejection
import se.swahnchat.app.ui.components.QrScannerDialog

@Composable
fun LoginScreen(onServerSettings: () -> Unit, onForgotPassword: () -> Unit = {}, vm: LoginViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LoginContent(
        state = state,
        callbacks = LoginCallbacks(
            onMode = vm::setMode, onServer = vm::setServerUrl, onUsername = vm::setUsername, onPassword = vm::setPassword,
            onConfirm = vm::setConfirm, onDisplayName = vm::setDisplayName, onCode = vm::setCode, onDeviceName = vm::setDeviceName,
            onTogglePassword = vm::togglePasswordVisible, onSubmit = vm::submit, onServerSettings = onServerSettings,
            onInviteScanned = vm::onInviteScanned, onDismissScanRejection = vm::dismissScanRejection, onForgotPassword = onForgotPassword,
        ),
    )
}

class LoginCallbacks(
    val onMode: (AuthMode) -> Unit = {},
    val onServer: (String) -> Unit = {},
    val onUsername: (String) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onConfirm: (String) -> Unit = {},
    val onDisplayName: (String) -> Unit = {},
    val onCode: (String) -> Unit = {},
    val onDeviceName: (String) -> Unit = {},
    val onTogglePassword: () -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onServerSettings: () -> Unit = {},
    /** The text of a scanned QR code (untrusted; the view model checks it and only pre-fills the invitation form). */
    val onInviteScanned: (String) -> Unit = {},
    val onDismissScanRejection: () -> Unit = {},
    /** Opens the screen that redeems a reset code from an administrator. */
    val onForgotPassword: () -> Unit = {},
)

@Composable
fun LoginContent(state: LoginUiState, callbacks: LoginCallbacks, modifier: Modifier = Modifier) {
    val errorText = errorTexts()
    var scanning by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 12.dp))
            }
            Text(stringResource(R.string.login_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (state.sessionEnded) {
                Text(
                    stringResource(R.string.error_session_ended), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.mode == AuthMode.LOGIN, onClick = { callbacks.onMode(AuthMode.LOGIN) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.login_tab_login)) }
                SegmentedButton(
                    selected = state.mode == AuthMode.REDEEM, onClick = { callbacks.onMode(AuthMode.REDEEM) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.login_tab_invite)) }
            }

            if (state.mode == AuthMode.REDEEM) {
                OutlinedButton(
                    onClick = { callbacks.onDismissScanRejection(); scanning = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.scan_invitation), modifier = Modifier.padding(start = 8.dp))
                }
                state.scanRejection?.let {
                    Text(stringResource(inviteRejectionText(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                state.invite?.let { InviteHostCard(it, state.effectiveServer) }
            }

            Field(
                state.effectiveServer, callbacks.onServer, R.string.server_address, KeyboardType.Uri,
                error = FieldError.URL in state.fieldErrors, errorRes = R.string.field_error_required,
                supporting = R.string.server_address_hint, capitalization = KeyboardCapitalization.None,
            )
            if (state.invite == null || state.mode != AuthMode.REDEEM) {
                TextButton(onClick = callbacks.onServerSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.connection_settings))
                }
            }

            if (state.mode == AuthMode.REDEEM) {
                Field(state.code, callbacks.onCode, R.string.invitation_code, KeyboardType.Ascii, error = FieldError.CODE in state.fieldErrors,
                    errorRes = R.string.field_error_required, capitalization = KeyboardCapitalization.None)
                Field(state.displayName, callbacks.onDisplayName, R.string.display_name, KeyboardType.Text,
                    error = FieldError.DISPLAY_NAME in state.fieldErrors, errorRes = R.string.field_error_display_name,
                    capitalization = KeyboardCapitalization.Words)
            }
            Field(
                state.username, callbacks.onUsername, R.string.username, KeyboardType.Ascii,
                error = FieldError.USERNAME in state.fieldErrors, errorRes = R.string.field_error_username,
                supporting = if (state.mode == AuthMode.REDEEM) R.string.username_hint else null, capitalization = KeyboardCapitalization.None,
            )
            PasswordField(
                state.password, callbacks.onPassword, R.string.password, state.passwordVisible, callbacks.onTogglePassword,
                error = FieldError.PASSWORD_EMPTY in state.fieldErrors || FieldError.PASSWORD_SHORT in state.fieldErrors,
                errorRes = if (state.mode == AuthMode.REDEEM) R.string.field_error_password_short else R.string.field_error_required,
                supporting = if (state.mode == AuthMode.REDEEM) R.string.password_hint else null,
                imeAction = if (state.mode == AuthMode.REDEEM) ImeAction.Next else ImeAction.Next,
            )
            if (state.mode == AuthMode.REDEEM) {
                PasswordField(
                    state.confirmPassword, callbacks.onConfirm, R.string.confirm_password, state.passwordVisible, callbacks.onTogglePassword,
                    error = FieldError.PASSWORD_MISMATCH in state.fieldErrors, errorRes = R.string.field_error_password_mismatch,
                )
            }
            Field(
                state.deviceName, callbacks.onDeviceName, R.string.device_name, KeyboardType.Text,
                error = FieldError.DEVICE_NAME in state.fieldErrors, errorRes = R.string.field_error_device_name,
                supporting = R.string.device_name_hint, capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done,
                onDone = callbacks.onSubmit,
            )

            state.error?.let {
                Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (state.mode == AuthMode.LOGIN) {
                TextButton(onClick = callbacks.onForgotPassword, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.forgot_password))
                }
            }

            Button(
                onClick = callbacks.onSubmit, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(if (state.mode == AuthMode.LOGIN) R.string.action_login else R.string.action_create_account))
                }
            }
        }
    }
    if (scanning) {
        QrScannerDialog(
            title = stringResource(R.string.scan_invitation),
            hint = stringResource(R.string.scan_invitation_hint),
            onResult = { text -> scanning = false; callbacks.onInviteScanned(text) },
            onDismiss = { scanning = false },
        )
    }
}

/** The server a scanned invitation points to, large: whoever made the code decides where the account is created. */
@Composable
private fun InviteHostCard(invite: InviteLink, server: String) {
    val host = InviteQr.hostOf(server) ?: server
    val warn = invite.suspiciousHost || InviteQr.isSuspiciousServer(server)
    Card(
        colors = CardDefaults.cardColors(containerColor = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.invite_scanned_from), style = MaterialTheme.typography.labelLarge)
            Text(host, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.invite_scanned_check), style = MaterialTheme.typography.bodySmall)
            if (warn) {
                Text(
                    stringResource(R.string.invite_scanned_suspicious), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

internal fun inviteRejectionText(reason: InviteRejection): Int = when (reason) {
    InviteRejection.NOT_AN_INVITE -> R.string.invite_error_not_invite
    InviteRejection.UNSUPPORTED_VERSION -> R.string.invite_error_version
    InviteRejection.TOO_LONG -> R.string.invite_error_too_long
    InviteRejection.MALFORMED -> R.string.invite_error_malformed
    InviteRejection.INVALID_SERVER -> R.string.invite_error_server
    InviteRejection.INSECURE_SERVER -> R.string.invite_error_insecure
    InviteRejection.INVALID_CODE -> R.string.invite_error_code
}

@Composable
internal fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    errorRes: Int = R.string.field_error_required,
    supporting: Int? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(stringResource(label)) },
        isError = error,
        supportingText = {
            when {
                error -> Text(stringResource(errorRes))
                supporting != null -> Text(stringResource(supporting))
            }
        },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

@Composable
internal fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    visible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    errorRes: Int = R.string.field_error_required,
    supporting: Int? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(stringResource(label)) },
        isError = error,
        supportingText = {
            when {
                error -> Text(stringResource(errorRes))
                supporting != null -> Text(stringResource(supporting))
            }
        },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (visible) R.string.hide_password else R.string.show_password),
                )
            }
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}
