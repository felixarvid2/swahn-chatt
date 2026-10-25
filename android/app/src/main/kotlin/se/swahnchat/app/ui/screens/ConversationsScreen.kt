package se.swahnchat.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import se.swahnchat.app.data.ConversationSettingsStore
import se.swahnchat.app.data.MAX_PINNED
import se.swahnchat.app.data.MuteDuration
import se.swahnchat.app.platform.notify.Notifier
import se.swahnchat.app.ui.model.arrangeConversations
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.swahnchat.app.R
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.ChatRepository
import se.swahnchat.app.data.ConnectionStatus
import se.swahnchat.app.ui.components.ConnectionBanner
import se.swahnchat.app.ui.model.ConnectionUi
import se.swahnchat.app.platform.work.WorkScheduler
import se.swahnchat.app.ui.components.Avatar
import se.swahnchat.app.ui.components.DeliveryIcon
import se.swahnchat.app.ui.components.rememberListTimeFormatter
import se.swahnchat.app.ui.model.ConversationItemUi
import se.swahnchat.app.ui.model.PreviewKind
import se.swahnchat.core.client.SwahnChatEngine
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    @ApplicationContext context: android.content.Context,
    repo: ChatRepository,
    private val engine: SwahnChatEngine,
    private val prefs: AppPrefs,
    private val work: WorkScheduler,
    private val settings: ConversationSettingsStore,
    private val notifier: Notifier,
    connectionStatus: ConnectionStatus,
) : ViewModel() {
    val connection: StateFlow<ConnectionUi> = connectionStatus.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionUi.HIDDEN)

    /** null while the first read is pending. */
    val items: StateFlow<List<ConversationItemUi>?> =
        repo.conversationItems(context.getString(R.string.unknown_user)).map<List<ConversationItemUi>, List<ConversationItemUi>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** Short messages for a snackbar (string resource ids). */
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun pin(id: String) = viewModelScope.launch { if (!settings.pin(id, System.currentTimeMillis())) _messages.tryEmit(R.string.pin_limit) }
    fun unpin(id: String) = viewModelScope.launch { settings.unpin(id) }
    fun archive(id: String) = viewModelScope.launch { settings.archive(id) }
    fun unarchive(id: String) = viewModelScope.launch { settings.unarchive(id) }
    fun unmute(id: String) = viewModelScope.launch { settings.unmute(id) }
    fun mute(id: String, duration: MuteDuration) = viewModelScope.launch {
        settings.mute(id, duration, System.currentTimeMillis())
        notifier.cancel(id)
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                engine.refreshDirectory()
                engine.sync()
                engine.processOutbox()
            } catch (e: Exception) {
                // offline: the list shows what we have
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Asked once per install; the system remembers a "don't ask again". */
    fun shouldAskNotificationPermission(): Boolean = !prefs.notificationPermissionAsked.also { prefs.notificationPermissionAsked = true }

    init {
        work.enqueueOutbox()
    }
}

@Composable
fun ConversationsScreen(
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    vm: ConversationsViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            vm.shouldAskNotificationPermission()
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(context.getString(it, MAX_PINNED)) } }
    ConversationsContent(
        items, refreshing, vm::refresh, onOpen, onNew, onSettings, connection = connection, onSearch = onSearch,
        actions = ConversationActions(vm::pin, vm::unpin, vm::mute, vm::unmute, vm::archive, vm::unarchive),
        snackbarHost = { SnackbarHost(snackbar) },
    )
}

/** What the long-press menu of a conversation can do. */
class ConversationActions(
    val onPin: (String) -> Unit = {},
    val onUnpin: (String) -> Unit = {},
    val onMute: (String, MuteDuration) -> Unit = { _, _ -> },
    val onUnmute: (String) -> Unit = {},
    val onArchive: (String) -> Unit = {},
    val onUnarchive: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsContent(
    items: List<ConversationItemUi>?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    connection: ConnectionUi = ConnectionUi.HIDDEN,
    onSearch: () -> Unit = {},
    actions: ConversationActions = ConversationActions(),
    snackbarHost: @Composable () -> Unit = {},
    archivedExpanded: Boolean = false,
) {
    val sections = remember(items) { arrangeConversations(items.orEmpty()) }
    var showArchived by rememberSaveable { mutableStateOf(archivedExpanded) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var muteFor by remember { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier,
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_conversation))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
        ConnectionBanner(connection)
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (items != null && items.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.no_conversations_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.no_conversations_text), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(sections.main, key = { it.id }) { item ->
                    ConversationItem(item, onOpen, menuFor == item.id, { menuFor = if (it) item.id else null }, { muteFor = item.id }, actions)
                }
                if (sections.archived.isNotEmpty()) {
                    item(key = "archived-header") {
                        ArchivedHeader(sections.archived.size, sections.archivedUnread, showArchived) { showArchived = !showArchived }
                    }
                    if (showArchived) {
                        items(sections.archived, key = { it.id }) { item ->
                            ConversationItem(item, onOpen, menuFor == item.id, { menuFor = if (it) item.id else null }, { muteFor = item.id }, actions)
                        }
                    }
                }
            }
        }
        }
    }
    muteFor?.let { id -> MuteDialog(onPick = { actions.onMute(id, it); muteFor = null }, onDismiss = { muteFor = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    item: ConversationItemUi,
    onOpen: (String) -> Unit,
    menuOpen: Boolean,
    setMenuOpen: (Boolean) -> Unit,
    askMute: () -> Unit,
    actions: ConversationActions,
) {
    Box {
        ConversationRow(item, onClick = { onOpen(item.id) }, onLongClick = { setMenuOpen(true) })
        DropdownMenu(expanded = menuOpen, onDismissRequest = { setMenuOpen(false) }) {
            if (!item.archived) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (item.pinned) R.string.conversation_unpin else R.string.conversation_pin)) },
                    onClick = { setMenuOpen(false); if (item.pinned) actions.onUnpin(item.id) else actions.onPin(item.id) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(if (item.muted) R.string.conversation_unmute else R.string.conversation_mute)) },
                onClick = { setMenuOpen(false); if (item.muted) actions.onUnmute(item.id) else askMute() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (item.archived) R.string.conversation_unarchive else R.string.conversation_archive)) },
                onClick = { setMenuOpen(false); if (item.archived) actions.onUnarchive(item.id) else actions.onArchive(item.id) },
            )
        }
    }
    HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun ArchivedHeader(count: Int, unread: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onToggle).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.archived_section, count), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
        if (unread > 0) Text(unread.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(if (expanded) R.string.archived_collapse else R.string.archived_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun MuteDialog(onPick: (MuteDuration) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mute_title)) },
        text = {
            Column {
                Text(stringResource(R.string.mute_text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                MuteDuration.entries.forEach { d ->
                    TextButton(onClick = { onPick(d) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(
                            stringResource(
                                when (d) {
                                    MuteDuration.HOUR_1 -> R.string.mute_1_hour
                                    MuteDuration.HOURS_8 -> R.string.mute_8_hours
                                    MuteDuration.WEEK_1 -> R.string.mute_1_week
                                    MuteDuration.ALWAYS -> R.string.mute_always
                                },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(item: ConversationItemUi, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val time = rememberListTimeFormatter()
    val unreadText = if (item.unread > 99) "99+" else item.unread.toString()
    // a typing contact is more current than a draft; a draft replaces the last message
    val showsDraft = item.draft != null && !item.typing
    val unreadDescription = if (item.unread > 0) pluralStringResource(R.plurals.unread_messages_description, item.unread, item.unread) else null
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(item.avatar, size = 52.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                fontWeight = if (item.unread > 0) FontWeight.Bold else FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.previewOwn && item.previewDelivery != se.swahnchat.app.ui.model.DeliveryUi.NONE && !showsDraft) {
                    DeliveryIcon(item.previewDelivery, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(end = 4.dp))
                }
                Text(
                    previewLine(item), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = if (item.typing || showsDraft) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = if (item.typing || item.previewKind == PreviewKind.DELETED || item.previewKind == PreviewKind.UNDECRYPTABLE) FontStyle.Italic else FontStyle.Normal,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            item.lastActivity?.let {
                Text(
                    time(it), style = MaterialTheme.typography.labelSmall,
                    color = if (item.unread > 0 && !item.muted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
              if (item.muted) {
                Icon(
                    Icons.Filled.VolumeOff, contentDescription = stringResource(R.string.state_muted),
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (item.pinned) {
                Icon(
                    Icons.Filled.PushPin, contentDescription = stringResource(R.string.state_pinned),
                    modifier = Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            if (item.unread > 0) {
                Box(
                    Modifier.padding(start = 6.dp).heightIn(min = 22.dp).clip(CircleShape)
                        .background(if (item.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 7.dp).semantics { contentDescription = unreadDescription.orEmpty() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(unreadText, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            }
        }
    }
}

@Composable
private fun previewLine(item: ConversationItemUi): String {
    if (item.typing) return stringResource(R.string.typing)
    if (item.draft != null) return stringResource(R.string.draft_preview, item.draft.replace('\n', ' '))
    val body = when (item.previewKind) {
        PreviewKind.NONE -> stringResource(R.string.no_messages_yet)
        PreviewKind.TEXT -> item.previewText.orEmpty().replace('\n', ' ')
        PreviewKind.PHOTO -> stringResource(R.string.attachment_photo)
        PreviewKind.VIDEO -> stringResource(R.string.attachment_video)
        PreviewKind.VOICE -> stringResource(R.string.attachment_voice)
        PreviewKind.FILE -> item.previewText ?: stringResource(R.string.attachment_file)
        PreviewKind.DELETED -> stringResource(R.string.message_deleted)
        PreviewKind.UNDECRYPTABLE -> stringResource(R.string.message_undecryptable)
    }
    if (item.previewKind == PreviewKind.NONE || item.previewKind == PreviewKind.DELETED) return body
    return when {
        item.previewOwn -> stringResource(R.string.preview_own, body)
        item.previewSender != null -> stringResource(R.string.preview_sender, item.previewSender, body)
        else -> body
    }
}

