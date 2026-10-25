package se.familychat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.familychat.app.R
import se.familychat.app.data.ChatRepository
import se.familychat.app.qr.SafetyQr
import se.familychat.app.ui.components.Avatar
import se.familychat.app.ui.components.QrImage
import se.familychat.app.ui.components.QrScannerDialog
import se.familychat.app.ui.model.UserUi
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.IdentityChange
import javax.inject.Inject

/**
 * A key that is blocked until the user accepts it: a changed key of a known device, or ([newDevice]) the key of a device
 * that was added next to verified ones.
 */
class PendingKeyUi(val newKey: ByteArray, val safetyGroups: List<String>, val newDevice: Boolean = false, val qr: String? = null)

data class DeviceSafetyUi(
    val deviceId: String,
    val number: Int,
    val name: String,
    /** The 60 digits in 12 groups of 5; empty if no identity is pinned yet (no message exchanged). */
    val groups: List<String>,
    val verified: Boolean,
    val pending: PendingKeyUi?,
    /** The text of this number's QR code (`fcsn1:...`); null if no identity is pinned yet. */
    val qr: String? = null,
)

/** What comparing a scanned QR code with a device's safety number found. */
enum class ScanVerdict { MATCH, MISMATCH, NOT_A_SAFETY_CODE }

/**
 * The result of scanning the other person's QR code for the device [deviceId]. [pendingKey]: the code was compared with the
 * number of a key that is still waiting for approval, so a match does not make it verified (that is a separate decision).
 */
data class ScanOutcomeUi(val deviceId: String, val deviceName: String, val verdict: ScanVerdict, val pendingKey: Boolean)

data class ContactUiState(
    val user: UserUi? = null,
    val devices: List<DeviceSafetyUi> = emptyList(),
    val loading: Boolean = true,
    /** The last accept was refused because the key does not match what the server lists for the device. */
    val acceptRefused: Boolean = false,
    val scan: ScanOutcomeUi? = null,
)

@HiltViewModel
class ContactViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repo: ChatRepository,
    private val engine: FamilyChatEngine,
) : ViewModel() {
    val userId: String = checkNotNull(savedState["userId"])
    private val refresh = MutableStateFlow(0)
    private val loading = MutableStateFlow(true)
    private val refused = MutableStateFlow(false)
    private val scan = MutableStateFlow<ScanOutcomeUi?>(null)

    private class Flags(val loading: Boolean, val refused: Boolean, val scan: ScanOutcomeUi?)

    private val flags = combine(loading, refused, scan, ::Flags)

    val state: StateFlow<ContactUiState> = combine(repo.user(userId), repo.devicesOf(userId), refresh, flags) { user, devices, _, f ->
        // CPU- and database-bound (5200 hash rounds per number, blocking reads of the pinned identity)
        val built = withContext(Dispatchers.Default) {
            val pending = engine.pendingIdentityChanges().filter { it.userId == userId }.associateBy { it.deviceId }
            val thisDevice = engine.account.value?.deviceId
            devices.filter { it.id != thisDevice && (it.identityKey != null || pending.containsKey(it.id)) }.map { d ->
                val p = pending[d.id]
                val number = engine.safetyNumber(userId, d.id)
                DeviceSafetyUi(
                    deviceId = d.id, number = d.number, name = d.name,
                    groups = number?.groups.orEmpty(),
                    verified = engine.isVerified(d.id),
                    pending = p?.newIdentityKey?.let { key ->
                        val newNumber = engine.safetyNumberFor(userId, d.id, key)
                        PendingKeyUi(key, newNumber?.groups.orEmpty(), p.kind == IdentityChange.Kind.NEW_DEVICE, newNumber?.let(SafetyQr::payload))
                    },
                    qr = number?.let(SafetyQr::payload),
                )
            }
        }
        ContactUiState(user, built, f.loading, f.refused, f.scan)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactUiState())

    init {
        viewModelScope.launch {
            runCatching { engine.refreshDevices(userId) }
            loading.value = false
        }
    }

    fun setVerified(deviceId: String, verified: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.setVerified(deviceId, verified) }
            refresh.value++
        }
    }

    /**
     * Compares a QR code scanned from the other person's phone with this device's safety number (the new number, for a key
     * that waits for approval). The scanned text is untrusted: anything that is not a safety number code is reported as such.
     */
    fun onScanned(deviceId: String, deviceName: String, text: String) {
        viewModelScope.launch {
            scan.value = withContext(Dispatchers.Default) {
                val pending = engine.pendingIdentityChanges().firstOrNull { it.userId == userId && it.deviceId == deviceId }
                val number = pending?.newIdentityKey?.let { engine.safetyNumberFor(userId, deviceId, it) } ?: engine.safetyNumber(userId, deviceId)
                val verdict = when (number?.let { SafetyQr.compare(it, text) }) {
                    SafetyQr.ScanResult.MATCH -> ScanVerdict.MATCH
                    SafetyQr.ScanResult.MISMATCH, null -> ScanVerdict.MISMATCH
                    SafetyQr.ScanResult.NOT_A_SAFETY_CODE -> ScanVerdict.NOT_A_SAFETY_CODE
                }
                ScanOutcomeUi(deviceId, deviceName, verdict, pendingKey = pending != null)
            }
        }
    }

    fun dismissScan() {
        scan.value = null
    }

    fun accept(deviceId: String, key: ByteArray) {
        viewModelScope.launch {
            refused.value = try {
                !engine.acceptIdentity(userId, deviceId, key)
            } catch (e: java.io.IOException) {
                true // the device list could not be checked; nothing was accepted
            }
            refresh.value++
        }
    }
}

@Composable
fun ContactScreen(onBack: () -> Unit, vm: ContactViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ContactContent(state, onBack, vm::setVerified, vm::accept, vm::onScanned, vm::dismissScan)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactContent(
    state: ContactUiState,
    onBack: () -> Unit,
    onVerified: (deviceId: String, verified: Boolean) -> Unit,
    onAccept: (deviceId: String, key: ByteArray) -> Unit,
    onScanned: (deviceId: String, deviceName: String, text: String) -> Unit = { _, _, _ -> },
    onDismissScan: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** The QR codes start out shown (screenshots; on the real screen a button shows them). */
    initiallyShowQr: Boolean = false,
) {
    var confirm by remember { mutableStateOf<Pair<DeviceSafetyUi, PendingKeyUi>?>(null) }
    var scanning by remember { mutableStateOf<DeviceSafetyUi?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.safety_number)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "head") {
                Column {
                    state.user?.let { u ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u.avatar, size = 56.dp)
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(u.name, style = MaterialTheme.typography.titleLarge)
                                Text("@${u.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.safety_explainer), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                    )
                    if (state.acceptRefused) {
                        Text(
                            stringResource(R.string.accept_refused), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (state.loading && state.devices.isEmpty()) CircularProgressIndicator(Modifier.padding(top = 16.dp))
                }
            }
            if (!state.loading && state.devices.isEmpty()) {
                item(key = "none") { Text(stringResource(R.string.safety_no_devices), style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.devices, key = { it.deviceId }) { d ->
                DeviceCard(
                    d, onVerified = { onVerified(d.deviceId, it) }, onAccept = { d.pending?.let { p -> confirm = d to p } },
                    onScan = { scanning = d }, initiallyShowQr = initiallyShowQr,
                )
            }
        }
    }
    scanning?.let { device ->
        QrScannerDialog(
            title = stringResource(R.string.scan_safety_title),
            hint = stringResource(R.string.scan_safety_hint, state.user?.name.orEmpty()),
            onResult = { text -> scanning = null; onScanned(device.deviceId, device.name, text) },
            onDismiss = { scanning = null },
        )
    }
    state.scan?.let { outcome -> ScanResultDialog(outcome, onVerified = { onVerified(outcome.deviceId, true) }, onDismiss = onDismissScan) }
    confirm?.let { (device, pending) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.accept_key_title)) },
            text = { Text(stringResource(R.string.accept_key_text, state.user?.name.orEmpty(), device.name)) },
            confirmButton = { TextButton(onClick = { onAccept(device.deviceId, pending.newKey); confirm = null }) { Text(stringResource(R.string.accept_key_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DeviceCard(d: DeviceSafetyUi, onVerified: (Boolean) -> Unit, onAccept: () -> Unit, onScan: () -> Unit, initiallyShowQr: Boolean) {
    val warning = d.pending != null
    var showQr by remember { mutableStateOf(initiallyShowQr) }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.device_with_number, d.name, d.number), style = MaterialTheme.typography.titleMedium)
            if (d.pending != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        stringResource(if (d.pending.newDevice) R.string.new_device_warning else R.string.identity_changed_warning), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 12.dp),
                    )
                }
                if (d.pending.safetyGroups.isNotEmpty()) {
                    Text(stringResource(R.string.new_safety_number), style = MaterialTheme.typography.labelLarge)
                    SafetyDigits(d.pending.safetyGroups)
                    OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.scan_qr), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Button(
                    onClick = onAccept, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(R.string.accept_key_button)) }
                if (d.groups.isNotEmpty()) {
                    Text(stringResource(R.string.previous_safety_number), style = MaterialTheme.typography.labelLarge)
                    SafetyDigits(d.groups)
                }
            } else if (d.groups.isEmpty()) {
                Text(stringResource(R.string.safety_not_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SafetyDigits(d.groups)
                QrActions(d.qr, showQr, onToggle = { showQr = !showQr }, onScan = onScan)
                if (d.verified) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.verified), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).weight(1f))
                        TextButton(onClick = { onVerified(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.remove_verification)) }
                    }
                } else {
                    OutlinedButton(onClick = { onVerified(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.mark_verified))
                    }
                }
            }
        }
    }
}

/** "Show my QR code" (the other person scans it) and "Scan their QR code", with the code below when it is shown. */
@Composable
private fun QrActions(qr: String?, showQr: Boolean, onToggle: () -> Unit, onScan: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (qr != null) {
            OutlinedButton(onClick = onToggle, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(if (showQr) R.string.hide_qr else R.string.show_qr), modifier = Modifier.padding(start = 8.dp))
            }
        }
        OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.scan_qr), modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (qr != null && showQr) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QrImage(qr, stringResource(R.string.safety_qr_description), Modifier.size(224.dp))
            Text(stringResource(R.string.safety_qr_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The verdict of a scan: a match offers "mark as verified", a mismatch is a clear warning. */
@Composable
private fun ScanResultDialog(outcome: ScanOutcomeUi, onVerified: () -> Unit, onDismiss: () -> Unit) {
    val ok = outcome.verdict == ScanVerdict.MATCH
    val bad = outcome.verdict == ScanVerdict.MISMATCH
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                stringResource(
                    when (outcome.verdict) {
                        ScanVerdict.MATCH -> R.string.scan_match_title
                        ScanVerdict.MISMATCH -> R.string.scan_mismatch_title
                        ScanVerdict.NOT_A_SAFETY_CODE -> R.string.scan_not_safety_title
                    },
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    when {
                        ok && outcome.pendingKey -> R.string.scan_match_pending_text
                        ok -> R.string.scan_match_text
                        bad -> R.string.scan_mismatch_text
                        else -> R.string.scan_not_safety_text
                    },
                    outcome.deviceName,
                ),
                color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            if (ok && !outcome.pendingKey) {
                TextButton(onClick = { onVerified(); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.mark_verified)) }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_ok)) }
            }
        },
        dismissButton = if (ok && !outcome.pendingKey) {
            { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) } }
        } else null,
    )
}

/** The 12 groups of 5 digits, three groups per line so they are easy to read aloud. */
@Composable
fun SafetyDigits(groups: List<String>, modifier: Modifier = Modifier) {
    val description = groups.joinToString(", ") { it.toList().joinToString(" ") }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp)
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        groups.chunked(3).forEach { line ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                line.forEach { g ->
                    Text(g, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 72.dp))
                }
            }
        }
    }
}
