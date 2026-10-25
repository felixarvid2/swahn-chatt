package se.familychat.app.ui.screens

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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.familychat.app.R
import se.familychat.app.data.ChatRepository
import se.familychat.app.data.StorageReport
import se.familychat.app.data.StorageUsage
import se.familychat.app.ui.components.Avatar
import se.familychat.app.ui.components.formatSize
import se.familychat.app.ui.model.AvatarUi
import se.familychat.core.client.FamilyChatEngine
import javax.inject.Inject

data class StorageRowUi(val conversationId: String, val title: String, val avatar: AvatarUi, val bytes: Long, val files: Int)

data class StorageUiState(
    val loading: Boolean = true,
    val rows: List<StorageRowUi> = emptyList(),
    val mediaBytes: Long = 0,
    val otherBytes: Long = 0,
    val databaseBytes: Long = 0,
    val cacheBytes: Long = 0,
    val busy: Boolean = false,
    /** Bytes freed by the last action, shown once ([StorageViewModel.messageShown] clears it). */
    val freed: Long? = null,
) {
    val totalBytes: Long get() = mediaBytes + otherBytes + databaseBytes + cacheBytes
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val usage: StorageUsage,
    private val repo: ChatRepository,
    private val engine: FamilyChatEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { usage.report() }
            val titles = runCatching { repo.conversationItems("").first() }.getOrDefault(emptyList()).associateBy { it.id }
            _state.update { it.fromReport(report, titles) }
        }
    }

    private fun StorageUiState.fromReport(report: StorageReport, titles: Map<String, se.familychat.app.ui.model.ConversationItemUi>) = copy(
        loading = false, busy = false, mediaBytes = report.mediaBytes, otherBytes = report.otherBytes,
        databaseBytes = report.databaseBytes, cacheBytes = report.cacheBytes,
        rows = report.conversations.map { c ->
            val item = titles[c.conversationId]
            StorageRowUi(c.conversationId, item?.title.orEmpty(), item?.avatar ?: AvatarUi(""), c.mediaBytes, c.fileCount)
        },
    )

    /** Deletes the downloaded media of one conversation, or of all ([conversationId] null). Messages stay. */
    fun clearMedia(conversationId: String?) = act { engine.clearDownloadedMedia(conversationId) }

    fun clearCache() = act { withContext(Dispatchers.IO) { usage.clearCache() } }

    fun messageShown() = _state.update { it.copy(freed = null) }

    private fun act(block: suspend () -> Long) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val freed = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                0L
            }
            _state.update { it.copy(freed = freed) }
            refresh()
        }
    }
}

@Composable
fun StorageScreen(onBack: () -> Unit, vm: StorageViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    StorageContent(state, onBack, vm::clearMedia, vm::clearCache, vm::messageShown)
}

private sealed interface StorageConfirm {
    data class Media(val row: StorageRowUi) : StorageConfirm
    data object AllMedia : StorageConfirm
    data object Cache : StorageConfirm
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageContent(
    state: StorageUiState,
    onBack: () -> Unit,
    onClearMedia: (conversationId: String?) -> Unit,
    onClearCache: () -> Unit,
    onMessageShown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf<StorageConfirm?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val freed = state.freed
    LaunchedEffect(freed) {
        if (freed != null) {
            snackbar.showSnackbar(context.getString(R.string.storage_freed, android.text.format.Formatter.formatShortFileSize(context, freed)))
            onMessageShown()
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.storage)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "total") { TotalCard(state) }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { confirm = StorageConfirm.AllMedia }, enabled = !state.busy && state.mediaBytes > 0,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.storage_clear_all_media)) }
                    OutlinedButton(
                        onClick = { confirm = StorageConfirm.Cache }, enabled = !state.busy && state.cacheBytes > 0,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.storage_clear_cache)) }
                    Text(
                        stringResource(R.string.storage_clear_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "per-conversation") {
                Text(stringResource(R.string.storage_per_conversation), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!state.loading && state.rows.isEmpty()) {
                item(key = "none") { Text(stringResource(R.string.storage_no_media), style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.rows, key = { it.conversationId }) { row ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(row.avatar, size = 40.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(
                            row.title.ifBlank { stringResource(R.string.unknown_user) }, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.storage_row_detail, formatSize(row.bytes), row.files), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { confirm = StorageConfirm.Media(row) }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.storage_clear_conversation, row.title))
                    }
                }
                HorizontalDivider()
            }
        }
    }
    when (val c = confirm) {
        null -> Unit
        is StorageConfirm.Media -> ConfirmClear(
            R.string.storage_clear_conversation_title, stringResource(R.string.storage_clear_conversation_text, c.row.title, formatSize(c.row.bytes)),
            onDismiss = { confirm = null }, onConfirm = { onClearMedia(c.row.conversationId); confirm = null },
        )
        StorageConfirm.AllMedia -> ConfirmClear(
            R.string.storage_clear_all_title, stringResource(R.string.storage_clear_all_text, formatSize(state.mediaBytes)),
            onDismiss = { confirm = null }, onConfirm = { onClearMedia(null); confirm = null },
        )
        StorageConfirm.Cache -> ConfirmClear(
            R.string.storage_clear_cache_title, stringResource(R.string.storage_clear_cache_text, formatSize(state.cacheBytes)),
            onDismiss = { confirm = null }, onConfirm = { onClearCache(); confirm = null },
        )
    }
}

@Composable
private fun TotalCard(state: StorageUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.storage_total), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(formatSize(state.totalBytes), style = MaterialTheme.typography.headlineMedium)
                }
                if (state.loading || state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            HorizontalDivider()
            SizeRow(R.string.storage_media, state.mediaBytes)
            SizeRow(R.string.storage_database, state.databaseBytes)
            SizeRow(R.string.storage_cache, state.cacheBytes)
            SizeRow(R.string.storage_other, state.otherBytes)
        }
    }
}

@Composable
private fun SizeRow(label: Int, bytes: Long) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(formatSize(bytes), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConfirmClear(title: Int, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.storage_clear_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
