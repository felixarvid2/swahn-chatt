package se.swahnchat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import se.swahnchat.app.R
import se.swahnchat.app.data.ChatRepository
import se.swahnchat.app.ui.components.Avatar
import se.swahnchat.app.ui.components.plainFormatted
import se.swahnchat.app.ui.components.rememberListTimeFormatter
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.model.attachmentKindOf
import se.swahnchat.app.ui.model.AttachmentKindUi
import se.swahnchat.core.client.SwahnChatEngine
import javax.inject.Inject

/** One starred message in the list. [senderName] null: written by this account. */
data class StarredRowUi(
    val messageId: String,
    val conversationId: String,
    val chatTitle: String,
    val chatAvatar: AvatarUi,
    val senderName: String?,
    val time: Long,
    val text: String?,
    val attachmentKind: AttachmentKindUi?,
    val isPoll: Boolean = false,
    val isLocation: Boolean = false,
)

data class StarredState(val loaded: Boolean = false, val rows: List<StarredRowUi> = emptyList())

/**
 * The starred messages of one conversation (route argument `conversation`) or of all of them. Stars exist on this phone only;
 * a star disappears with its message (deleted, expired) and with the conversation.
 */
@HiltViewModel
class StarredViewModel @Inject constructor(savedState: SavedStateHandle, repo: ChatRepository, engine: SwahnChatEngine) : ViewModel() {
    val conversationId: String? = savedState["conversation"]

    val state: StateFlow<StarredState> = combine(
        repo.starred(conversationId), repo.userMap(), repo.conversationItems("?"), engine.account,
    ) { messages, users, chats, account ->
        val byId = chats.associateBy { it.id }
        StarredState(
            loaded = true,
            rows = messages.filter { !it.deleted }.map { m ->
                val chat = byId[m.conversationId]
                StarredRowUi(
                    messageId = m.id, conversationId = m.conversationId, chatTitle = chat?.title.orEmpty(),
                    chatAvatar = chat?.avatar ?: AvatarUi("?"),
                    senderName = if (m.senderUserId == account?.userId) null else users[m.senderUserId]?.displayName ?: "?",
                    time = m.serverTs ?: m.clientTs, text = m.text,
                    attachmentKind = m.attachments.firstOrNull()?.let { attachmentKindOf(it.kind) }, isPoll = m.poll != null, isLocation = m.location != null,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StarredState())
}

@Composable
fun StarredScreen(onBack: () -> Unit, onOpen: (conversationId: String, messageId: String) -> Unit, vm: StarredViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    StarredContent(state, showChat = vm.conversationId == null, onBack = onBack, onOpen = onOpen)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredContent(
    state: StarredState,
    showChat: Boolean,
    onBack: () -> Unit,
    onOpen: (conversationId: String, messageId: String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.starred_title)) },
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
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                Text(
                    stringResource(R.string.starred_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp).semantics { heading() },
                )
                Text(
                    stringResource(R.string.starred_empty_text), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            val clock = rememberListTimeFormatter()
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(state.rows, key = { it.messageId }) { row ->
                    StarredRow(row, showChat, clock(row.time), onClick = { onOpen(row.conversationId, row.messageId) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun StarredRow(row: StarredRowUi, showChat: Boolean, time: String, onClick: () -> Unit) {
    val who = row.senderName ?: stringResource(R.string.starred_own_message)
    val body = when {
        row.isPoll -> "📊 ${plainFormatted(row.text.orEmpty())}"
        row.isLocation -> "📍 ${stringResource(R.string.attachment_location)}"
        !row.text.isNullOrBlank() -> plainFormatted(row.text)
        row.attachmentKind != null -> stringResource(
            when (row.attachmentKind) {
                AttachmentKindUi.IMAGE -> R.string.attachment_photo
                AttachmentKindUi.VIDEO -> R.string.attachment_video
                AttachmentKindUi.VOICE -> R.string.attachment_voice
                AttachmentKindUi.FILE -> R.string.attachment_file
            },
        )
        else -> ""
    }
    val title = if (showChat && row.chatTitle.isNotEmpty()) "$who · ${row.chatTitle}" else who
    val open = stringResource(R.string.starred_open_in_chat)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = open, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $body, $time" },
        verticalAlignment = Alignment.Top,
    ) {
        if (showChat) {
            Avatar(row.chatAvatar, size = 40.dp)
        } else {
            Icon(
                Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp).padding(8.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
