package se.swahnchat.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import se.swahnchat.app.R
import se.swahnchat.app.data.history.HistoryError
import se.swahnchat.app.data.history.HistoryFormat
import se.swahnchat.app.data.history.HistoryJob
import se.swahnchat.app.data.history.HistoryManager
import se.swahnchat.app.data.history.PassphraseStrength
import se.swahnchat.app.data.history.Strength
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(private val manager: HistoryManager) : ViewModel() {
    val job: StateFlow<HistoryJob> = manager.state
    fun export(target: Uri, passphrase: String, includeMedia: Boolean) = manager.export(target, passphrase, includeMedia)
    fun import(source: Uri, passphrase: String) = manager.import(source, passphrase)
    fun clear() = manager.clear()
}

@Composable
fun HistoryScreen(onBack: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val job by vm.job.collectAsStateWithLifecycle()
    var pickedExport by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val args = pickedExport
        if (uri != null && args != null) vm.export(uri, args.first, args.second)
        pickedExport = null
    }
    var importSource by remember { mutableStateOf<Uri?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importSource = uri }
    HistoryContent(
        job = job,
        onBack = { vm.clear(); onBack() },
        onExport = { passphrase, includeMedia ->
            pickedExport = passphrase to includeMedia
            create.launch("swahn-chat-historik-${LocalDate.now()}.fchx")
        },
        onChooseImport = { open.launch(arrayOf("*/*")) },
        importChosen = importSource != null,
        onImport = { passphrase -> importSource?.let { vm.import(it, passphrase) }; importSource = null },
        onCancelImport = { importSource = null },
        onDismissResult = vm::clear,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryContent(
    job: HistoryJob,
    onBack: () -> Unit,
    onExport: (passphrase: String, includeMedia: Boolean) -> Unit,
    onChooseImport: () -> Unit,
    importChosen: Boolean,
    onImport: (passphrase: String) -> Unit,
    onCancelImport: () -> Unit,
    onDismissResult: () -> Unit,
) {
    // Plain `remember`, not saved with the screen's state: the passphrase must not end up in a saved bundle.
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var includeMedia by remember { mutableStateOf(false) }
    val busy = job is HistoryJob.Exporting || job is HistoryJob.ImportReading || job is HistoryJob.ImportMerging
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            JobStatus(job, onDismissResult)

            Text(stringResource(R.string.history_export_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.history_export_text), style = MaterialTheme.typography.bodyMedium)
            PassphraseField(passphrase, { passphrase = it }, stringResource(R.string.history_passphrase), enabled = !busy)
            StrengthMeter(passphrase)
            PassphraseField(again, { again = it }, stringResource(R.string.history_passphrase_again), enabled = !busy)
            val mismatch = again.isNotEmpty() && again != passphrase
            if (mismatch) {
                Text(stringResource(R.string.history_passphrase_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = includeMedia, enabled = !busy, role = Role.Checkbox, onValueChange = { includeMedia = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = includeMedia, onCheckedChange = null, enabled = !busy)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(R.string.history_include_media), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.history_include_media_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = { onExport(passphrase, includeMedia); passphrase = ""; again = "" },
                enabled = !busy && passphrase.length >= HistoryFormat.MIN_PASSPHRASE_LENGTH && passphrase == again,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.history_export_action)) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.history_import_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.history_import_text), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onChooseImport, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.history_import_action))
            }
        }
    }
    if (importChosen) ImportPassphraseDialog(onImport, onCancelImport)
}

@Composable
private fun PassphraseField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StrengthMeter(passphrase: String) {
    if (passphrase.isEmpty()) return
    val strength = PassphraseStrength.rate(passphrase)
    val (label, fraction) = when (strength) {
        Strength.TOO_SHORT -> R.string.history_strength_too_short to 0.1f
        Strength.WEAK -> R.string.history_strength_weak to 0.3f
        Strength.FAIR -> R.string.history_strength_fair to 0.55f
        Strength.GOOD -> R.string.history_strength_good to 0.8f
        Strength.STRONG -> R.string.history_strength_strong to 1f
    }
    val color = when (strength) {
        Strength.TOO_SHORT, Strength.WEAK -> MaterialTheme.colorScheme.error
        Strength.FAIR -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val text = if (strength == Strength.TOO_SHORT) stringResource(label) else stringResource(R.string.history_strength_label, stringResource(label))
    Column {
        LinearProgressIndicator(progress = { fraction }, color = color, modifier = Modifier.fillMaxWidth())
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ImportPassphraseDialog(onImport: (String) -> Unit, onCancel: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.history_import_passphrase_title)) },
        text = {
            Column {
                Text(stringResource(R.string.history_import_passphrase_hint), style = MaterialTheme.typography.bodyMedium)
                PassphraseField(passphrase, { passphrase = it }, stringResource(R.string.history_passphrase), enabled = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(passphrase) }, enabled = passphrase.isNotEmpty()) { Text(stringResource(R.string.history_import_action)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun JobStatus(job: HistoryJob, onDismiss: () -> Unit) {
    when (job) {
        HistoryJob.Idle -> Unit
        is HistoryJob.Exporting -> Progress(stringResource(R.string.history_running_export, job.messages))
        is HistoryJob.ImportReading -> Progress(stringResource(R.string.history_running_import_read, job.messages))
        is HistoryJob.ImportMerging -> Progress(stringResource(R.string.history_running_import_merge, job.messages))
        is HistoryJob.Exported -> ResultCard(
            buildList {
                add(stringResource(R.string.history_export_done, job.result.messages, job.result.conversations))
                if (job.result.mediaFiles > 0) add(stringResource(R.string.history_export_done_media, job.result.mediaFiles))
                if (job.result.leftOut > 0) add(stringResource(R.string.history_export_left_out, job.result.leftOut))
            },
            error = false, onDismiss = onDismiss,
        )
        is HistoryJob.Imported -> ResultCard(
            buildList {
                add(stringResource(R.string.history_import_done, job.result.imported))
                if (job.result.alreadyHere > 0) add(stringResource(R.string.history_import_already, job.result.alreadyHere))
                if (job.result.unknownConversation > 0) add(stringResource(R.string.history_import_unknown_conversation, job.result.unknownConversation))
                if (job.result.invalid > 0) add(stringResource(R.string.history_import_invalid, job.result.invalid))
            },
            error = false, onDismiss = onDismiss,
        )
        is HistoryJob.Failed -> ResultCard(listOf(stringResource(errorText(job.error))), error = true, onDismiss = onDismiss)
    }
}

private fun errorText(e: HistoryError): Int = when (e) {
    HistoryError.NOT_A_FILE -> R.string.history_error_not_a_file
    HistoryError.VERSION -> R.string.history_error_version
    HistoryError.PARAMETERS -> R.string.history_error_parameters
    HistoryError.WRONG_PASSPHRASE -> R.string.history_error_wrong_passphrase
    HistoryError.DAMAGED -> R.string.history_error_damaged
    HistoryError.TOO_LARGE -> R.string.history_error_too_large
    HistoryError.WRONG_ACCOUNT -> R.string.history_error_wrong_account
    HistoryError.CONTENT -> R.string.history_error_content
    HistoryError.IO -> R.string.history_error_io
    HistoryError.WEAK -> R.string.history_error_weak
    HistoryError.UNKNOWN -> R.string.history_error_unknown
}

@Composable
private fun Progress(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 16.dp), strokeWidth = 3.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ResultCard(lines: List<String>, error: Boolean, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            for (l in lines) Text(l, style = MaterialTheme.typography.bodyMedium, color = color)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_ok)) }
        }
    }
}
