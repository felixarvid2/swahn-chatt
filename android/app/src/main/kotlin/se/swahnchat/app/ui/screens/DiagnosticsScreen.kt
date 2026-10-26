package se.swahnchat.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.swahnchat.app.R
import se.swahnchat.app.ui.components.formatSize
import se.swahnchat.app.diagnostics.BatteryOptimization
import se.swahnchat.app.diagnostics.DiagnosticsCollector
import se.swahnchat.app.diagnostics.DiagnosticsReport
import se.swahnchat.app.diagnostics.EventLog
import se.swahnchat.app.diagnostics.PushTestResult
import se.swahnchat.app.diagnostics.PushTester
import se.swahnchat.app.diagnostics.Reachability
import se.swahnchat.app.diagnostics.SessionChange
import se.swahnchat.app.diagnostics.SessionEvent
import se.swahnchat.app.diagnostics.exportText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

enum class ExportState { IDLE, DONE, FAILED }

data class DiagnosticsUiState(
    val report: DiagnosticsReport,
    val loading: Boolean = false,
    val testing: Boolean = false,
    val testResult: PushTestResult? = null,
    val export: ExportState = ExportState.IDLE,
    val logEntries: Int = 0,
)

class DiagnosticsCallbacks(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onTestPush: () -> Unit = {},
    val onBattery: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onOpenOutbox: () -> Unit = {},
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val collector: DiagnosticsCollector,
    testers: PushTester.Factory,
    private val log: EventLog,
) : ViewModel() {
    private val tester = testers.create()
    private val _state = MutableStateFlow(DiagnosticsUiState(collector.local(), loading = true, logEntries = log.entries().size))
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, report = collector.local()) }
        viewModelScope.launch {
            val report = collector.collect()
            _state.update { it.copy(report = report, loading = false, logEntries = log.entries().size) }
        }
    }

    /** Re-reads the local figures only (the person came back from a system page). */
    fun refreshLocal() = _state.update { it.copy(report = collector.local().copy(reachability = it.report.reachability, latencyMs = it.report.latencyMs,
        outboxSize = it.report.outboxSize, lastPushAt = it.report.lastPushAt, lastPushStatus = it.report.lastPushStatus)) }

    fun testPush() {
        if (_state.value.testing) return
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val r = tester.run()
            _state.update { it.copy(testing = false, testResult = r) }
            refresh()
        }
    }

    /** The copyable summary: the same text as the top of the export. */
    fun summary(): String = _state.value.report.toText(ZoneId.systemDefault())

    /** Writes the summary and the log to [uri], which the person chose in the system's file dialog. */
    fun exportTo(uri: android.net.Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val text = exportText(collector.local().copy(outboxSize = _state.value.report.outboxSize), log, ZoneId.systemDefault())
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) log.record(SessionEvent(SessionChange.LOG_EXPORTED))
            _state.update { it.copy(export = if (ok) ExportState.DONE else ExportState.FAILED) }
        }
    }

    fun dismissExport() = _state.update { it.copy(export = ExportState.IDLE) }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, onOpenOutbox: () -> Unit, vm: DiagnosticsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let(vm::exportTo) }
    val fileName = stringResource(R.string.diagnostics_file_name, LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) { vm.refreshLocal(); onPauseOrDispose {} }
    DiagnosticsContent(
        state,
        DiagnosticsCallbacks(
            onBack = onBack, onRefresh = vm::refresh, onTestPush = vm::testPush,
            onBattery = { BatteryOptimization.open(context) },
            onCopy = { copySummary(context, vm.summary()) },
            onExport = { picker.launch(fileName) },
            onOpenOutbox = onOpenOutbox,
        ),
        onDismissExport = vm::dismissExport,
    )
}

private fun copySummary(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsContent(state: DiagnosticsUiState, cb: DiagnosticsCallbacks, modifier: Modifier = Modifier, onDismissExport: () -> Unit = {}) {
    var confirmExport by remember { mutableStateOf(false) }
    val r = state.report
    val dateTime = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT) }
    fun time(ms: Long) = if (ms <= 0) null else dateTime.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    val never = stringResource(R.string.diagnostics_never)
    val unknown = stringResource(R.string.diagnostics_unknown)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) }, navigationIcon = { BackButton(cb.onBack) },
                actions = {
                    if (state.loading) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp)
                    IconButton(onClick = cb.onRefresh, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.diagnostics_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.diagnostics_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Group(R.string.diagnostics_group_connection) {
                Fact(R.string.diagnostics_server, r.serverHost ?: unknown)
                Fact(
                    R.string.diagnostics_reachable,
                    when (r.reachability) {
                        Reachability.NOT_CHECKED -> unknown
                        Reachability.REACHABLE -> stringResource(R.string.diagnostics_reachable_yes) + (r.latencyMs?.let { " ($it ms)" } ?: "")
                        Reachability.UNREACHABLE -> stringResource(R.string.diagnostics_reachable_no)
                        Reachability.TLS_PROBLEM -> stringResource(R.string.diagnostics_reachable_tls)
                        Reachability.UNEXPECTED_ANSWER -> stringResource(R.string.diagnostics_reachable_unexpected)
                        Reachability.INVALID_ADDRESS -> stringResource(R.string.diagnostics_reachable_invalid)
                    },
                    ok = r.reachability == Reachability.REACHABLE,
                    bad = r.reachability != Reachability.NOT_CHECKED && r.reachability != Reachability.REACHABLE,
                )
                Fact(R.string.diagnostics_server_version, stringResource(R.string.diagnostics_server_version_admin))
                Fact(R.string.diagnostics_clock, r.clockOffsetMs?.let { stringResource(R.string.diagnostics_clock_value, it) } ?: unknown,
                    bad = (r.clockOffsetMs ?: 0).let { kotlin.math.abs(it) > CLOCK_WARN_MS })
                Fact(R.string.diagnostics_realtime, r.realtime?.name ?: unknown)
                Fact(R.string.diagnostics_last_sync, time(r.lastSyncAt) ?: never)
            }

            Group(R.string.diagnostics_group_push) {
                Fact(
                    R.string.diagnostics_distributor,
                    when {
                        !r.distributorInstalled -> stringResource(R.string.diagnostics_distributor_none)
                        else -> r.distributorLabel ?: stringResource(R.string.diagnostics_distributor_not_chosen)
                    },
                    ok = r.distributorInstalled && r.distributorLabel != null, bad = !r.distributorInstalled,
                )
                Fact(R.string.diagnostics_endpoint, stringResource(if (r.endpointRegistered) R.string.yes else R.string.no), ok = r.endpointRegistered,
                    bad = !r.endpointRegistered)
                Fact(
                    R.string.diagnostics_last_push,
                    (r.lastPushAt?.let(::time) ?: never) + (r.lastPushStatus?.let { " · " + stringResource(R.string.diagnostics_push_answer, it) } ?: ""),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = cb.onTestPush, enabled = !state.testing && r.endpointRegistered, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.push_test_button))
                    }
                    if (state.testing) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(24.dp), strokeWidth = 2.dp)
                }
                state.testResult?.let { StatusLine(it is PushTestResult.Delivered, pushTestText(it)) }
            }

            Group(R.string.diagnostics_group_phone) {
                Fact(R.string.diagnostics_notifications, stringResource(if (r.notificationsAllowed) R.string.yes else R.string.no),
                    ok = r.notificationsAllowed, bad = !r.notificationsAllowed)
                Fact(
                    R.string.diagnostics_battery,
                    stringResource(if (r.batteryUnrestricted) R.string.battery_status_ok else R.string.battery_status_restricted),
                    ok = r.batteryUnrestricted, bad = !r.batteryUnrestricted,
                )
                if (!r.batteryUnrestricted) {
                    OutlinedButton(onClick = cb.onBattery, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.battery_allow)) }
                }
                Fact(R.string.diagnostics_alarms, stringResource(R.string.diagnostics_alarms_value))
            }

            Group(R.string.diagnostics_group_data) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Fact(R.string.diagnostics_outbox, r.outboxSize.toString(), bad = r.outboxSize > 0) }
                    TextButton(onClick = cb.onOpenOutbox, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.diagnostics_open_outbox)) }
                }
                Fact(R.string.diagnostics_database, formatSize(r.databaseBytes))
                Fact(R.string.diagnostics_app, "${r.appVersion} (${r.buildType})")
                Fact(R.string.diagnostics_android, "API ${r.androidSdk}")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = cb.onCopy, modifier = Modifier.heightIn(min = 48.dp).weight(1f)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.diagnostics_copy), modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { confirmExport = true }, modifier = Modifier.heightIn(min = 48.dp).weight(1f)) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.diagnostics_export), modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(stringResource(R.string.diagnostics_copy_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state.export) {
                ExportState.DONE -> StatusLine(true, stringResource(R.string.diagnostics_export_done))
                ExportState.FAILED -> StatusLine(false, stringResource(R.string.diagnostics_export_failed))
                ExportState.IDLE -> Unit
            }
        }
    }
    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(stringResource(R.string.diagnostics_export_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.diagnostics_export_includes), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.diagnostics_export_includes_list, state.logEntries), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.diagnostics_export_excludes), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.diagnostics_export_excludes_list), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.diagnostics_export_share_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { confirmExport = false; cb.onExport() }) { Text(stringResource(R.string.diagnostics_export_choose)) } },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    LaunchedEffect(state.export) {
        if (state.export != ExportState.IDLE) {
            kotlinx.coroutines.delay(EXPORT_NOTICE_MS)
            onDismissExport()
        }
    }
}

private const val CLOCK_WARN_MS = 60_000L
private const val EXPORT_NOTICE_MS = 6_000L

@Composable
private fun Group(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() })
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
    }
}

@Composable
private fun Fact(label: Int, value: String, ok: Boolean = false, bad: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value, style = MaterialTheme.typography.bodyLarge,
            color = when {
                bad -> MaterialTheme.colorScheme.error
                ok -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
