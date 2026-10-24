package se.familychat.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.familychat.app.R
import se.familychat.app.data.UiError
import se.familychat.app.ui.components.Avatar
import se.familychat.app.ui.components.BubbleActions
import se.familychat.app.ui.components.MessageBubble
import se.familychat.app.ui.components.PlaybackUi
import se.familychat.app.ui.components.ReplyQuote
import se.familychat.app.ui.components.dayLabel
import se.familychat.app.ui.components.formatDuration
import se.familychat.app.ui.model.AttachmentKindUi
import se.familychat.app.ui.model.ChatRow
import se.familychat.app.media.ShareFiles
import se.familychat.app.ui.model.SystemKind
import se.familychat.app.ui.model.TransferUi
import java.io.File

/** What the conversation screen can do besides sending; all supplied by the navigation graph. */
class ConversationNav(
    val onBack: () -> Unit,
    val onOpenMedia: (messageId: String, attachmentId: String) -> Unit,
    val onOpenGroupInfo: (conversationId: String) -> Unit,
    val onOpenContact: (userId: String) -> Unit,
)

@Composable
fun ConversationScreen(nav: ConversationNav, vm: ConversationViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { vm.onPause() }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { vm.attach(it) }
    val pickDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.attach(it) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) scope.launch { snackbar.showSnackbar(context.getString(R.string.error_microphone_permission)) }
    }

    val errorText = errorTexts()
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is ConversationEffect.Error -> snackbar.showSnackbar(errorText(effect.error))
                is ConversationEffect.OpenFile -> openFile(context, effect.path, effect.mime, effect.name)
                is ConversationEffect.ScrollTo -> {
                    val index = state.rows.asReversed().indexOfFirst { it.key == effect.messageId }
                    if (index >= 0) listState.animateScrollToItem(index)
                }
            }
        }
    }
    LaunchedEffect(state.removed) { if (state.removed) nav.onBack() }

    val actions = remember(vm) {
        BubbleActions(
            onReply = vm::startReply,
            onEdit = vm::startEdit,
            onDelete = { confirmDelete = it },
            onCopy = { copyToClipboard(context, it); },
            onRetry = vm::retry,
            onOpenAttachment = { messageId, a ->
                when (a.kind) {
                    AttachmentKindUi.IMAGE, AttachmentKindUi.VIDEO -> nav.onOpenMedia(messageId, a.id)
                    else -> vm.open(a)
                }
            },
            onDownload = { messageId, a -> vm.download(messageId, a) },
            onToggleVoice = { _, a -> vm.toggleVoice(a) },
            onReviewIdentity = { state.otherUserId?.let(nav.onOpenContact) },
            onOpenContact = nav.onOpenContact,
            onJumpTo = vm::jumpTo,
        )
    }

    ConversationContent(
        state = state,
        playback = playback,
        actions = actions,
        listState = listState,
        snackbarHost = { SnackbarHost(snackbar) },
        callbacks = ConversationCallbacks(
            onBack = nav.onBack,
            onHeaderClick = { if (state.isGroup) nav.onOpenGroupInfo(vm.conversationId) else state.otherUserId?.let(nav.onOpenContact) },
            onOpenSafety = { state.otherUserId?.let(nav.onOpenContact) },
            onOpenGroupInfo = { nav.onOpenGroupInfo(vm.conversationId) },
            onDraft = vm::onDraft,
            onSend = vm::send,
            onCancelReply = vm::cancelReply,
            onCancelEdit = vm::cancelEdit,
            onPickMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onPickDocument = { pickDocs.launch(arrayOf("*/*")) },
            onMicPress = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    vm.startRecording()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onMicRelease = { heldMs, slidCancel ->
                when {
                    state.recording == null -> Unit
                    slidCancel -> vm.cancelRecording()
                    heldMs < TAP_MS -> vm.lockRecording()
                    else -> vm.finishRecording(send = true)
                }
            },
            onCancelRecording = vm::cancelRecording,
            onSendRecording = { vm.finishRecording(send = true) },
            onLoadOlder = vm::loadOlder,
        ),
    )

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_message_title)) },
            text = { Text(stringResource(R.string.delete_message_text)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(id); confirmDelete = null }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private const val TAP_MS = 350L

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText(null, text))
    // Android 13+ shows its own confirmation
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Hands a decrypted file to another app (share sheet). The file is first copied under its real name into the cache, so the
 * receiving app sees "holiday.pdf" and not the attachment id; the FileProvider only serves that cache folder.
 */
internal fun openFile(context: Context, path: String, mime: String, name: String? = null) {
    try {
        val copy = ShareFiles.copyForSharing(File(path), File(context.cacheDir, "share"), name)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // a bad name, a vanished file, no app to share with: tell the user instead of crashing
        Toast.makeText(context, R.string.error_open_file, Toast.LENGTH_LONG).show()
    }
}

@Composable
fun errorTexts(): (UiError) -> String {
    val context = LocalContext.current
    return remember(context) {
        { e: UiError ->
            context.getString(
                when (e) {
                    UiError.NETWORK -> R.string.error_network
                    UiError.INVALID_CREDENTIALS -> R.string.error_invalid_credentials
                    UiError.USERNAME_TAKEN -> R.string.error_username_taken
                    UiError.DEVICE_LIMIT -> R.string.error_device_limit
                    UiError.INVALID_INVITE -> R.string.error_invalid_invite
                    UiError.RATE_LIMITED -> R.string.error_rate_limited
                    UiError.FORBIDDEN -> R.string.error_forbidden
                    UiError.TOO_LARGE -> R.string.error_too_large
                    UiError.INVALID_INPUT -> R.string.error_invalid_input
                    UiError.SESSION_ENDED -> R.string.error_session_ended
                    UiError.SERVER -> R.string.error_server
                    UiError.INSECURE_URL -> R.string.error_insecure_url
                    UiError.UNKNOWN -> R.string.error_unknown
                },
            )
        }
    }
}

class ConversationCallbacks(
    val onBack: () -> Unit = {},
    val onHeaderClick: () -> Unit = {},
    val onOpenSafety: () -> Unit = {},
    val onOpenGroupInfo: () -> Unit = {},
    val onDraft: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onCancelReply: () -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onPickMedia: () -> Unit = {},
    val onPickDocument: () -> Unit = {},
    val onMicPress: () -> Unit = {},
    val onMicRelease: (heldMs: Long, slidCancel: Boolean) -> Unit = { _, _ -> },
    val onCancelRecording: () -> Unit = {},
    val onSendRecording: () -> Unit = {},
    val onLoadOlder: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationContent(
    state: ConversationUiState,
    playback: PlaybackUi,
    actions: BubbleActions,
    callbacks: ConversationCallbacks,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    snackbarHost: @Composable () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        Modifier.heightIn(min = 48.dp).clickable(onClick = callbacks.onHeaderClick, role = Role.Button),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(state.avatar, size = 40.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            val subtitle = when {
                                state.typingNames.isNotEmpty() -> stringResource(R.string.typing_names, state.typingNames.joinToString(", "))
                                state.isGroup -> pluralStringResource(R.plurals.members_count, state.memberCount, state.memberCount)
                                else -> null
                            }
                            if (subtitle != null) {
                                Text(
                                    subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontStyle = if (state.typingNames.isNotEmpty()) FontStyle.Italic else FontStyle.Normal,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (state.isGroup) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.group_info)) },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = { menuOpen = false; callbacks.onOpenGroupInfo() },
                                )
                            } else if (state.otherUserId != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.safety_number)) },
                                    leadingIcon = { Icon(Icons.Filled.Security, null) },
                                    onClick = { menuOpen = false; callbacks.onOpenSafety() },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = { Composer(state, callbacks) },
    ) { padding ->
        MessageList(state, playback, actions, callbacks, listState, Modifier.padding(padding).fillMaxSize())
    }
}

@Composable
private fun MessageList(
    state: ConversationUiState,
    playback: PlaybackUi,
    actions: BubbleActions,
    callbacks: ConversationCallbacks,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
) {
    val reversed = remember(state.rows) { state.rows.asReversed() }
    val newestKey = reversed.firstOrNull()?.key
    val newestIsOwn = (reversed.firstOrNull() as? ChatRow.Message)?.message?.outgoing == true
    LaunchedEffect(newestKey) {
        // stay at the bottom when new messages arrive, unless the user scrolled up to read; own messages always jump down
        if (newestKey != null && (listState.firstVisibleItemIndex <= 1 || newestIsOwn)) listState.scrollToItem(0)
    }
    LaunchedEffect(listState, reversed.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (reversed.isNotEmpty() && last >= reversed.size - 5) callbacks.onLoadOlder()
        }
    }
    Box(modifier) {
        if (state.loaded && state.rows.isEmpty()) {
            Text(
                stringResource(R.string.conversation_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            items(reversed, key = { it.key }) { row ->
                when (row) {
                    is ChatRow.DateHeader -> DateSeparator(row)
                    is ChatRow.Message -> MessageBubble(row, actions, playback)
                    is ChatRow.System -> SystemNotice(row) { actions.onOpenContact(row.userId) }
                }
            }
        }
    }
}

@Composable
private fun DateSeparator(row: ChatRow.DateHeader) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            dayLabel(row.dayStart), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SystemNotice(row: ChatRow.System, onReview: () -> Unit) {
    val warning = row.kind == SystemKind.IDENTITY_CHANGED || row.kind == SystemKind.NEW_DEVICE_BLOCKED
    val text = when (row.kind) {
        SystemKind.IDENTITY_CHANGED -> stringResource(R.string.system_identity_changed, row.personName)
        SystemKind.NEW_DEVICE ->
            if (row.own) stringResource(R.string.system_new_own_device, row.deviceName.orEmpty())
            else stringResource(R.string.system_new_device, row.personName, row.deviceName.orEmpty())
        SystemKind.NEW_DEVICE_BLOCKED ->
            if (row.own) stringResource(R.string.system_new_own_device_blocked, row.deviceName.orEmpty())
            else stringResource(R.string.system_new_device_blocked, row.personName, row.deviceName.orEmpty())
        SystemKind.MEMBER_ADDED -> stringResource(R.string.system_member_added, row.personName)
        SystemKind.MEMBER_REMOVED -> stringResource(R.string.system_member_removed, row.personName)
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp))
                .background(if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (warning) Icons.Filled.Warning else Icons.Filled.Security, contentDescription = null,
                tint = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (warning) {
            TextButton(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_review_identity)) }
        }
    }
}

// --- composer ---------------------------------------------------------------------------------------------------------

@Composable
private fun Composer(state: ConversationUiState, callbacks: ConversationCallbacks) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.imePadding().navigationBarsPadding()) {
        Column {
            AnimatedVisibility(state.replyTo != null) {
                state.replyTo?.let { reply ->
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ReplyQuote(reply, onClick = {}, modifier = Modifier.weight(1f))
                        IconButton(onClick = callbacks.onCancelReply, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel_reply))
                        }
                    }
                }
            }
            AnimatedVisibility(state.editingMessageId != null) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.editing_message), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    IconButton(onClick = callbacks.onCancelEdit, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel_edit))
                    }
                }
            }
            val recording = state.recording
            if (recording != null && recording.locked) {
                LockedRecordingBar(recording, callbacks)
            } else {
                InputRow(state, callbacks)
            }
        }
    }
}

@Composable
private fun InputRow(state: ConversationUiState, callbacks: ConversationCallbacks) {
    var attachMenu by remember { mutableStateOf(false) }
    val recording = state.recording
    val hasText = state.draft.isNotBlank()
    var slidCancel by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        if (state.editingMessageId == null && recording == null) {
            Box {
                IconButton(onClick = { attachMenu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.action_attach))
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_photo_video)) },
                        leadingIcon = { Icon(Icons.Filled.Photo, null) },
                        onClick = { attachMenu = false; callbacks.onPickMedia() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_document)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                        onClick = { attachMenu = false; callbacks.onPickDocument() },
                    )
                }
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        if (recording != null) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                Text(
                    formatDuration(recording.elapsedMs), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    stringResource(if (slidCancel) R.string.release_to_cancel else R.string.slide_to_cancel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (slidCancel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        } else {
            TextField(
                value = state.draft,
                onValueChange = callbacks.onDraft,
                placeholder = { Text(stringResource(R.string.message_hint)) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        }
        Spacer(Modifier.width(4.dp))
        if (hasText && recording == null) {
            FilledIconButton(onClick = callbacks.onSend, enabled = !state.sending, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (state.editingMessageId != null) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(if (state.editingMessageId != null) R.string.action_save_edit else R.string.action_send),
                )
            }
        } else {
            MicButton(recording != null, callbacks, onSlide = { slidCancel = it })
        }
    }
}

/** Hold to record and release to send, slide left to cancel; a short tap keeps recording (see [LockedRecordingBar]). */
@Composable
private fun MicButton(recording: Boolean, callbacks: ConversationCallbacks, onSlide: (Boolean) -> Unit) {
    val cancelDistance = with(LocalDensity.current) { 96.dp.toPx() }
    val description = stringResource(R.string.action_record_voice)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = description; role = Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    callbacks.onMicPress()
                    var dx = 0f
                    var slid = false
                    var held = 0L
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.changedToUp() || !change.pressed) {
                            held = (change?.uptimeMillis ?: down.uptimeMillis) - down.uptimeMillis
                            break
                        }
                        dx += change.positionChange().x
                        val nowSlid = dx < -cancelDistance
                        if (nowSlid != slid) { slid = nowSlid; onSlide(slid) }
                        change.consume()
                    }
                    onSlide(false)
                    callbacks.onMicRelease(held, slid)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun LockedRecordingBar(recording: RecordingUi, callbacks: ConversationCallbacks) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = callbacks.onCancelRecording, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_cancel_recording), tint = MaterialTheme.colorScheme.error)
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        Text(
            formatDuration(recording.elapsedMs), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        FilledIconButton(onClick = callbacks.onSendRecording, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send_recording))
        }
    }
}
