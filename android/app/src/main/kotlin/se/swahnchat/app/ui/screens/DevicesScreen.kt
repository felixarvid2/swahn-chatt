package se.swahnchat.app.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.swahnchat.app.R
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.UiError
import se.swahnchat.app.data.toUiError
import se.swahnchat.app.ui.components.rememberScheduleFormatter
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.model.Device
import javax.inject.Inject

/** A device of this account. [pushRegistered] and [lastPushStatus] are only known for the device in the hand: the server shows them to nobody else. */
data class OwnDeviceUi(
    val id: String,
    val name: String,
    val lastSeen: Long,
    val isCurrent: Boolean,
    val pushRegistered: Boolean? = null,
    val lastPushStatus: Int? = null,
)

data class DevicesUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val devices: List<OwnDeviceUi> = emptyList(),
    /** New devices of this account that wait for the owner's approval (the safety screen decides). */
    val pendingApproval: Int = 0,
    val busy: Boolean = false,
    val error: UiError? = null,
)

/** The longest name the server accepts. */
const val MAX_DEVICE_NAME = 64

/** The name as it will be sent, or null if it is not allowed (empty or longer than 64 characters). */
fun validDeviceName(input: String): String? = input.trim().takeIf { it.length in 1..MAX_DEVICE_NAME }

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val engine: SwahnChatEngine,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()
    val userId: String get() = engine.account.value?.userId.orEmpty()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val me = engine.account.value ?: return@launch
            try {
                val list = engine.myDevices()
                val current = runCatching { engine.currentDevice() }.getOrNull()
                val pending = runCatching { engine.pendingIdentityChanges().count { it.userId == me.userId } }.getOrDefault(0)
                _state.update {
                    it.copy(
                        loading = false, failed = false, pendingApproval = pending,
                        devices = list.sortedWith(compareByDescending<Device> { d -> d.id == me.deviceId }.thenByDescending { d -> d.lastSeenAt }).map { d ->
                            val isCurrent = d.id == me.deviceId
                            OwnDeviceUi(
                                d.id, d.name, d.lastSeenAt, isCurrent,
                                pushRegistered = if (isCurrent) prefs.pushEndpointUploaded != null else null,
                                lastPushStatus = if (isCurrent) current?.lastPushStatus else null,
                            )
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private fun run(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUiError()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun rename(id: String, name: String) {
        val valid = validDeviceName(name) ?: return
        run { engine.renameDevice(id, valid) }
    }

    fun revoke(id: String) = run { engine.revokeDevice(id) }
    fun dismissError() = _state.update { it.copy(error = null) }
}

@Composable
fun DevicesScreen(onBack: () -> Unit, onOpenOwnSafety: (userId: String) -> Unit, vm: DevicesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) { vm.load(); onPauseOrDispose {} }
    DevicesContent(state, onBack, vm::rename, vm::revoke, onOpenOwnSafety = { onOpenOwnSafety(vm.userId) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesContent(
    state: DevicesUiState,
    onBack: () -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onRevoke: (id: String) -> Unit,
    onOpenOwnSafety: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<OwnDeviceUi?>(null) }
    var revoking by remember { mutableStateOf<OwnDeviceUi?>(null) }
    val errorText = errorTexts()
    val whenText = rememberScheduleFormatter()
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.devices_title)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.devices_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.pendingApproval > 0) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.devices_pending_title, state.pendingApproval), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.devices_pending_text), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = onOpenOwnSafety,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.devices_pending_open)) }
                        }
                    }
                }
            }
            state.error?.let { item { Text(errorText(it), color = MaterialTheme.colorScheme.error) } }
            when {
                state.loading -> item { CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), strokeWidth = 2.dp) }
                state.failed -> item { Text(stringResource(R.string.devices_load_failed), color = MaterialTheme.colorScheme.error) }
                else -> items(state.devices, key = { it.id }) { d ->
                    DeviceCard(d, lastSeen = if (d.lastSeen > 0) whenText(d.lastSeen) else null, enabled = !state.busy, onRename = { renaming = d },
                        onRevoke = { revoking = d })
                }
            }
            item {
                OutlinedButton(onClick = onOpenOwnSafety, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.my_devices_safety), modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.my_devices_safety_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    renaming?.let { device ->
        var text by remember(device.id) { mutableStateOf(device.name) }
        val valid = validDeviceName(text)
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.devices_rename_title)) },
            text = { RenameDeviceForm(text, onChange = { text = it }) },
            confirmButton = { TextButton(enabled = valid != null,
                onClick = { onRename(device.id, text); renaming = null }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    revoking?.let { device ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(R.string.revoke_device_title, device.name)) },
            text = { Text(stringResource(R.string.revoke_device_text)) },
            confirmButton = { TextButton(onClick = { onRevoke(device.id); revoking = null }) { Text(stringResource(R.string.action_revoke)) } },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The explanation and the field of the rename dialog (apart from the dialog so that it can be tested). */
@Composable
internal fun RenameDeviceForm(text: String, onChange: (String) -> Unit) {
    val valid = validDeviceName(text)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.devices_rename_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = text, onValueChange = { onChange(it.take(MAX_DEVICE_NAME + 1)) }, singleLine = true, isError = valid == null,
            label = { Text(stringResource(R.string.device_name)) },
            supportingText = { Text(if (valid == null) stringResource(R.string.field_error_device_name) else "${text.trim().length}/$MAX_DEVICE_NAME") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
    }
}

@Composable
private fun DeviceCard(device: OwnDeviceUi, lastSeen: String?, enabled: Boolean, onRename: () -> Unit, onRevoke: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                if (device.isCurrent) Text(stringResource(R.string.this_device), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    if (lastSeen != null) stringResource(R.string.last_seen, lastSeen) else stringResource(R.string.never_seen),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val push = when {
                    device.pushRegistered == null -> stringResource(R.string.devices_push_unknown)
                    device.pushRegistered == false -> stringResource(R.string.devices_push_none)
                    device.lastPushStatus == null -> stringResource(R.string.devices_push_registered)
                    device.lastPushStatus == 0 -> stringResource(R.string.devices_push_no_answer)
                    device.lastPushStatus in 200..299 -> stringResource(R.string.devices_push_ok)
                    else -> stringResource(R.string.devices_push_refused, device.lastPushStatus)
                }
                Text(push, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRename, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.devices_rename_description, device.name))
            }
            if (!device.isCurrent) {
                IconButton(onClick = onRevoke, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.revoke_device_description, device.name),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
