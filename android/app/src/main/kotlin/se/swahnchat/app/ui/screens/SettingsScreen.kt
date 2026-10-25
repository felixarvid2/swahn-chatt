package se.swahnchat.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.swahnchat.app.R
import se.swahnchat.app.data.LockTimeout
import se.swahnchat.app.data.NotificationPrivacy
import se.swahnchat.app.data.ThemeMode
import se.swahnchat.app.platform.security.Authenticator
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.PhoneAndroid
import se.swahnchat.app.platform.push.PushStatus
import se.swahnchat.app.ui.components.Avatar
import se.swahnchat.app.ui.components.QrImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class SettingsCallbacks(
    val onBack: () -> Unit = {},
    val onSetName: (String) -> Unit = {},
    val onPickAvatar: () -> Unit = {},
    val onClearAvatar: () -> Unit = {},
    val onChangePassword: (String, String) -> Unit = { _, _ -> },
    val onRevokeDevice: (String) -> Unit = {},
    val onSelectDistributor: (String) -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onNotificationPrivacy: (NotificationPrivacy) -> Unit = {},
    val onBlockScreenshots: (Boolean) -> Unit = {},
    val onLockTimeout: (LockTimeout) -> Unit = {},
    /** Turn the app lock on or off; the screen has already checked that the user can authenticate. */
    val onAppLock: (Boolean) -> Unit = {},
    /** Asks the user to authenticate, then calls back (the real screen shows the system prompt). */
    val onAuthenticate: (onSuccess: () -> Unit) -> Unit = { it() },
    val lockAvailable: Boolean = true,
    val onCreateInvitation: (note: String, hours: Int, admin: Boolean) -> Unit = { _, _, _ -> },
    val onDismissInvitation: () -> Unit = {},
    val onDismissPasswordChanged: () -> Unit = {},
    val onLogout: () -> Unit = {},
    val onOpenSystemNotificationSettings: () -> Unit = {},
    val onOpenLanguageSettings: (() -> Unit)? = null,
    /** Opens the safety screen of the own account (verify own devices). */
    val onOpenOwnSafety: () -> Unit = {},
    val onOpenStorage: () -> Unit = {},
    val onOpenAutoDownload: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onShare: (String) -> Unit = {},
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSafety: (userId: String) -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenAutoDownload: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::setAvatar) }
    LifecycleResumeEffect(vm) { vm.onShown(); onPauseOrDispose {} }
    val activity = LocalActivity.current as? FragmentActivity
    val promptTitle = stringResource(R.string.lock_prompt_title)
    SettingsContent(
        state,
        SettingsCallbacks(
            onBack = onBack, onSetName = vm::setDisplayName,
            onPickAvatar = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onClearAvatar = vm::clearAvatar, onChangePassword = vm::changePassword, onRevokeDevice = vm::revokeDevice,
            onSelectDistributor = vm::selectDistributor, onTheme = vm::setTheme,
            onNotificationPrivacy = vm::setNotificationPrivacy, onBlockScreenshots = vm::setBlockScreenshots,
            onLockTimeout = vm::setLockTimeout, onAppLock = vm::setAppLock, lockAvailable = Authenticator.isAvailable(context),
            onAuthenticate = { done -> if (activity != null) Authenticator.prompt(activity, promptTitle, null) { ok -> if (ok) done() } }, onCreateInvitation = vm::createInvitation,
            onDismissInvitation = vm::dismissInvitation, onDismissPasswordChanged = vm::dismissPasswordChanged, onLogout = vm::logout,
            onOpenSystemNotificationSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
            onOpenLanguageSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                { context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
            } else null,
            onOpenOwnSafety = { onOpenSafety(state.userId) },
            onOpenStorage = onOpenStorage, onOpenAutoDownload = onOpenAutoDownload, onOpenHistory = onOpenHistory,
            onCopy = { copyInvitation(context, it) },
            onShare = { code ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, context.getString(R.string.invitation_share_text, code))
                context.startActivity(Intent.createChooser(send, null))
            },
        ),
    )
}

private fun copyInvitation(context: Context, code: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText(null, code).apply {
        // keep the code out of keyboard suggestions and clipboard previews where the system supports it
        description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(state: SettingsUiState, cb: SettingsCallbacks, modifier: Modifier = Modifier) {
    var editName by remember { mutableStateOf(false) }
    var changePassword by remember { mutableStateOf(false) }
    var pickDistributor by remember { mutableStateOf(false) }
    var pickTheme by remember { mutableStateOf(false) }
    var pickPrivacy by remember { mutableStateOf(false) }
    var pickTimeout by remember { mutableStateOf(false) }
    var lockUnavailable by remember { mutableStateOf(false) }
    var createInvite by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<DeviceUi?>(null) }
    val errorText = errorTexts()
    val context = LocalContext.current
    val notificationsOn = remember(state) { NotificationManagerCompat.from(context).areNotificationsEnabled() }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { BackButton(cb.onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            // --- profile ---
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(state.avatar, size = 72.dp)
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(state.displayName, style = MaterialTheme.typography.titleLarge)
                    Text("@${state.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { editName = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit_name))
                }
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = cb.onPickAvatar, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_change_picture)) }
                if (state.avatar.attachmentId != null) {
                    TextButton(onClick = cb.onClearAvatar, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_remove_picture)) }
                }
                if (state.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            }
            state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // --- account ---
            Section(R.string.settings_account)
            SettingRow(Icons.Filled.Key, stringResource(R.string.change_password), null, onClick = { changePassword = true })
            if (state.isAdmin) SettingRow(Icons.Filled.PersonAdd, stringResource(R.string.create_invitation), stringResource(R.string.create_invitation_hint), onClick = { createInvite = true })

            // --- devices ---
            Section(R.string.settings_devices)
            when (val d = state.devices) {
                DevicesUi.Loading -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), strokeWidth = 2.dp)
                DevicesUi.Failed -> Text(stringResource(R.string.devices_load_failed), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                is DevicesUi.Loaded -> d.devices.forEach { device -> DeviceRow(device, onRevoke = { revoke = device }) }
            }
            SettingRow(Icons.Filled.Security, stringResource(R.string.my_devices_safety), stringResource(R.string.my_devices_safety_hint), onClick = cb.onOpenOwnSafety)

            // --- notifications and push ---
            Section(R.string.settings_notifications)
            SettingRow(
                Icons.Filled.Notifications, stringResource(R.string.notifications),
                stringResource(if (notificationsOn) R.string.notifications_on else R.string.notifications_off), onClick = cb.onOpenSystemNotificationSettings,
            )
            SettingRow(Icons.Filled.Apps, stringResource(R.string.push_title), pushSummary(state), onClick = { pickDistributor = true })

            // --- privacy and security ---
            Section(R.string.settings_privacy)
            SettingRow(
                Icons.Filled.VisibilityOff, stringResource(R.string.notification_privacy), stringResource(privacyLabel(state.notificationPrivacy)),
                onClick = { pickPrivacy = true },
            )
            SwitchRow(
                Icons.Filled.PhoneAndroid, stringResource(R.string.block_screenshots), stringResource(R.string.block_screenshots_hint),
                state.blockScreenshots, cb.onBlockScreenshots,
            )
            SwitchRow(
                Icons.Filled.Lock, stringResource(R.string.app_lock), stringResource(R.string.app_lock_hint), state.appLockEnabled,
            ) { on ->
                if (on && !cb.lockAvailable) lockUnavailable = true else cb.onAuthenticate { cb.onAppLock(on) }
            }
            if (state.appLockEnabled) {
                SettingRow(
                    Icons.Filled.Timer, stringResource(R.string.lock_after), stringResource(timeoutLabel(state.lockTimeout)),
                    onClick = { pickTimeout = true },
                )
            }

            // --- data and storage ---
            Section(R.string.settings_data)
            SettingRow(
                Icons.Filled.CloudDownload, stringResource(R.string.auto_download), stringResource(R.string.auto_download_hint),
                onClick = cb.onOpenAutoDownload,
            )
            SettingRow(Icons.Filled.Storage, stringResource(R.string.storage), stringResource(R.string.storage_hint), onClick = cb.onOpenStorage)
            SettingRow(Icons.Filled.Backup, stringResource(R.string.history_title), stringResource(R.string.history_hint), onClick = cb.onOpenHistory)

            // --- appearance ---
            Section(R.string.settings_appearance)
            SettingRow(Icons.Filled.Palette, stringResource(R.string.theme), stringResource(themeLabel(state.theme)), onClick = { pickTheme = true })
            cb.onOpenLanguageSettings?.let { open -> SettingRow(Icons.Filled.Language, stringResource(R.string.language), stringResource(R.string.language_hint), onClick = open) }

            // --- about ---
            Section(R.string.settings_about)
            SettingRow(Icons.Filled.Wifi, stringResource(R.string.server), state.serverUrl, onClick = null)
            SettingRow(Icons.Filled.Smartphone, stringResource(R.string.this_device), state.deviceName, onClick = null)
            SettingRow(Icons.Filled.Info, stringResource(R.string.app_name), stringResource(R.string.version_format, state.versionName), onClick = null)
            Text(
                stringResource(R.string.security_info), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingRow(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.action_logout), null, danger = true, onClick = { confirmLogout = true })
        }
    }

    if (editName) {
        var text by remember { mutableStateOf(state.displayName) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text(stringResource(R.string.display_name)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(64) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { cb.onSetName(text); editName = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { editName = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (changePassword) PasswordDialog(onDismiss = { changePassword = false }, onConfirm = { c, n -> cb.onChangePassword(c, n); changePassword = false })
    if (state.passwordChanged) {
        AlertDialog(
            onDismissRequest = cb.onDismissPasswordChanged,
            title = { Text(stringResource(R.string.change_password)) },
            text = { Text(stringResource(R.string.password_changed_text)) },
            confirmButton = { TextButton(onClick = cb.onDismissPasswordChanged) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    if (pickDistributor) DistributorDialog(state, onDismiss = { pickDistributor = false }, onSelect = { cb.onSelectDistributor(it); pickDistributor = false })
    if (pickTheme) {
        AlertDialog(
            onDismissRequest = { pickTheme = false },
            title = { Text(stringResource(R.string.theme)) },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = state.theme == mode, role = Role.RadioButton) { cb.onTheme(mode); pickTheme = false },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.theme == mode, onClick = null)
                            Text(stringResource(themeLabel(mode)), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickTheme = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    if (pickPrivacy) {
        ChoiceDialog(
            R.string.notification_privacy, NotificationPrivacy.entries, state.notificationPrivacy, ::privacyLabel,
            explanation = R.string.notification_privacy_hint,
            onPick = { cb.onNotificationPrivacy(it); pickPrivacy = false }, onDismiss = { pickPrivacy = false },
        )
    }
    if (pickTimeout) {
        ChoiceDialog(
            R.string.lock_after, LockTimeout.entries, state.lockTimeout, ::timeoutLabel,
            onPick = { cb.onLockTimeout(it); pickTimeout = false }, onDismiss = { pickTimeout = false },
        )
    }
    if (lockUnavailable) {
        AlertDialog(
            onDismissRequest = { lockUnavailable = false },
            title = { Text(stringResource(R.string.app_lock)) },
            text = { Text(stringResource(R.string.app_lock_unavailable)) },
            confirmButton = { TextButton(onClick = { lockUnavailable = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    if (createInvite) {
        InvitationDialog(onDismiss = { createInvite = false }, onCreate = { n, h, a -> cb.onCreateInvitation(n, h, a); createInvite = false })
    }
    state.invitation?.let { inv ->
        AlertDialog(
            onDismissRequest = cb.onDismissInvitation,
            title = { Text(stringResource(R.string.invitation_created)) },
            text = { InvitationCreated(inv.code, state.invitationQr, cb.onCopy, cb.onShare) },
            confirmButton = { TextButton(onClick = cb.onDismissInvitation) { Text(stringResource(R.string.action_close)) } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.action_logout)) },
            text = { Text(stringResource(R.string.logout_text)) },
            confirmButton = { TextButton(onClick = { confirmLogout = false; cb.onLogout() }) { Text(stringResource(R.string.action_logout)) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    revoke?.let { device ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text(stringResource(R.string.revoke_device_title, device.name)) },
            text = { Text(stringResource(R.string.revoke_device_text)) },
            confirmButton = { TextButton(onClick = { cb.onRevokeDevice(device.id); revoke = null }) { Text(stringResource(R.string.action_revoke)) } },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The created invitation: the code (shown once), the same invitation as a QR code to scan, and copy / share. */
@Composable
internal fun InvitationCreated(code: String, qr: String?, onCopy: (String) -> Unit, onShare: (String) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.invitation_created_text), style = MaterialTheme.typography.bodyMedium)
        Text(
            code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        qr?.let {
            QrImage(it, stringResource(R.string.invitation_qr_description), Modifier.size(208.dp))
            Text(
                stringResource(R.string.invitation_qr_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row {
            TextButton(onClick = { onCopy(code) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.action_copy), modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = { onShare(code) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.action_share), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** The created-invitation dialog as a plain card (the screenshot test cannot capture a dialog window). */
@Composable
internal fun InvitationCreatedPreview(code: String, qr: String?) {
    androidx.compose.material3.Surface(Modifier.padding(24.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
        Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.invitation_created), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
            InvitationCreated(code, qr, {}, {})
        }
    }
}

internal fun privacyLabel(value: NotificationPrivacy): Int = when (value) {
    NotificationPrivacy.FULL -> R.string.privacy_full
    NotificationPrivacy.NAME_ONLY -> R.string.privacy_name_only
    NotificationPrivacy.HIDDEN -> R.string.privacy_hidden
}

internal fun timeoutLabel(value: LockTimeout): Int = when (value) {
    LockTimeout.IMMEDIATELY -> R.string.lock_immediately
    LockTimeout.MINUTE_1 -> R.string.lock_1_minute
    LockTimeout.MINUTES_5 -> R.string.lock_5_minutes
    LockTimeout.MINUTES_30 -> R.string.lock_30_minutes
}

@Composable
private fun <T> ChoiceDialog(
    title: Int, options: List<T>, selected: T, label: (T) -> Int, onPick: (T) -> Unit, onDismiss: () -> Unit, explanation: Int? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                if (explanation != null) Text(stringResource(explanation), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected == option, role = Role.RadioButton) { onPick(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == option, onClick = null)
                        Text(stringResource(label(option)), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@Composable
private fun pushSummary(state: SettingsUiState): String = when (state.pushStatus) {
    PushStatus.NO_DISTRIBUTOR -> stringResource(R.string.push_no_distributor)
    PushStatus.NOT_CHOSEN -> stringResource(R.string.push_not_chosen)
    PushStatus.REGISTERING -> stringResource(R.string.push_registering, distributorLabel(state))
    PushStatus.ACTIVE -> stringResource(R.string.push_active, distributorLabel(state))
    PushStatus.FAILED -> stringResource(R.string.push_failed)
}

private fun distributorLabel(state: SettingsUiState): String =
    state.distributors.firstOrNull { it.packageName == state.selectedDistributor }?.label ?: state.selectedDistributor.orEmpty()

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, danger: Boolean = false, onClick: (() -> Unit)? = null) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceUi, onRevoke: () -> Unit) {
    val date = remember(device.lastSeen) {
        if (device.lastSeen <= 0) null else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(device.lastSeen).atZone(ZoneId.systemDefault()))
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (device.isCurrent) stringResource(R.string.this_device)
                else if (date != null) stringResource(R.string.last_seen, date) else stringResource(R.string.never_seen),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!device.isCurrent) {
            IconButton(onClick = onRevoke, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.revoke_device_description, device.name), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = current.isNotEmpty() && new.length in 10..256 && new == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.change_password_text), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(current, { current = it }, label = { Text(stringResource(R.string.current_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
                OutlinedTextField(new, { new = it }, label = { Text(stringResource(R.string.new_password)) }, singleLine = true,
                    isError = new.isNotEmpty() && new.length < 10, supportingText = { Text(stringResource(R.string.password_hint)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
                OutlinedTextField(confirm, { confirm = it }, label = { Text(stringResource(R.string.confirm_password)) }, singleLine = true,
                    isError = confirm.isNotEmpty() && confirm != new,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(current, new) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DistributorDialog(state: SettingsUiState, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.push_title)) },
        text = {
            Column {
                Text(stringResource(R.string.push_explainer), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
                if (state.distributors.isEmpty()) {
                    Text(stringResource(R.string.push_install_hint), style = MaterialTheme.typography.bodyMedium)
                }
                state.distributors.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = d.packageName == state.selectedDistributor, role = Role.RadioButton) { onSelect(d.packageName) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = d.packageName == state.selectedDistributor, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(d.label, style = MaterialTheme.typography.bodyLarge)
                            Text(d.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun InvitationDialog(onDismiss: () -> Unit, onCreate: (String, Int, Boolean) -> Unit) {
    var note by remember { mutableStateOf("") }
    var hours by remember { mutableIntStateOf(72) }
    var admin by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_invitation)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(note, { note = it.take(200) }, label = { Text(stringResource(R.string.invitation_note)) }, singleLine = true)
                Text(stringResource(R.string.invitation_valid_for), style = MaterialTheme.typography.labelLarge)
                listOf(24 to R.string.hours_24, 72 to R.string.hours_72, 168 to R.string.days_7).forEach { (h, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = hours == h, role = Role.RadioButton) { hours = h },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = hours == h, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.invitation_admin), modifier = Modifier.weight(1f))
                    Switch(checked = admin, onCheckedChange = { admin = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(note, hours, admin) }) { Text(stringResource(R.string.action_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
