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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.filled.Call
import se.swahnchat.app.call.canShowFullScreenCalls
import se.swahnchat.app.call.openFullScreenIntentSettings
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wallpaper
import se.swahnchat.app.AppLocale
import se.swahnchat.app.data.Accent
import se.swahnchat.app.data.AppLanguage
import se.swahnchat.app.data.ChatBackground
import se.swahnchat.app.data.QuietHours
import se.swahnchat.app.data.QuietHoursSettings
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
    val onSelectDistributor: (String) -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onNotificationPrivacy: (NotificationPrivacy) -> Unit = {},
    val onBlockScreenshots: (Boolean) -> Unit = {},
    val onCallsRelayOnly: (Boolean) -> Unit = {},
    /** Whether incoming calls may take over the screen (Android 14+ lets the user switch that off); false shows a row that opens the setting. */
    val fullScreenIntentAllowed: Boolean = true,
    val onOpenFullScreenIntentSettings: () -> Unit = {},
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
    val onQuietHours: (QuietHoursSettings) -> Unit = {},
    val onSendReadReceipts: (Boolean) -> Unit = {},
    val onShowTyping: (Boolean) -> Unit = {},
    val onSharePresence: (Boolean) -> Unit = {},
    val onHighContrast: (Boolean) -> Unit = {},
    val onAccent: (Accent) -> Unit = {},
    val onChatBackground: (ChatBackground) -> Unit = {},
    val onLanguage: (AppLanguage) -> Unit = {},
    /** Opens the safety screen of the own account (verify own devices). */
    val onOpenOwnSafety: () -> Unit = {},
    val onOpenStorage: () -> Unit = {},
    val onOpenAutoDownload: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onOpenDevices: () -> Unit = {},
    val onOpenAdmin: () -> Unit = {},
    val onOpenDeleteAccount: () -> Unit = {},
    val onOpenOnboarding: () -> Unit = {},
    val onOpenDiagnostics: () -> Unit = {},
    val onOpenOutbox: () -> Unit = {},
    /** Opens the system's battery-optimisation dialog for the app. */
    val onBattery: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSafety: (userId: String) -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenAutoDownload: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenDevices: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
    onOpenDeleteAccount: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenOutbox: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::setAvatar) }
    // checked again when the person comes back from the system page that switches it
    var fullScreenAllowed by remember { mutableStateOf(canShowFullScreenCalls(context)) }
    LifecycleResumeEffect(vm) { vm.onShown(); fullScreenAllowed = canShowFullScreenCalls(context); onPauseOrDispose {} }
    val activity = LocalActivity.current as? FragmentActivity
    val promptTitle = stringResource(R.string.lock_prompt_title)
    // the choice lives in the system (Android 13+) or in the preferences; read it here so the row shows the real one
    var chosenLanguage by remember { mutableStateOf(AppLocale.current(context)) }
    SettingsContent(
        state.copy(language = chosenLanguage),
        SettingsCallbacks(
            onBack = onBack, onSetName = vm::setDisplayName,
            onPickAvatar = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onClearAvatar = vm::clearAvatar, onChangePassword = vm::changePassword,
            onSelectDistributor = vm::selectDistributor, onTheme = vm::setTheme,
            onNotificationPrivacy = vm::setNotificationPrivacy, onBlockScreenshots = vm::setBlockScreenshots, onCallsRelayOnly = vm::setCallsRelayOnly,
            fullScreenIntentAllowed = fullScreenAllowed,
            onOpenFullScreenIntentSettings = { openFullScreenIntentSettings(context) },
            onLockTimeout = vm::setLockTimeout, onAppLock = vm::setAppLock, lockAvailable = Authenticator.isAvailable(context),
            onAuthenticate = { done -> if (activity != null) Authenticator.prompt(activity, promptTitle,
                null) { ok -> if (ok) done() } }, onCreateInvitation = vm::createInvitation,
            onDismissInvitation = vm::dismissInvitation, onDismissPasswordChanged = vm::dismissPasswordChanged, onLogout = vm::logout,
            onOpenSystemNotificationSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
            onQuietHours = vm::setQuietHours, onSendReadReceipts = vm::setSendReadReceipts, onShowTyping = vm::setShowTyping,
            onSharePresence = vm::setSharePresence, onHighContrast = vm::setHighContrast, onAccent = vm::setAccent,
            onChatBackground = vm::setChatBackground,
            onLanguage = { language ->
                (activity)?.let { vm.applyLanguage(it, language) }
                chosenLanguage = language
            },
            onOpenOwnSafety = { onOpenSafety(state.userId) },
            onOpenStorage = onOpenStorage, onOpenAutoDownload = onOpenAutoDownload, onOpenHistory = onOpenHistory,
            onOpenDevices = onOpenDevices, onOpenAdmin = onOpenAdmin, onOpenDeleteAccount = onOpenDeleteAccount,
            onOpenOnboarding = onOpenOnboarding, onOpenDiagnostics = onOpenDiagnostics, onOpenOutbox = onOpenOutbox,
            onBattery = { se.swahnchat.app.diagnostics.BatteryOptimization.open(context) },
            onCopy = { copyInvitation(context, it) },
            onShare = { code ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, resources.getString(R.string.invitation_share_text, code))
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
    var quietDialog by remember { mutableStateOf(false) }
    var pickAccent by remember { mutableStateOf(false) }
    var pickBackground by remember { mutableStateOf(false) }
    var pickLanguage by remember { mutableStateOf(false) }
    var lockUnavailable by remember { mutableStateOf(false) }
    var createInvite by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
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
            if (state.isAdmin) SettingRow(Icons.Filled.PersonAdd, stringResource(R.string.create_invitation),
                stringResource(R.string.create_invitation_hint), onClick = { createInvite = true })
            if (state.isAdmin) SettingRow(Icons.Filled.AdminPanelSettings, stringResource(R.string.admin_title), stringResource(R.string.admin_row_hint),
                onClick = cb.onOpenAdmin)
            SettingRow(Icons.Filled.DeleteForever, stringResource(R.string.delete_account_title), stringResource(R.string.delete_account_row_hint),
                danger = true, onClick = cb.onOpenDeleteAccount)

            // --- devices ---
            Section(R.string.settings_devices)
            SettingRow(
                Icons.Filled.Smartphone, stringResource(R.string.devices_title),
                (state.devices as? DevicesUi.Loaded)?.devices?.size?.let { pluralStringResource(R.plurals.devices_count, it,
                    it) } ?: stringResource(R.string.devices_row_hint),
                onClick = cb.onOpenDevices,
            )
            SettingRow(Icons.Filled.Security, stringResource(R.string.my_devices_safety), stringResource(R.string.my_devices_safety_hint),
                onClick = cb.onOpenOwnSafety)

            // --- notifications and push ---
            Section(R.string.settings_notifications)
            SettingRow(
                Icons.Filled.Notifications, stringResource(R.string.notifications),
                stringResource(if (notificationsOn) R.string.notifications_on else R.string.notifications_off), onClick = cb.onOpenSystemNotificationSettings,
            )
            SettingRow(
                Icons.Filled.Bedtime, stringResource(R.string.quiet_hours),
                if (state.quietHours.enabled) {
                    stringResource(R.string.quiet_hours_summary, QuietHours.clock(state.quietHours.startMinutes), QuietHours.clock(state.quietHours.endMinutes))
                } else {
                    stringResource(R.string.quiet_hours_off)
                },
                onClick = { quietDialog = true },
            )
            SettingRow(Icons.Filled.Apps, stringResource(R.string.push_title), pushSummary(state), onClick = { pickDistributor = true })
            SettingRow(
                Icons.Filled.BatteryChargingFull, stringResource(R.string.battery_title),
                stringResource(if (state.batteryUnrestricted) R.string.battery_status_ok else R.string.battery_status_restricted_short),
                onClick = cb.onBattery,
            )
            if (!cb.fullScreenIntentAllowed) {
                SettingRow(
                    Icons.Filled.Call, stringResource(R.string.calls_full_screen), stringResource(R.string.calls_full_screen_hint),
                    onClick = cb.onOpenFullScreenIntentSettings,
                )
            }

            // --- privacy and security ---
            Section(R.string.settings_privacy)
            SettingRow(
                Icons.Filled.VisibilityOff, stringResource(R.string.notification_privacy), stringResource(privacyLabel(state.notificationPrivacy)),
                onClick = { pickPrivacy = true },
            )
            SwitchRow(
                Icons.Filled.DoneAll, stringResource(R.string.read_receipts), stringResource(R.string.read_receipts_hint),
                state.sendReadReceipts, cb.onSendReadReceipts,
            )
            SwitchRow(
                Icons.Filled.Keyboard, stringResource(R.string.show_typing), stringResource(R.string.show_typing_hint),
                state.showTyping, cb.onShowTyping,
            )
            SwitchRow(
                Icons.Filled.Visibility, stringResource(R.string.share_presence), stringResource(R.string.share_presence_hint),
                state.sharePresence == true, cb.onSharePresence, enabled = state.sharePresence != null,
            )
            SwitchRow(
                Icons.Filled.PhoneAndroid, stringResource(R.string.block_screenshots), stringResource(R.string.block_screenshots_hint),
                state.blockScreenshots, cb.onBlockScreenshots,
            )
            SwitchRow(
                Icons.Filled.Call, stringResource(R.string.calls_relay_only), stringResource(R.string.calls_relay_only_hint),
                state.callsRelayOnly, cb.onCallsRelayOnly,
            )
            SwitchRow(
                Icons.Filled.Lock, stringResource(R.string.app_lock), stringResource(R.string.app_lock_hint), state.appLockEnabled,
                onChange = { on -> if (on && !cb.lockAvailable) lockUnavailable = true else cb.onAuthenticate { cb.onAppLock(on) } },
            )
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
            SettingRow(
                Icons.Filled.Outbox, stringResource(R.string.outbox_title),
                if (state.failedCount > 0) pluralStringResource(R.plurals.outbox_failed, state.failedCount,
                    state.failedCount) else stringResource(R.string.outbox_row_hint),
                danger = state.failedCount > 0, badge = state.failedCount, onClick = cb.onOpenOutbox,
            )

            // --- appearance ---
            Section(R.string.settings_appearance)
            SettingRow(Icons.Filled.Palette, stringResource(R.string.theme), stringResource(themeLabel(state.theme)), onClick = { pickTheme = true })
            SwitchRow(
                Icons.Filled.Contrast, stringResource(R.string.high_contrast), stringResource(R.string.high_contrast_hint),
                state.highContrast, cb.onHighContrast,
            )
            SettingRow(
                Icons.Filled.ColorLens, stringResource(R.string.accent_color),
                stringResource(accentLabel(if (state.accent == Accent.SYSTEM && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) Accent.TEAL else state.accent)),
                onClick = { pickAccent = true },
            )
            SettingRow(
                Icons.Filled.Wallpaper, stringResource(R.string.chat_background), stringResource(backgroundLabel(state.chatBackground)),
                onClick = { pickBackground = true },
            )
            SettingRow(
                Icons.Filled.Language, stringResource(R.string.language), stringResource(languageLabel(state.language)),
                onClick = { pickLanguage = true },
            )

            // --- about ---
            Section(R.string.settings_about)
            SettingRow(Icons.Filled.Wifi, stringResource(R.string.server), state.serverUrl, onClick = null)
            SettingRow(Icons.Filled.Smartphone, stringResource(R.string.this_device), state.deviceName, onClick = null)
            SettingRow(Icons.Filled.RocketLaunch, stringResource(R.string.onboarding_title), stringResource(R.string.onboarding_row_hint),
                onClick = cb.onOpenOnboarding)
            SettingRow(Icons.Filled.BugReport, stringResource(R.string.diagnostics_title), stringResource(R.string.diagnostics_row_hint),
                onClick = cb.onOpenDiagnostics)
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
            confirmButton = { TextButton(enabled = text.isNotBlank(),
                onClick = { cb.onSetName(text); editName = false }) { Text(stringResource(R.string.action_save)) } },
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
    if (pickDistributor) {
        DistributorDialog(state.distributors, state.selectedDistributor, onDismiss = { pickDistributor = false },
            onSelect = { cb.onSelectDistributor(it); pickDistributor = false })
    }
    if (pickTheme) {
        AlertDialog(
            onDismissRequest = { pickTheme = false },
            title = { Text(stringResource(R.string.theme)) },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = state.theme == mode,
                                role = Role.RadioButton) { cb.onTheme(mode); pickTheme = false },
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
    if (quietDialog) QuietHoursDialog(state.quietHours, cb.onQuietHours, onDismiss = { quietDialog = false })
    if (pickAccent) {
        AccentDialog(
            state.accent, systemAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            onPick = { cb.onAccent(it); pickAccent = false }, onDismiss = { pickAccent = false },
        )
    }
    if (pickBackground) ChatBackgroundDialog(state.chatBackground, { cb.onChatBackground(it); pickBackground = false }, { pickBackground = false })
    if (pickLanguage) {
        ChoiceDialog(
            R.string.language, AppLanguage.entries, state.language, ::languageLabel, explanation = R.string.language_hint,
            onPick = { cb.onLanguage(it); pickLanguage = false }, onDismiss = { pickLanguage = false },
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
            properties = SecureDialogProperties,
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
                if (explanation != null) Text(stringResource(explanation), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp))
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
internal fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean,
    onChange: (Boolean) -> Unit, enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
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

internal fun languageLabel(language: AppLanguage): Int = when (language) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.SWEDISH -> R.string.language_swedish
    AppLanguage.ENGLISH -> R.string.language_english
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
internal fun Section(title: Int) {
    Text(
        stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
internal fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, danger: Boolean = false, onClick: (() -> Unit)? = null,
    /** A number on the icon (failed messages); 0: none. */
    badge: Int = 0,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgedBox(badge = { if (badge > 0) Badge { Text(badge.toString()) } }) {
            Icon(icon, contentDescription = null, tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next))
                OutlinedTextField(new, { new = it }, label = { Text(stringResource(R.string.new_password)) }, singleLine = true,
                    isError = new.isNotEmpty() && new.length < 10, supportingText = { Text(stringResource(R.string.password_hint)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next))
                OutlinedTextField(confirm, { confirm = it }, label = { Text(stringResource(R.string.confirm_password)) }, singleLine = true,
                    isError = confirm.isNotEmpty() && confirm != new,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done))
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(current, new) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun DistributorDialog(
    distributors: List<se.swahnchat.app.platform.push.Distributor>,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.push_title)) },
        text = {
            Column {
                Text(stringResource(R.string.push_explainer), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
                if (distributors.isEmpty()) {
                    Text(stringResource(R.string.push_install_hint), style = MaterialTheme.typography.bodyMedium)
                }
                distributors.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = d.packageName == selected,
                            role = Role.RadioButton) { onSelect(d.packageName) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = d.packageName == selected, onClick = null)
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
internal fun InvitationDialog(onDismiss: () -> Unit, onCreate: (String, Int, Boolean) -> Unit) {
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
