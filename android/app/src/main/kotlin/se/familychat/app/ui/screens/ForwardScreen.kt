package se.familychat.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.familychat.app.R
import se.familychat.app.data.ChatRepository
import se.familychat.app.di.ApplicationScope
import se.familychat.app.platform.work.WorkScheduler
import se.familychat.app.ui.components.ConversationPicker
import se.familychat.app.ui.model.ConversationItemUi
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.MAX_FORWARD_TARGETS
import javax.inject.Inject

/** Result of pressing "Vidarebefordra": the conversations it went to, or why it did not. */
sealed interface ForwardOutcome {
    data class Sent(val conversationIds: List<String>) : ForwardOutcome
    data object Failed : ForwardOutcome
}

@HiltViewModel
class ForwardViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext context: android.content.Context,
    repo: ChatRepository,
    private val engine: FamilyChatEngine,
    private val work: WorkScheduler,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val messageId: String = checkNotNull(savedState["messageId"])

    /** null while the first read is pending. */
    val items: StateFlow<List<ConversationItemUi>?> =
        repo.conversationItems(context.getString(R.string.unknown_user)).map<List<ConversationItemUi>, List<ConversationItemUi>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /**
     * Copies the message into each chosen conversation. Runs in the app's scope, not the screen's: decrypting and copying
     * attachments takes a moment and must not be cut short by leaving the screen.
     */
    fun forward(conversationIds: List<String>, onDone: (ForwardOutcome) -> Unit) {
        if (_sending.value || conversationIds.isEmpty()) return
        _sending.value = true
        viewModelScope.launch {
            val outcome = try {
                val ids = appScope.async { engine.forwardMessage(messageId, conversationIds) }.await()
                work.enqueueOutbox()
                ForwardOutcome.Sent(conversationIds.take(ids.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ForwardOutcome.Failed
            }
            _sending.value = false
            onDone(outcome)
        }
    }
}

/** Choose up to five conversations for [ForwardViewModel]'s message. */
@Composable
fun ForwardScreen(onBack: () -> Unit, onForwarded: (List<String>) -> Unit, vm: ForwardViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val failed = stringResource(R.string.forward_failed)
    val maxText = stringResource(R.string.forward_max, MAX_FORWARD_TARGETS)
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }
    ForwardContent(
        items = items, selected = selected, query = query, sending = sending, snackbar = snackbar, onBack = onBack, onQuery = { query = it },
        onToggle = { id ->
            selected = when {
                id in selected -> selected - id
                selected.size >= MAX_FORWARD_TARGETS -> { message = maxText; selected }
                else -> selected + id
            }
        },
        onSend = {
            vm.forward(selected.toList()) { outcome ->
                when (outcome) {
                    is ForwardOutcome.Sent -> {
                        Toast.makeText(context, R.string.forward_done, Toast.LENGTH_SHORT).show()
                        onForwarded(outcome.conversationIds)
                    }
                    ForwardOutcome.Failed -> message = failed
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardContent(
    items: List<ConversationItemUi>?,
    selected: Set<String>,
    query: String,
    sending: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSend: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.forward_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.forward_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = onSend,
                        enabled = selected.isNotEmpty() && !sending,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
                    ) {
                        if (sending) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Text(stringResource(R.string.forward_sending))
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.forward_send) + if (selected.isNotEmpty()) " (${selected.size})" else "")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (items == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                ConversationPicker(items = items, selected = selected, query = query, multiple = true, onQuery = onQuery, onToggle = onToggle)
            }
        }
    }
}
