package se.swahnchat.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.swahnchat.app.R
import se.swahnchat.app.ui.components.formatSize
import se.swahnchat.app.ui.components.rememberScheduleFormatter
import se.swahnchat.core.model.AdminDevice
import se.swahnchat.core.model.AdminUser
import se.swahnchat.core.model.AuditEvent
import se.swahnchat.core.model.Invitation

/**
 * A dialog that shows a secret (reset code, invitation code or QR): FLAG_SECURE on the dialog's own window, whatever the
 * "block screenshots" setting says. A dialog is a window of its own, so the activity's flag (see MainActivity) does not
 * cover it, and the setting is only added to, never replaced.
 */
internal val SecureDialogProperties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)

/** What the administrator can do with one account, in the order the menu shows it. */
enum class AdminAction { SUSPEND, UNSUSPEND, MAKE_ADMIN, REMOVE_ADMIN, RESET_CODE, REMOVE }

/** The actions that make sense for [user]: none for yourself or for a removed account (the server refuses them as well). */
fun adminActions(user: AdminUser, me: String): List<AdminAction> = when {
    user.disabledAt != null || user.id == me -> emptyList()
    else -> listOf(
        if (user.suspendedAt != null) AdminAction.UNSUSPEND else AdminAction.SUSPEND,
        if (user.isAdmin) AdminAction.REMOVE_ADMIN else AdminAction.MAKE_ADMIN,
        AdminAction.RESET_CODE,
        AdminAction.REMOVE,
    )
}

/** One of the confirmations: every destructive action asks first. */
private sealed interface Pending {
    class OnUser(val action: AdminAction, val user: AdminUser) : Pending
    class OnDevice(val user: AdminUser, val device: AdminDevice) : Pending
    class OnInvitation(val invitation: Invitation) : Pending
}

class AdminCallbacks(
    val onBack: () -> Unit = {},
    val onTab: (AdminTab) -> Unit = {},
    val onReload: () -> Unit = {},
    val onSuspend: (String) -> Unit = {},
    val onUnsuspend: (String) -> Unit = {},
    val onSetAdmin: (userId: String, admin: Boolean) -> Unit = { _, _ -> },
    /** The second argument is the calling administrator's own password; the server asks for it. */
    val onResetCode: (AdminUser, String) -> Unit = { _, _ -> },
    val onRevokeDevice: (userId: String, deviceId: String) -> Unit = { _, _ -> },
    val onRemoveUser: (userId: String, adminPassword: String) -> Unit = { _, _ -> },
    val onCreateInvitation: (note: String, hours: Int, admin: Boolean) -> Unit = { _, _, _ -> },
    val onRevokeInvitation: (String) -> Unit = {},
    val onDismissInvitation: () -> Unit = {},
    val onDismissResetCode: () -> Unit = {},
    val onLoadMoreAudit: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

@Composable
fun AdminScreen(onBack: () -> Unit, vm: AdminViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AdminContent(
        state,
        AdminCallbacks(
            onBack = onBack, onTab = vm::selectTab,
            onReload = {
                when (state.tab) {
                    AdminTab.USERS -> vm.loadUsers()
                    AdminTab.INVITATIONS -> vm.loadInvitations()
                    AdminTab.STATUS -> vm.loadStats()
                    AdminTab.LOG -> vm.loadAudit()
                }
            },
            onSuspend = vm::suspendUser, onUnsuspend = vm::unsuspendUser, onSetAdmin = vm::setAdmin, onResetCode = vm::createResetCode,
            onRevokeDevice = vm::revokeDevice, onRemoveUser = vm::removeUser, onCreateInvitation = vm::createInvitation,
            onRevokeInvitation = vm::revokeInvitation, onDismissInvitation = vm::dismissInvitation, onDismissResetCode = vm::dismissResetCode,
            onLoadMoreAudit = vm::loadMoreAudit, onCopy = { copySensitive(context, it) },
            onShare = { code ->
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.invitation_share_text, code))
                context.startActivity(android.content.Intent.createChooser(send, null))
            },
            onDismissError = vm::dismissError,
        ),
    )
}

/** Copies [text] and asks the system to keep it out of previews and suggestions where it supports that. */
internal fun copySensitive(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
        ClipData.newPlainText(null, text).apply {
            description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminContent(state: AdminUiState, cb: AdminCallbacks, modifier: Modifier = Modifier) {
    var pending by remember { mutableStateOf<Pending?>(null) }
    var createInvite by remember { mutableStateOf(false) }
    val errorText = errorTexts()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_title)) }, navigationIcon = { BackButton(cb.onBack) },
                actions = {
                    if (state.busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = state.tab.ordinal, edgePadding = 8.dp) {
                AdminTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab, onClick = { cb.onTab(tab) }, modifier = Modifier.heightIn(min = 48.dp),
                        text = { Text(stringResource(tabTitle(tab))) },
                    )
                }
            }
            state.error?.let { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(errorText(e), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = cb.onDismissError) { Text(stringResource(R.string.action_ok)) }
                }
            }
            when (state.tab) {
                AdminTab.USERS -> UsersTab(state, cb, onAsk = { pending = it })
                AdminTab.INVITATIONS -> InvitationsTab(state, cb, onCreate = { createInvite = true }, onAsk = { pending = Pending.OnInvitation(it) })
                AdminTab.STATUS -> StatusTab(state, cb)
                AdminTab.LOG -> LogTab(state, cb)
            }
        }
    }

    pending?.let { p -> ConfirmAdminDialog(p, cb, onDone = { pending = null }) }
    if (createInvite) InvitationDialog(onDismiss = { createInvite = false }, onCreate = { n, h, a -> cb.onCreateInvitation(n, h, a); createInvite = false })
    state.invitation?.let { inv ->
        AlertDialog(
            onDismissRequest = cb.onDismissInvitation,
            properties = SecureDialogProperties,
            title = { Text(stringResource(R.string.invitation_created)) },
            text = { InvitationCreated(inv.code, state.invitationQr, cb.onCopy, cb.onShare) },
            confirmButton = { TextButton(onClick = cb.onDismissInvitation) { Text(stringResource(R.string.action_close)) } },
        )
    }
    state.resetCode?.let { r ->
        AlertDialog(
            onDismissRequest = cb.onDismissResetCode,
            properties = SecureDialogProperties,
            title = { Text(stringResource(R.string.admin_reset_code_title, r.userName)) },
            text = { ResetCodeCard(r, whenText = rememberScheduleFormatter(), onCopy = cb.onCopy) },
            confirmButton = { TextButton(onClick = cb.onDismissResetCode) { Text(stringResource(R.string.admin_reset_code_done)) } },
        )
    }
}

private fun tabTitle(tab: AdminTab): Int = when (tab) {
    AdminTab.USERS -> R.string.admin_tab_users
    AdminTab.INVITATIONS -> R.string.admin_tab_invitations
    AdminTab.STATUS -> R.string.admin_tab_status
    AdminTab.LOG -> R.string.admin_tab_log
}

/** The reset code, once: large, copyable, with when it stops working and what it does to the account. */
@Composable
internal fun ResetCodeCard(r: ResetCodeUi, whenText: (Long) -> String, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.admin_reset_code_text, r.userName), style = MaterialTheme.typography.bodyMedium)
        Text(r.code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.admin_reset_code_expires, whenText(r.expiresAt)), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onCopy(r.code) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.action_copy), modifier = Modifier.padding(start = 8.dp))
        }
        Text(stringResource(R.string.admin_reset_code_once), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.admin_reset_code_effects), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The reset-code dialog as a plain card (the screenshot test cannot capture a dialog window). */
@Composable
internal fun ResetCodePreview(r: ResetCodeUi) {
    androidx.compose.material3.Surface(Modifier.padding(24.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
        Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.admin_reset_code_title, r.userName), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp))
            ResetCodeCard(r, whenText = rememberScheduleFormatter(), onCopy = {})
        }
    }
}

// --- users --------------------------------------------------------------------------------------------------------------------

@Composable
private fun UsersTab(state: AdminUiState, cb: AdminCallbacks, onAsk: (Pending) -> Unit) {
    when (val u = state.users) {
        Loadable.Idle, Loadable.Loading -> Loading()
        Loadable.Failed -> Failed(cb.onReload)
        is Loadable.Ready -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.admin_users_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(u.value, key = { it.id }) { user -> UserCard(user, state.me, enabled = !state.busy, onAsk = onAsk,
                onAsk2 = { onAsk(Pending.OnDevice(user, it)) }) }
        }
    }
}

@Composable
private fun UserCard(user: AdminUser, me: String, enabled: Boolean, onAsk: (Pending) -> Unit, onAsk2: (AdminDevice) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val whenText = rememberScheduleFormatter()
    val removed = user.disabledAt != null
    val actions = adminActions(user, me)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (removed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(user.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("@${user.username}" + if (user.id == me) " · " + stringResource(R.string.admin_you) else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (actions.isNotEmpty()) {
                    IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.admin_user_actions, user.displayName))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        actions.forEach { action ->
                            DropdownMenuItem(text = { Text(stringResource(actionLabel(action))) },
                                onClick = { menu = false; onAsk(Pending.OnUser(action, user)) })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (user.isAdmin) Badge(R.string.admin_badge_admin, MaterialTheme.colorScheme.primary)
                if (user.suspendedAt != null) Badge(R.string.admin_badge_suspended, MaterialTheme.colorScheme.error)
                if (removed) Badge(R.string.admin_badge_removed, MaterialTheme.colorScheme.error)
            }
            Text(
                if (user.lastSeenAt != null) stringResource(R.string.last_seen, whenText(user.lastSeenAt!!)) else stringResource(R.string.never_seen),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
            if (!removed) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.admin_devices, user.activeDevices, user.activeDevices) + " · " + formatSize(user.attachmentBytes),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(48.dp)) {
                        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(R.string.admin_show_devices, user.displayName))
                    }
                }
                if (expanded) user.devices.forEach { d -> DeviceHealthRow(d, canRevoke = user.id != me && enabled, whenText = whenText,
                    onRevoke = { onAsk2(d) }) }
            }
        }
    }
}

@Composable
private fun pluralStringResource(id: Int, count: Int, vararg args: Any): String =
    androidx.compose.ui.res.pluralStringResource(id, count, *args)

@Composable
private fun Badge(label: Int, color: androidx.compose.ui.graphics.Color) {
    Text(
        stringResource(label), style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold,
    )
}

private fun actionLabel(a: AdminAction): Int = when (a) {
    AdminAction.SUSPEND -> R.string.admin_action_suspend
    AdminAction.UNSUSPEND -> R.string.admin_action_unsuspend
    AdminAction.MAKE_ADMIN -> R.string.admin_action_make_admin
    AdminAction.REMOVE_ADMIN -> R.string.admin_action_remove_admin
    AdminAction.RESET_CODE -> R.string.admin_action_reset_code
    AdminAction.REMOVE -> R.string.admin_action_remove
}

/** One device of an account with the health of its push: registered or not, the server's last attempt and how many failed in a row. */
@Composable
private fun DeviceHealthRow(d: AdminDevice, canRevoke: Boolean, whenText: (Long) -> String, onRevoke: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
            Text(d.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (d.lastSeenAt > 0) stringResource(R.string.last_seen, whenText(d.lastSeenAt)) else stringResource(R.string.never_seen),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val unhealthy = d.pushRegistered && (d.pushFailures > 0 || (d.lastPushStatus != null && d.lastPushStatus !in 200..299))
            Text(
                pushHealth(d), style = MaterialTheme.typography.bodySmall,
                color = if (!d.pushRegistered || unhealthy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canRevoke) {
            IconButton(onClick = onRevoke, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.revoke_device_description, d.name),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun pushHealth(d: AdminDevice): String = when {
    !d.pushRegistered -> stringResource(R.string.admin_push_none)
    d.lastPushAt == null -> stringResource(R.string.admin_push_untested)
    d.lastPushStatus == 0 -> stringResource(R.string.admin_push_no_answer, d.pushFailures)
    d.lastPushStatus != null && d.lastPushStatus !in 200..299 -> stringResource(R.string.admin_push_refused, d.lastPushStatus!!, d.pushFailures)
    d.pushFailures > 0 -> stringResource(R.string.admin_push_failing, d.pushFailures)
    else -> stringResource(R.string.admin_push_ok)
}

// --- the confirmations ----------------------------------------------------------------------------------------------------------

@Composable
private fun ConfirmAdminDialog(p: Pending, cb: AdminCallbacks, onDone: () -> Unit) {
    when (p) {
        is Pending.OnUser -> when (p.action) {
            AdminAction.REMOVE -> RemoveUserDialog(
                p.user, onDismiss = onDone,
                onConfirm = { password -> runAdminAction(AdminAction.REMOVE, p.user, password, cb); onDone() },
            )
            else -> {
                val (title, text, label) = when (p.action) {
                    AdminAction.SUSPEND -> Triple(R.string.admin_suspend_title, R.string.admin_suspend_text, R.string.admin_action_suspend)
                    AdminAction.UNSUSPEND -> Triple(R.string.admin_unsuspend_title, R.string.admin_unsuspend_text, R.string.admin_action_unsuspend)
                    AdminAction.MAKE_ADMIN -> Triple(R.string.admin_make_admin_title, R.string.admin_make_admin_text, R.string.admin_action_make_admin)
                    AdminAction.REMOVE_ADMIN -> Triple(R.string.admin_remove_admin_title, R.string.admin_remove_admin_text, R.string.admin_action_remove_admin)
                    else -> Triple(R.string.admin_reset_title, R.string.admin_reset_text, R.string.admin_reset_create)
                }
                // a reset code takes the account over, so it also needs the administrator's own password
                var password by remember { mutableStateOf("") }
                val needsPassword = p.action == AdminAction.RESET_CODE
                AlertDialog(
                    onDismissRequest = onDone,
                    title = { Text(stringResource(title, p.user.displayName)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(text, p.user.displayName))
                            if (needsPassword) AdminPasswordForm(password, onPassword = { password = it })
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !needsPassword || canConfirmReset(password),
                            onClick = {
                                val typed = password
                                password = "" // never kept after use
                                runAdminAction(p.action, p.user, typed, cb)
                                onDone()
                            },
                        ) { Text(stringResource(label)) }
                    },
                    dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
                )
            }
        }
        is Pending.OnDevice -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text(stringResource(R.string.admin_revoke_device_title, p.device.name, p.user.displayName)) },
            text = { Text(stringResource(R.string.admin_revoke_device_text)) },
            confirmButton = { TextButton(onClick = { cb.onRevokeDevice(p.user.id, p.device.id); onDone() }) { Text(stringResource(R.string.action_revoke)) } },
            dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
        )
        is Pending.OnInvitation -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text(stringResource(R.string.admin_revoke_invitation_title)) },
            text = { Text(stringResource(R.string.admin_revoke_invitation_text)) },
            confirmButton = { TextButton(onClick = { cb.onRevokeInvitation(p.invitation.id); onDone() }) { Text(stringResource(R.string.action_revoke)) } },
            dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Removing an account cannot be undone: the username has to be typed to enable the button, and the administrator's own
 * password is asked for as well (an administrator's session alone must not be enough).
 */
@Composable
private fun RemoveUserDialog(user: AdminUser, onDismiss: () -> Unit, onConfirm: (adminPassword: String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_remove_title, user.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.admin_remove_text), style = MaterialTheme.typography.bodyMedium)
                RemoveUserForm(user.username, typed, onTyped = { typed = it })
                AdminPasswordForm(password, onPassword = { password = it })
            }
        },
        confirmButton = { TextButton(enabled = canRemoveUser(typed, user.username, password),
            onClick = { val given = password; password = ""; onConfirm(given) }) { Text(stringResource(R.string.admin_action_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A reset code takes the account over, so it needs the administrator's own password as well. */
fun canConfirmReset(adminPassword: String): Boolean = adminPassword.isNotEmpty()

/** Removing needs the username typed and the administrator's own password. */
fun canRemoveUser(typed: String, username: String, adminPassword: String): Boolean =
    canConfirmRemoval(typed, username) && adminPassword.isNotEmpty()

/** What a confirmed [action] calls; [adminPassword] only goes to the two calls the server protects with it. */
internal fun runAdminAction(action: AdminAction, user: AdminUser, adminPassword: String, cb: AdminCallbacks) = when (action) {
    AdminAction.SUSPEND -> cb.onSuspend(user.id)
    AdminAction.UNSUSPEND -> cb.onUnsuspend(user.id)
    AdminAction.MAKE_ADMIN -> cb.onSetAdmin(user.id, true)
    AdminAction.REMOVE_ADMIN -> cb.onSetAdmin(user.id, false)
    AdminAction.RESET_CODE -> cb.onResetCode(user, adminPassword)
    AdminAction.REMOVE -> cb.onRemoveUser(user.id, adminPassword)
}

/** The removal is confirmed once the username was typed exactly (surrounding spaces do not count). */
fun canConfirmRemoval(typed: String, username: String): Boolean = typed.trim() == username

/** The line that names the username and the field for it (apart from the dialog so that it can be tested). */
@Composable
internal fun RemoveUserForm(username: String, typed: String, onTyped: (String) -> Unit) {
    Text(stringResource(R.string.admin_remove_type, username), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    OutlinedTextField(typed, onTyped, singleLine = true, label = { Text(stringResource(R.string.username)) })
}

/** The calling administrator's own password, asked for before a reset code is created or a user is removed. Not logged, not kept. */
@Composable
internal fun AdminPasswordForm(password: String, onPassword: (String) -> Unit) {
    Text(stringResource(R.string.admin_confirm_password), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        password, onPassword, singleLine = true, label = { Text(stringResource(R.string.admin_your_password)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

// --- invitations, status, log -----------------------------------------------------------------------------------------------------

@Composable
private fun InvitationsTab(state: AdminUiState, cb: AdminCallbacks, onCreate: () -> Unit, onAsk: (Invitation) -> Unit) {
    when (val l = state.invitations) {
        Loadable.Idle, Loadable.Loading -> Loading()
        Loadable.Failed -> Failed(cb.onReload)
        is Loadable.Ready -> {
            val whenText = rememberScheduleFormatter()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Button(onClick = onCreate, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.create_invitation), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (l.value.isEmpty()) item { Text(stringResource(R.string.admin_invitations_empty), style = MaterialTheme.typography.bodyMedium) }
                items(l.value, key = { it.id }) { inv -> InvitationCard(inv, whenText, enabled = !state.busy, onRevoke = { onAsk(inv) }) }
            }
        }
    }
}

/** What an invitation is now. */
enum class InvitationState { ACTIVE, USED, REVOKED, EXPIRED }

fun invitationState(i: Invitation, now: Long): InvitationState = when {
    i.revokedAt != null -> InvitationState.REVOKED
    i.remaining > 0 && i.expiresAt > now -> InvitationState.ACTIVE
    i.remaining <= 0 && (i.usedAt != null || i.uses >= i.maxUses) -> InvitationState.USED
    else -> InvitationState.EXPIRED
}

@Composable
private fun InvitationCard(inv: Invitation, whenText: (Long) -> String, enabled: Boolean, onRevoke: () -> Unit) {
    val state = invitationState(inv, se.swahnchat.app.ui.components.LocalNowMillis.current)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(inv.note?.takeIf { it.isNotBlank() } ?: stringResource(R.string.admin_invitation_no_note), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(
                        when (state) {
                            InvitationState.ACTIVE -> R.string.admin_invitation_active
                            InvitationState.USED -> R.string.admin_invitation_used
                            InvitationState.REVOKED -> R.string.admin_invitation_revoked
                            InvitationState.EXPIRED -> R.string.admin_invitation_expired
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state == InvitationState.ACTIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.admin_invitation_details, inv.uses, inv.maxUses,
                        whenText(inv.expiresAt)) + if (inv.isAdmin) " · " + stringResource(R.string.admin_badge_admin) else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state == InvitationState.ACTIVE) {
                IconButton(onClick = onRevoke, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.admin_revoke_invitation_title),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun StatusTab(state: AdminUiState, cb: AdminCallbacks) {
    when (val s = state.stats) {
        Loadable.Idle, Loadable.Loading -> Loading()
        Loadable.Failed -> Failed(cb.onReload)
        is Loadable.Ready -> {
            val st = s.value
            val whenText = rememberScheduleFormatter()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { StatLine(R.string.admin_stat_version, st.version.ifBlank { "?" }) }
                item {
                    val current = st.migration.current
                    StatLine(
                        R.string.admin_stat_migration,
                        when {
                            current == null -> stringResource(R.string.diagnostics_unknown)
                            current == st.migration.head -> stringResource(R.string.admin_stat_migration_ok, current)
                            else -> stringResource(R.string.admin_stat_migration_behind, current, st.migration.head)
                        },
                        bad = current != st.migration.head,
                    )
                }
                item { StatLine(R.string.admin_stat_users, st.users.toString()) }
                item { StatLine(R.string.admin_stat_devices, st.activeDevices.toString()) }
                item { StatLine(R.string.admin_stat_conversations, st.conversations.toString()) }
                item { StatLine(R.string.admin_stat_messages, st.messages.toString()) }
                item { StatLine(R.string.admin_stat_undelivered, st.undeliveredEnvelopes.toString()) }
                item { StatLine(R.string.admin_stat_attachments, "${st.attachments} · ${formatSize(st.attachmentsBytes)}") }
                item { StatLine(R.string.admin_stat_db, st.dbBytes?.let { formatSize(it) } ?: stringResource(R.string.diagnostics_unknown)) }
                item { StatLine(R.string.admin_stat_disk, formatSize(st.diskFreeBytes), bad = st.diskFreeBytes < LOW_DISK_BYTES) }
                item { StatLine(R.string.admin_stat_uptime, uptimeText(st.uptimeSeconds)) }
                item {
                    val m = st.dbMaintenance
                    StatLine(
                        R.string.admin_stat_maintenance,
                        if (m == null) stringResource(R.string.admin_stat_maintenance_none)
                        else stringResource(if (m.ok) R.string.admin_stat_maintenance_ok else R.string.admin_stat_maintenance_failed, whenText(m.at), m.vacuum),
                        bad = m != null && !m.ok,
                    )
                }
            }
        }
    }
}

private const val LOW_DISK_BYTES = 500L * 1024 * 1024

internal fun uptimeText(seconds: Long): String {
    val d = seconds / 86_400
    val h = seconds % 86_400 / 3_600
    val m = seconds % 3_600 / 60
    return if (d > 0) "$d d $h h" else if (h > 0) "$h h $m min" else "$m min"
}

@Composable
private fun StatLine(label: Int, value: String, bad: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** The name the log shows for a user id: the display name if the user list knows it, else a short form of the id. */
fun auditName(id: String?, users: List<AdminUser>, unknown: String): String =
    when {
        id == null -> unknown
        else -> users.firstOrNull { it.id == id }?.let { "${it.displayName} (@${it.username})" } ?: id.take(8)
    }

@Composable
private fun LogTab(state: AdminUiState, cb: AdminCallbacks) {
    when (val a = state.audit) {
        Loadable.Idle, Loadable.Loading -> Loading()
        Loadable.Failed -> Failed(cb.onReload)
        is Loadable.Ready -> {
            val users = (state.users as? Loadable.Ready)?.value.orEmpty()
            val whenText = rememberScheduleFormatter()
            val unknown = stringResource(R.string.diagnostics_unknown)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringResource(R.string.admin_log_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(a.value, key = { it.id }) { e -> AuditRow(e, users, unknown, whenText(e.at)) }
                item {
                    if (state.auditHasMore) {
                        OutlinedButton(onClick = cb.onLoadMoreAudit, enabled = !state.auditLoadingMore,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.admin_log_more))
                        }
                    } else if (a.value.isEmpty()) {
                        Text(stringResource(R.string.admin_log_empty))
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditRow(e: AuditEvent, users: List<AdminUser>, unknown: String, time: String) {
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(auditLabel(e.action), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val actor = auditName(e.actorUserId, users, unknown)
        val target = e.targetUserId?.takeIf { it != e.actorUserId }?.let { auditName(it, users, unknown) }
        Text(
            stringResource(R.string.admin_log_actor, actor) + (target?.let { " → $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

/** The words for the actions the server knows (docs/SECURITY.md); anything else is shown as the server wrote it. */
@Composable
internal fun auditLabel(action: String): String {
    val res = when (action) {
        "invitation_created" -> R.string.audit_invitation_created
        "invitation_revoked" -> R.string.audit_invitation_revoked
        "invitation_redeemed" -> R.string.audit_invitation_redeemed
        "admin_granted" -> R.string.audit_admin_granted
        "admin_revoked" -> R.string.audit_admin_revoked
        "user_suspended" -> R.string.audit_user_suspended
        "user_unsuspended" -> R.string.audit_user_unsuspended
        "login_blocked" -> R.string.audit_login_blocked
        "login_success" -> R.string.audit_login_success
        "login_failure" -> R.string.audit_login_failure
        "password_changed" -> R.string.audit_password_changed
        "password_change_failed" -> R.string.audit_password_change_failed
        "password_reset_issued" -> R.string.audit_password_reset_issued
        "password_reset_used" -> R.string.audit_password_reset_used
        "device_revoked" -> R.string.audit_device_revoked
        "device_renamed" -> R.string.audit_device_renamed
        "user_removed" -> R.string.audit_user_removed
        "self_delete_failed" -> R.string.audit_self_delete_failed
        "refresh_token_reuse" -> R.string.audit_refresh_token_reuse
        "presence_setting_changed" -> R.string.audit_presence_setting_changed
        else -> null
    }
    return if (res != null) stringResource(res) else action.take(60)
}

@Composable
private fun Loading() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
}

@Composable
private fun Failed(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.admin_load_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
    }
}
