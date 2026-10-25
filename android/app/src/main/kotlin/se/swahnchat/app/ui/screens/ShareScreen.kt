package se.swahnchat.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.swahnchat.app.R
import se.swahnchat.app.data.ChatRepository
import se.swahnchat.app.data.SessionManager
import se.swahnchat.app.data.SessionState
import se.swahnchat.app.media.AttachmentPreparer
import se.swahnchat.app.media.PendingShare
import se.swahnchat.app.media.PendingShares
import se.swahnchat.app.media.ShareIntake
import se.swahnchat.app.media.ShareRefusedException
import se.swahnchat.app.platform.security.AppLock
import se.swahnchat.app.ui.components.ConversationPicker
import se.swahnchat.app.ui.model.ConversationItemUi
import java.io.File
import javax.inject.Inject

/** What the share target knows about the incoming share. */
sealed interface ShareState {
    /** Files are being copied into the app's own storage. */
    data object Preparing : ShareState

    /** Nothing usable came in ([refused] things were turned away). */
    data class Nothing(val refused: Int) : ShareState

    /** Ready to be sent to a conversation. */
    data class Ready(val text: String?, val files: List<File>, val refused: Int, val truncated: Boolean) : ShareState
}

/**
 * Takes over what another app shares, before anything of ours is shown: reads the intent, copies the (content://) files into
 * private storage with a size limit, and holds the result for the picker. Locked or signed out, it shows no conversation.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preparer: AttachmentPreparer,
    private val pending: PendingShares,
    repo: ChatRepository,
    session: SessionManager,
    lock: AppLock,
) : ViewModel() {
    private val _state = MutableStateFlow<ShareState>(ShareState.Preparing)
    val state: StateFlow<ShareState> = _state.asStateFlow()

    /** The app lock hides the picker like it hides the rest of the app. */
    val locked: StateFlow<Boolean> = combine(lock.locked, session.state) { l, s -> l && s is SessionState.SignedIn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, lock.locked.value)

    val signedIn: StateFlow<Boolean?> = session.state.map { s ->
        when (s) {
            is SessionState.SignedIn -> true
            SessionState.SignedOut -> false
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val items: StateFlow<List<ConversationItemUi>?> =
        repo.conversationItems(context.getString(R.string.unknown_user)).map<List<ConversationItemUi>, List<ConversationItemUi>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var started = false

    /** Reads [intent] and copies what it carries; the grants of the sending app last as long as the activity does. */
    fun start(intent: Intent?) {
        if (started) return
        started = true
        viewModelScope.launch {
            val request = ShareIntake.parse(intent, context.packageName)
            var refused = request.refused
            val files = ArrayList<File>()
            withContext(Dispatchers.IO) {
                for (uri in request.uris) {
                    try {
                        files += ShareIntake.stage(context.contentResolver, uri, preparer.stagingDir, AttachmentPreparer.MAX_ATTACHMENT_BYTES)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ShareRefusedException) {
                        refused++
                    }
                }
            }
            _state.value =
                if (request.text.isNullOrBlank() && files.isEmpty()) ShareState.Nothing(refused)
                else ShareState.Ready(request.text?.takeIf { it.isNotBlank() }, files, refused, request.truncated)
        }
    }

    /** The user chose [conversationId]: the composer there opens with the text and files. */
    fun choose(conversationId: String): Boolean {
        val ready = _state.value as? ShareState.Ready ?: return false
        pending.put(conversationId, PendingShare(ready.text, ready.files))
        chosen = true
        return true
    }

    private var chosen = false

    /** The user backed out (or the screen is gone without a choice): the copies go. */
    fun discard() {
        if (chosen) return
        (_state.value as? ShareState.Ready)?.files?.forEach { f -> preparer.deleteStagedCopy(android.net.Uri.fromFile(f)) }
    }
}

/** The share picker: what is being shared, then the conversations (recent first, searchable). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    state: ShareState,
    items: List<ConversationItemUi>?,
    onChoose: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.padding(0.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ShareState.Preparing -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.share_preparing), modifier = Modifier.padding(top = 16.dp))
                }
                is ShareState.Nothing -> Text(
                    stringResource(if (state.refused > 0) R.string.share_rejected_all else R.string.share_nothing),
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                is ShareState.Ready -> Column(Modifier.fillMaxSize()) {
                    ShareSummary(state)
                    if (items == null) {
                        Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                    } else {
                        ConversationPicker(items, selected = emptySet(), query = query, multiple = false, onQuery = { query = it }, onToggle = onChoose)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareSummary(state: ShareState.Ready) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (state.text != null) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                    Text(
                        stringResource(R.string.share_summary_text, state.text), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (state.files.isNotEmpty()) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                    Text(
                        if (state.files.size == 1) state.files.single().name else stringResource(R.string.share_files_summary, state.files.size),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (state.refused > 0) {
                Text(
                    stringResource(R.string.share_rejected_some, state.refused), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state.truncated) {
                Text(
                    stringResource(R.string.share_too_many), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
