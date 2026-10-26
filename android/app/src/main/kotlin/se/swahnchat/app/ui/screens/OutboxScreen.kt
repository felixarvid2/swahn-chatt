package se.swahnchat.app.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.swahnchat.app.R
import se.swahnchat.app.data.ChatRepository
import se.swahnchat.app.data.db.UnsentRow
import se.swahnchat.app.data.db.decodeAttachments
import se.swahnchat.app.media.AttachmentPreparer
import se.swahnchat.app.platform.work.WorkScheduler
import se.swahnchat.app.ui.components.plainFormatted
import se.swahnchat.app.ui.components.rememberScheduleFormatter
import se.swahnchat.core.client.FailureReason
import se.swahnchat.core.client.StoredMessage
import se.swahnchat.core.client.SwahnChatEngine
import javax.inject.Inject

/** Why a message is still in the outbox, worded for people; never the text of an error. */
enum class OutboxProblem {
    NONE, OFFLINE, RATE_LIMITED, SERVER, DEVICES_CHANGED, IDENTITY_CHANGED, REJECTED, TOO_LARGE, NOT_A_MEMBER, ATTACHMENT_MISSING, TOO_MANY_RETRIES, OTHER;

    companion object {
        /**
         * What the outbox knows ([failureReason] of a failed message, the engine's short [lastError] of one that is waiting) as one of the
         * kinds above. The engine's [lastError] can hold a network error's text, so only its few known tokens are recognised; the rest is
         * "no connection" (an I/O error) and never shown as it is.
         */
        fun of(failed: Boolean, failureReason: String?, lastError: String?): OutboxProblem {
            if (failed) {
                return when (runCatching { FailureReason.valueOf(failureReason.orEmpty()) }.getOrNull()) {
                    FailureReason.IDENTITY_CHANGED -> IDENTITY_CHANGED
                    FailureReason.REJECTED -> REJECTED
                    FailureReason.NOT_A_MEMBER -> NOT_A_MEMBER
                    FailureReason.TOO_LARGE -> TOO_LARGE
                    FailureReason.ATTACHMENT_MISSING -> ATTACHMENT_MISSING
                    FailureReason.TOO_MANY_RETRIES -> TOO_MANY_RETRIES
                    null -> OTHER
                }
            }
            val e = lastError ?: return NONE
            return when {
                e == "rate_limited" -> RATE_LIMITED
                e.startsWith("server_error") -> SERVER
                e == "mismatched_devices" -> DEVICES_CHANGED
                e == "identity_changed" -> IDENTITY_CHANGED
                e == "no_session" -> OTHER
                ':' in e && e.substringBefore(':').all { it.isLowerCase() || it == '_' } -> REJECTED
                else -> OFFLINE
            }
        }
    }
}

data class OutboxItemUi(
    val messageId: String,
    val conversationId: String,
    val chat: String,
    /** The text (with markup removed by the screen) or null for an attachment-only message. */
    val text: String?,
    val attachmentCount: Int,
    val time: Long,
    val failed: Boolean,
    val problem: OutboxProblem,
    val attempts: Int,
)

data class ScheduledItemUi(val messageId: String, val conversationId: String, val chat: String, val text: String?, val attachmentCount: Int, val sendAt: Long)

data class OutboxUiState(
    val loaded: Boolean = false,
    val items: List<OutboxItemUi> = emptyList(),
    val scheduled: List<ScheduledItemUi> = emptyList(),
    /** A retry pass is running. */
    val retrying: Boolean = false,
) {
    val failedCount: Int get() = items.count { it.failed }
}

internal fun unsentToUi(rows: List<UnsentRow>, titles: Map<String, String>, unknown: String): List<OutboxItemUi> = rows.map { r ->
    val failed = r.status == "FAILED"
    OutboxItemUi(
        r.messageId, r.conversationId, titles[r.conversationId] ?: unknown, r.text?.takeIf { it.isNotBlank() }, decodeAttachments(r.attachments).size,
        r.clientTs, failed, OutboxProblem.of(failed, r.failureReason, r.lastError), r.attempts,
    )
}

internal fun scheduledToUi(list: List<StoredMessage>, titles: Map<String, String>, unknown: String): List<ScheduledItemUi> = list.map {
    ScheduledItemUi(it.id, it.conversationId, titles[it.conversationId] ?: unknown, it.text?.takeIf { t -> t.isNotBlank() }, it.attachments.size,
        it.scheduledAt ?: it.clientTs)
}

@HiltViewModel
class OutboxViewModel @Inject constructor(
    @ApplicationContext context: Context,
    repo: ChatRepository,
    private val engine: SwahnChatEngine,
    private val work: WorkScheduler,
    private val preparer: AttachmentPreparer,
) : ViewModel() {
    private val unknown = context.getString(R.string.unknown_user)
    private val retrying = kotlinx.coroutines.flow.MutableStateFlow(false)

    val state: StateFlow<OutboxUiState> = combine(repo.unsent(), repo.scheduled(null), repo.chatTitles(unknown), retrying) { unsent, scheduled, titles, busy ->
        OutboxUiState(true, unsentToUi(unsent, titles, unknown), scheduledToUi(scheduled, titles, unknown), busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OutboxUiState())

    private fun launchQuietly(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the list follows the database: whatever could not be done is still there to try again
            }
        }
    }

    /** One message: a failed one is queued again first. The pass runs here and now; WorkManager keeps trying if it cannot finish. */
    fun retry(messageId: String, failed: Boolean) = launchQuietly {
        retrying.value = true
        try {
            if (failed) engine.retryMessage(messageId)
            work.enqueueOutbox()
            engine.processOutbox()
        } finally {
            retrying.value = false
        }
    }

    /** Every failed message is queued again and the outbox is sent. */
    fun retryAll() = launchQuietly {
        retrying.value = true
        try {
            state.value.items.filter { it.failed }.forEach { engine.retryMessage(it.messageId) }
            work.enqueueOutbox()
            engine.processOutbox()
        } finally {
            retrying.value = false
        }
    }

    /** Throws the message away: it is removed from the outbox and from this phone, and never sent. */
    fun discard(messageId: String) = launchQuietly {
        engine.discardUnsent(messageId)?.attachments?.forEach { preparer.deleteOutgoingCopy(it.localPath) }
    }
}

@Composable
fun OutboxScreen(onBack: () -> Unit, onOpenScheduled: (conversationId: String) -> Unit, onOpenChat: (conversationId: String) -> Unit,
    vm: OutboxViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    OutboxContent(state, onBack, vm::retry, vm::retryAll, vm::discard, onOpenScheduled, onOpenChat)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboxContent(
    state: OutboxUiState,
    onBack: () -> Unit,
    onRetry: (messageId: String, failed: Boolean) -> Unit,
    onRetryAll: () -> Unit,
    onDiscard: (messageId: String) -> Unit,
    onOpenScheduled: (conversationId: String) -> Unit,
    onOpenChat: (conversationId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var discarding by remember { mutableStateOf<OutboxItemUi?>(null) }
    val whenText = rememberScheduleFormatter()
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.outbox_title)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        if (state.loaded && state.items.isEmpty() && state.scheduled.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Text(stringResource(R.string.outbox_empty_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp).semantics { heading() })
                Text(stringResource(R.string.outbox_empty_text), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.items.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.outbox_section_unsent), style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                            Text(stringResource(R.string.outbox_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onRetryAll, enabled = !state.retrying, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.outbox_retry_all), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    items(state.items, key = { it.messageId }) { item ->
                        UnsentCard(item, whenText(item.time), onRetry = { onRetry(item.messageId, item.failed) }, onDiscard = { discarding = item },
                            onOpen = { onOpenChat(item.conversationId) })
                    }
                }
                if (state.scheduled.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.outbox_section_scheduled), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                        )
                    }
                    items(state.scheduled, key = { "s-" + it.messageId }) { item ->
                        ScheduledCard(item, whenText(item.sendAt), onOpen = { onOpenScheduled(item.conversationId) })
                    }
                }
            }
        }
    }
    discarding?.let { item ->
        AlertDialog(
            onDismissRequest = { discarding = null },
            title = { Text(stringResource(R.string.outbox_discard_title)) },
            text = { Text(stringResource(R.string.outbox_discard_text, item.chat)) },
            confirmButton = { TextButton(onClick = { onDiscard(item.messageId); discarding = null }) { Text(stringResource(R.string.outbox_discard)) } },
            dismissButton = { TextButton(onClick = { discarding = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun problemText(p: OutboxProblem): String? = when (p) {
    OutboxProblem.NONE -> null
    OutboxProblem.OFFLINE -> stringResource(R.string.outbox_problem_offline)
    OutboxProblem.RATE_LIMITED -> stringResource(R.string.outbox_problem_rate_limited)
    OutboxProblem.SERVER -> stringResource(R.string.outbox_problem_server)
    OutboxProblem.DEVICES_CHANGED -> stringResource(R.string.outbox_problem_devices_changed)
    OutboxProblem.IDENTITY_CHANGED -> stringResource(R.string.outbox_problem_identity_changed)
    OutboxProblem.REJECTED -> stringResource(R.string.outbox_problem_rejected)
    OutboxProblem.TOO_LARGE -> stringResource(R.string.outbox_problem_too_large)
    OutboxProblem.NOT_A_MEMBER -> stringResource(R.string.outbox_problem_not_a_member)
    OutboxProblem.ATTACHMENT_MISSING -> stringResource(R.string.outbox_problem_attachment_missing)
    OutboxProblem.TOO_MANY_RETRIES -> stringResource(R.string.outbox_problem_too_many)
    OutboxProblem.OTHER -> stringResource(R.string.outbox_problem_other)
}

@Composable
private fun messageSummary(text: String?, attachments: Int): String =
    text?.let(::plainFormatted)?.toString() ?: if (attachments > 0) stringResource(R.string.scheduled_attachment_summary) else ""

@Composable
private fun UnsentCard(item: OutboxItemUi, time: String, onRetry: () -> Unit, onDiscard: () -> Unit, onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onOpen).heightIn(min = 32.dp)) {
                Icon(
                    if (item.failed) Icons.Filled.ErrorOutline else Icons.Filled.Sync, contentDescription = null,
                    tint = if (item.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp),
                )
                Text(item.chat, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f))
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp))
            }
            Text(messageSummary(item.text, item.attachmentCount), style = MaterialTheme.typography.bodyLarge, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
            Text(
                stringResource(if (item.failed) R.string.outbox_state_failed else R.string.outbox_state_waiting), style = MaterialTheme.typography.labelMedium,
                color = if (item.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            problemText(item.problem)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.outbox_retry)) }
                IconButton(onClick = onDiscard, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.outbox_discard), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ScheduledCard(item: ScheduledItemUi, time: String, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.outbox_scheduled_for, item.chat, time), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            }
            Text(messageSummary(item.text, item.attachmentCount), style = MaterialTheme.typography.bodyLarge, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
