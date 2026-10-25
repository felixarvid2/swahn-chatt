package se.familychat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import se.familychat.app.R
import se.familychat.app.data.ChatRepository
import se.familychat.app.ui.components.Avatar
import se.familychat.app.ui.components.LocalNowMillis
import se.familychat.app.ui.components.currentLocale
import se.familychat.app.ui.components.rememberClockFormatter
import se.familychat.app.ui.model.MemberUi
import se.familychat.core.client.ReceiptTimes
import se.familychat.core.client.StoredMessage
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** What one member has done with a message of yours. A time is null where no receipt carried one. */
data class RecipientInfo(
    val member: MemberUi,
    val delivered: Boolean,
    val deliveredAt: Long?,
    val read: Boolean,
    val readAt: Long?,
)

data class MessageInfoState(
    val loaded: Boolean = false,
    /** The message is gone (deleted on this phone, or never existed): the screen closes. */
    val gone: Boolean = false,
    val isGroup: Boolean = false,
    val sentAt: Long? = null,
    val text: String? = null,
    val attachmentCount: Int = 0,
    val recipients: List<RecipientInfo> = emptyList(),
)

/**
 * The info of an own message: for each other member (the one person of a direct conversation, every other member of a group)
 * whether and when they received and read it. Whether comes from the message (`deliveredTo` / `readBy`, which is also what
 * the ticks use), when from the receipts that carried a time. Reading implies delivery. A read receipt can be missing for good
 * (a member may read silently), which shows as "–".
 */
fun buildMessageInfo(message: StoredMessage?, receipts: List<ReceiptTimes>, members: List<MemberUi>, isGroup: Boolean): MessageInfoState {
    if (message == null) return MessageInfoState(loaded = true, gone = true)
    val times = receipts.associateBy { it.userId }
    val recipients = members.filter { !it.isMe }.map { m ->
        val t = times[m.userId]
        val read = m.userId in message.readBy
        RecipientInfo(
            member = m, delivered = read || m.userId in message.deliveredTo || t?.deliveredAt != null,
            deliveredAt = t?.deliveredAt, read = read || t?.readAt != null, readAt = t?.readAt,
        )
    }
    return MessageInfoState(
        loaded = true, isGroup = isGroup, sentAt = message.serverTs ?: message.clientTs.takeIf { message.seq != null },
        text = message.text, attachmentCount = message.attachments.size, recipients = recipients,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MessageInfoViewModel @Inject constructor(savedState: SavedStateHandle, repo: ChatRepository) : ViewModel() {
    val messageId: String = checkNotNull(savedState["messageId"])

    val state: StateFlow<MessageInfoState> = repo.message(messageId).flatMapLatest { m ->
        if (m == null) {
            flowOf(MessageInfoState(loaded = true, gone = true))
        } else {
            combine(repo.receipts(messageId), repo.members(m.conversationId), repo.conversation(m.conversationId)) { receipts, members, conversation ->
                buildMessageInfo(m, receipts, members, isGroup = conversation?.type == se.familychat.core.model.ConversationType.GROUP)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessageInfoState())
}

@Composable
fun MessageInfoScreen(onBack: () -> Unit, vm: MessageInfoViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    MessageInfoContent(state, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInfoContent(state: MessageInfoState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dateTime = rememberDateTimeFormatter()
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.message_info)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "message") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val text = state.text?.takeIf { it.isNotBlank() }
                            ?: if (state.attachmentCount > 0) stringResource(R.string.message_info_attachment) else ""
                        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        state.sentAt?.let {
                            Text(
                                stringResource(R.string.message_info_sent, dateTime(it)), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
            item(key = "head") {
                Text(
                    stringResource(if (state.isGroup) R.string.message_info_members else R.string.message_info_person),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!state.loaded) item(key = "loading") { CircularProgressIndicator(Modifier.padding(16.dp)) }
            items(state.recipients, key = { it.member.userId }) { r -> RecipientCard(r, dateTime) }
            item(key = "hint") {
                Text(
                    stringResource(R.string.message_info_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecipientCard(r: RecipientInfo, dateTime: (Long) -> String) {
    val delivered = statusText(r.delivered, r.deliveredAt, dateTime)
    val read = statusText(r.read, r.readAt, dateTime)
    val deliveredLabel = stringResource(R.string.message_info_delivered)
    val readLabel = stringResource(R.string.message_info_read)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "${r.member.name}. $deliveredLabel: $delivered. $readLabel: $read"
        },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Avatar(r.member.avatar, size = 40.dp)
            Column(Modifier.padding(start = 16.dp).weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.member.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusLine(Icons.Filled.Done, deliveredLabel, delivered, r.delivered)
                StatusLine(Icons.Filled.DoneAll, readLabel, read, r.read)
            }
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp),
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The time if it is known, "Time unknown" if the message is known to have got there, else a dash. */
@Composable
private fun statusText(done: Boolean, at: Long?, dateTime: (Long) -> String): String = when {
    at != null -> dateTime(at)
    done -> stringResource(R.string.message_info_time_unknown)
    else -> stringResource(R.string.message_info_none)
}

/** "Idag 14:32", "Igår 09:05" or "24 oktober 18:20", following the device's clock setting. */
@Composable
private fun rememberDateTimeFormatter(): (Long) -> String {
    val clock = rememberClockFormatter()
    val now = LocalNowMillis.current
    val zone = ZoneId.systemDefault()
    val locale = currentLocale()
    val today = stringResource(R.string.today)
    val yesterday = stringResource(R.string.yesterday)
    return remember(clock, now, zone, locale, today, yesterday) {
        { ms ->
            val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val nowDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val label = when (day) {
                nowDay -> today
                nowDay.minusDays(1) -> yesterday
                else -> java.time.format.DateTimeFormatter.ofPattern(if (day.year == nowDay.year) "d MMM" else "d MMM yyyy", locale).format(day)
            }
            "$label ${clock(ms)}"
        }
    }
}
