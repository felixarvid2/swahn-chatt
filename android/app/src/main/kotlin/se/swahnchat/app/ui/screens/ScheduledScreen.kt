package se.swahnchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.swahnchat.app.R
import se.swahnchat.app.data.ChatRepository
import se.swahnchat.app.media.AttachmentPreparer
import se.swahnchat.app.platform.work.WorkScheduler
import se.swahnchat.app.ui.components.ScheduleDialog
import se.swahnchat.app.ui.components.defaultScheduleTime
import se.swahnchat.app.ui.components.plainFormatted
import se.swahnchat.app.ui.components.rememberScheduleFormatter
import se.swahnchat.core.client.MIN_SCHEDULE_MS
import se.swahnchat.core.client.SwahnChatEngine
import javax.inject.Inject

/** One message that waits for its time. */
data class ScheduledRowUi(val messageId: String, val text: String?, val attachmentCount: Int, val sendAt: Long)

data class ScheduledState(val loaded: Boolean = false, val rows: List<ScheduledRowUi> = emptyList())

/** The messages scheduled in one conversation (route argument `conversationId`): change the time, send now, delete. */
@HiltViewModel
class ScheduledViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repo: ChatRepository,
    private val engine: SwahnChatEngine,
    private val work: WorkScheduler,
    private val preparer: AttachmentPreparer,
) : ViewModel() {
    val conversationId: String = checkNotNull(savedState["conversationId"])

    val state: StateFlow<ScheduledState> = repo.scheduled(conversationId).map { list ->
        ScheduledState(true, list.map { ScheduledRowUi(it.id, it.text, it.attachments.size, it.scheduledAt ?: it.clientTs) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduledState())

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                work.scheduleSend(engine.nextScheduledAt())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the row is gone or its time has come in the meantime: the list updates by itself
            }
        }
    }

    fun reschedule(id: String, sendAt: Long) = run {
        val earliest = System.currentTimeMillis() + MIN_SCHEDULE_MS
        engine.rescheduleMessage(id, if (sendAt < earliest && sendAt >= earliest - 15_000) earliest else sendAt)
    }

    fun sendNow(id: String) = run {
        engine.sendScheduledNow(id)
        work.enqueueOutbox()
    }

    fun delete(id: String) = run {
        engine.cancelScheduled(id)?.attachments?.forEach { preparer.deleteOutgoingCopy(it.localPath) }
    }
}

@Composable
fun ScheduledScreen(onBack: () -> Unit, vm: ScheduledViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ScheduledContent(state, onBack, onReschedule = vm::reschedule, onSendNow = vm::sendNow, onDelete = vm::delete)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledContent(
    state: ScheduledState,
    onBack: () -> Unit,
    onReschedule: (messageId: String, sendAt: Long) -> Unit,
    onSendNow: (messageId: String) -> Unit,
    onDelete: (messageId: String) -> Unit,
) {
    var changing by remember { mutableStateOf<ScheduledRowUi?>(null) }
    var deleting by remember { mutableStateOf<ScheduledRowUi?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scheduled_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        if (state.loaded && state.rows.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                Text(
                    stringResource(R.string.scheduled_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp).semantics { heading() },
                )
                Text(
                    stringResource(R.string.scheduled_empty_text), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            val whenText = rememberScheduleFormatter()
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(stringResource(R.string.schedule_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.rows, key = { it.messageId }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    stringResource(R.string.scheduled_at, whenText(row.sendAt)), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            val body = row.text?.takeIf { it.isNotBlank() }?.let(::plainFormatted)
                                ?: if (row.attachmentCount > 0) stringResource(R.string.scheduled_attachment_summary) else ""
                            Text(
                                body,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp, end = 8.dp),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(onClick = { changing = row }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Filled.EditCalendar, contentDescription = stringResource(R.string.scheduled_change_time))
                                }
                                IconButton(onClick = { onSendNow(row.messageId) }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.scheduled_send_now))
                                }
                                IconButton(onClick = { deleting = row }, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.scheduled_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    changing?.let { row ->
        ScheduleDialog(
            initial = row.sendAt.takeIf { it > System.currentTimeMillis() } ?: defaultScheduleTime(System.currentTimeMillis()),
            now = System::currentTimeMillis,
            onConfirm = { onReschedule(row.messageId, it); changing = null }, onDismiss = { changing = null },
            confirmLabel = stringResource(R.string.schedule_save),
        )
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.scheduled_delete_title)) },
            text = { Text(stringResource(R.string.scheduled_delete_text)) },
            confirmButton = { TextButton(onClick = { onDelete(row.messageId); deleting = null }) { Text(stringResource(R.string.scheduled_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
