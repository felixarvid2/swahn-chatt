package se.familychat.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import se.familychat.app.R
import se.familychat.app.data.AppPrefs
import se.familychat.app.data.ChatRepository
import se.familychat.app.platform.work.WorkScheduler
import se.familychat.app.ui.components.Avatar
import se.familychat.app.ui.components.DeliveryIcon
import se.familychat.app.ui.components.rememberListTimeFormatter
import se.familychat.app.ui.model.ConversationItemUi
import se.familychat.app.ui.model.PreviewKind
import se.familychat.core.client.FamilyChatEngine
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    @ApplicationContext context: android.content.Context,
    repo: ChatRepository,
    private val engine: FamilyChatEngine,
    private val prefs: AppPrefs,
    private val work: WorkScheduler,
) : ViewModel() {
    /** null while the first read is pending. */
    val items: StateFlow<List<ConversationItemUi>?> =
        repo.conversationItems(context.getString(R.string.unknown_user)).map<List<ConversationItemUi>, List<ConversationItemUi>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    vm: ConversationsViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
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
    ConversationsContent(items, refreshing, vm::refresh, onOpen, onNew, onSettings)
}

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
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
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
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.padding(padding).fillMaxSize()) {
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
                items(items.orEmpty(), key = { it.id }) { item ->
                    ConversationRow(item, onClick = { onOpen(item.id) })
                    HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun ConversationRow(item: ConversationItemUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val time = rememberListTimeFormatter()
    val unreadText = if (item.unread > 99) "99+" else item.unread.toString()
    val unreadDescription = if (item.unread > 0) pluralStringResource(R.plurals.unread_messages_description, item.unread, item.unread) else null
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(item.avatar, size = 52.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                fontWeight = if (item.unread > 0) FontWeight.Bold else FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.previewOwn && item.previewDelivery != se.familychat.app.ui.model.DeliveryUi.NONE) {
                    DeliveryIcon(item.previewDelivery, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(end = 4.dp))
                }
                Text(
                    previewLine(item), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = if (item.typing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = if (item.typing || item.previewKind == PreviewKind.DELETED || item.previewKind == PreviewKind.UNDECRYPTABLE) FontStyle.Italic else FontStyle.Normal,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            item.lastActivity?.let {
                Text(
                    time(it), style = MaterialTheme.typography.labelSmall,
                    color = if (item.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.unread > 0) {
                Box(
                    Modifier.padding(top = 6.dp).heightIn(min = 22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 7.dp).semantics { contentDescription = unreadDescription.orEmpty() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(unreadText, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun previewLine(item: ConversationItemUi): String {
    if (item.typing) return stringResource(R.string.typing)
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

