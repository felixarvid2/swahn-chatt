package se.swahnchat.app.ui.screens

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import se.swahnchat.app.R
import se.swahnchat.app.data.ChatRepository
import se.swahnchat.app.data.SearchRepository
import se.swahnchat.app.data.Snippet
import se.swahnchat.app.ui.components.Avatar
import se.swahnchat.app.ui.components.rememberListTimeFormatter
import se.swahnchat.app.ui.model.SearchGroupUi
import se.swahnchat.app.ui.model.SearchHitUi
import se.swahnchat.app.ui.model.groupSearchHits
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    /** The conversation searched in (null: all conversations). */
    val conversationId: String? = null,
    val conversationTitle: String = "",
    val searched: Boolean = false,
    val groups: List<SearchGroupUi> = emptyList(),
) {
    val total: Int get() = groups.sumOf { it.hits.size }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext context: android.content.Context,
    private val search: SearchRepository,
    private val repo: ChatRepository,
) : ViewModel() {
    private val conversationId: String? = savedState["conversation"]
    private val query = MutableStateFlow("")
    private val unknown = context.getString(R.string.unknown_user)
    private val you = context.getString(R.string.you)

    val state: StateFlow<SearchUiState> = combine(query, query.debounce(DEBOUNCE_MS).distinctUntilChanged().flatMapLatest { q -> results(q) }) { q, r ->
        r.copy(query = q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState(conversationId = conversationId))

    fun onQuery(text: String) { query.value = text }

    private fun results(q: String) = flow {
        val conversations = repo.conversationItems(unknown).first().associateBy { it.id }
        val users = repo.userMap().first()
        val hits = search.search(q, conversationId).map {
            SearchHitUi(it.messageId, it.conversationId, it.time, if (it.outgoing) null else users[it.senderUserId]?.displayName ?: unknown, it.snippet)
        }
        emit(
            SearchUiState(
                conversationId = conversationId, conversationTitle = conversationId?.let { conversations[it]?.title }.orEmpty(),
                searched = q.isNotBlank(), groups = groupSearchHits(hits, conversations, unknown),
            ),
        )
    }

    private companion object {
        const val DEBOUNCE_MS = 200L
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenHit: (conversationId: String, messageId: String) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    SearchContent(state, vm::onQuery, onBack, onOpenHit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onOpenHit: (conversationId: String, messageId: String) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    val inConversation = state.conversationId != null
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = {
                    TextField(
                        value = state.query, onValueChange = onQuery, singleLine = true,
                        placeholder = { Text(stringResource(if (inConversation) R.string.search_in_conversation else R.string.search_messages_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.searched -> Hint(stringResource(R.string.search_empty))
                state.total == 0 -> Hint(stringResource(R.string.search_no_results))
                else -> {
                    Text(
                        pluralStringResource(R.plurals.search_hits, state.total, state.total), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        for (group in state.groups) {
                            if (!inConversation) {
                                item(key = "g-${group.conversationId}") { GroupHeader(group) }
                            }
                            items(group.hits, key = { it.messageId }) { hit ->
                                HitRow(hit, showSender = inConversation, onClick = { onOpenHit(hit.conversationId, hit.messageId) })
                                HorizontalDivider(Modifier.padding(start = if (inConversation) 16.dp else 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GroupHeader(group: SearchGroupUi) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(group.avatar, size = 36.dp)
        Text(
            group.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Text(group.hits.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HitRow(hit: SearchHitUi, showSender: Boolean, onClick: () -> Unit) {
    val time = rememberListTimeFormatter()
    val you = stringResource(R.string.you)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick)
            .padding(start = if (showSender) 16.dp else 64.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row {
            if (showSender) Text(hit.senderName ?: you, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            else androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text(time(hit.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val prefix = if (!showSender && hit.senderName == null) "$you: " else if (!showSender) "${hit.senderName}: " else ""
        Text(highlighted(prefix, hit.snippet), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** The snippet with the matching word starts emphasised. */
@Composable
internal fun highlighted(prefix: String, snippet: Snippet): AnnotatedString {
    val style = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, background = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
    return buildAnnotatedString {
        append(prefix)
        var at = 0
        for (r in snippet.highlights.sortedBy { it.first }) {
            if (r.first < at) continue
            append(snippet.text.substring(at, r.first))
            withStyle(style) { append(snippet.text.substring(r.first, r.last + 1)) }
            at = r.last + 1
        }
        append(snippet.text.substring(at))
    }
}
