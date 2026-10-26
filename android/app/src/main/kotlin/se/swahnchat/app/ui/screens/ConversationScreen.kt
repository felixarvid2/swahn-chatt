package se.swahnchat.app.ui.screens

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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import se.swahnchat.app.ui.components.ConnectionBanner
import se.swahnchat.app.ui.components.LinkKind
import se.swahnchat.app.ui.components.describeLink
import se.swahnchat.app.ui.model.SafetyState
import se.swahnchat.app.ui.model.SafetyUi
import se.swahnchat.app.ui.model.isFirstOfRun
import android.net.Uri
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
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
import se.swahnchat.app.R
import se.swahnchat.app.data.UiError
import se.swahnchat.app.ui.components.Avatar
import se.swahnchat.app.ui.components.chatBackground
import se.swahnchat.app.ui.components.rememberListTimeFormatter
import se.swahnchat.app.ui.components.BubbleActions
import se.swahnchat.app.ui.components.MessageBubble
import se.swahnchat.app.ui.components.PlaybackUi
import se.swahnchat.app.ui.components.ReplyQuote
import se.swahnchat.app.ui.components.DisappearingDialog
import se.swahnchat.app.ui.components.MentionPicker
import se.swahnchat.app.ui.components.PollCreateDialog
import se.swahnchat.app.ui.components.ScheduleDialog
import se.swahnchat.app.ui.components.defaultScheduleTime
import se.swahnchat.app.ui.components.StagedStrip
import se.swahnchat.app.ui.components.VideoSendOptions
import se.swahnchat.app.ui.components.dayLabel
import se.swahnchat.app.ui.components.timerLabel
import se.swahnchat.app.ui.components.formatDuration
import se.swahnchat.app.ui.model.AttachmentKindUi
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.media.ShareFiles
import se.swahnchat.app.ui.model.SystemKind
import se.swahnchat.app.call.callDurationLabel
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.Videocam
import se.swahnchat.app.ui.model.TransferUi
import java.io.File

/** What the conversation screen can do besides sending; all supplied by the navigation graph. */
class ConversationNav(
    val onBack: () -> Unit,
    val onOpenMedia: (messageId: String, attachmentId: String) -> Unit,
    val onOpenGroupInfo: (conversationId: String) -> Unit,
    val onOpenContact: (userId: String) -> Unit,
    val onSearch: (conversationId: String) -> Unit = {},
    /** Places a call in this (direct) conversation: opens the call screen. */
    val onCall: (conversationId: String, video: Boolean) -> Unit = { _, _ -> },
    val onOpenMessageInfo: (messageId: String) -> Unit = {},
    val onForward: (messageId: String) -> Unit = {},
    /** The starred messages of this conversation; the screen it opens brings a chosen message back as a jump. */
    val onOpenStarred: (conversationId: String) -> Unit = {},
    /** The list of messages scheduled in this conversation. */
    val onOpenScheduled: (conversationId: String) -> Unit = {},
    /** Opens another conversation (the direct chat of a tapped mention). */
    val onOpenConversation: (conversationId: String) -> Unit = {},
    /** "Media och filer": the gallery of this conversation. */
    val onOpenGallery: (conversationId: String) -> Unit = {},
)

@Composable
fun ConversationScreen(
    nav: ConversationNav,
    /** A message to show on opening (from the search); [onJumpHandled] is called when the list has been asked for it. */
    jumpTo: String? = null,
    onJumpHandled: () -> Unit = {},
    vm: ConversationViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val items = vm.rows.collectAsLazyPagingItems()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmDeleteForMe by remember { mutableStateOf<String?>(null) }
    var confirmLink by remember { mutableStateOf<String?>(null) }
    var confirmClosePoll by remember { mutableStateOf<String?>(null) }
    var confirmCancelScheduled by remember { mutableStateOf<String?>(null) }
    var rescheduleTarget by remember { mutableStateOf<String?>(null) }
    // a quoted message that was not loaded: the list restarts around it, then we scroll to it
    var pendingScroll by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { vm.onPause() }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { vm.attach(it) }
    val pickDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.attach(it) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) scope.launch { snackbar.showSnackbar(resources.getString(R.string.error_microphone_permission)) }
    }

    val attach = rememberAttachCallbacks(vm, state.locationShare)

    val errorText = errorTexts()
    LaunchedEffect(vm) {
        suspend fun scrollTo(messageId: String) {
            val index = items.indexOfKey(messageId)
            if (index >= 0) {
                listState.animateScrollToItem(index)
            } else {
                pendingScroll = messageId
                vm.loadAround(messageId)
            }
        }
        vm.effects.collect { effect ->
            when (effect) {
                is ConversationEffect.Error -> snackbar.showSnackbar(errorText(effect.error))
                is ConversationEffect.Notice -> snackbar.showSnackbar(resources.getString(effect.message))
                is ConversationEffect.OpenFile -> openFile(context, effect.path, effect.mime, effect.name)
                is ConversationEffect.ScrollTo -> scrollTo(effect.messageId)
                is ConversationEffect.OpenConversation -> nav.onOpenConversation(effect.conversationId)
                is ConversationEffect.OpenContact -> nav.onOpenContact(effect.userId)
            }
        }
    }
    // from the search: restart the list around the message (it is rarely among the newest), then the effect below scrolls
    LaunchedEffect(jumpTo) {
        if (jumpTo != null) {
            pendingScroll = jumpTo
            vm.loadAround(jumpTo)
            onJumpHandled()
        }
    }
    LaunchedEffect(pendingScroll, items.itemCount) {
        val id = pendingScroll ?: return@LaunchedEffect
        val index = items.indexOfKey(id)
        if (index >= 0) {
            listState.scrollToItem(index)
            pendingScroll = null
        } else {
            delay(PENDING_SCROLL_TIMEOUT_MS)
            pendingScroll = null
        }
    }
    LaunchedEffect(state.removed) { if (state.removed) nav.onBack() }

    val actions = remember(vm) {
        BubbleActions(
            onReply = vm::startReply,
            onEdit = vm::startEdit,
            onDelete = { confirmDelete = it },
            onDeleteForMe = { confirmDeleteForMe = it },
            onInfo = nav.onOpenMessageInfo,
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
            onCycleVoiceSpeed = vm::cycleVoiceSpeed,
            onOpenLink = { confirmLink = it },
            onReact = vm::react,
            onForward = nav.onForward,
            onStar = vm::star,
            onVote = vm::vote,
            onClosePoll = { confirmClosePoll = it },
            onOpenMention = vm::openMention,
            onReschedule = { rescheduleTarget = it },
            onSendScheduledNow = vm::sendScheduledNow,
            onCancelScheduled = { confirmCancelScheduled = it },
            onOpenLocation = { lat, lon, label -> openInMapApp(context, lat, lon, label) },
        )
    }

    ConversationContent(
        state = state,
        items = items,
        playback = playback,
        actions = actions,
        listState = listState,
        snackbarHost = { SnackbarHost(snackbar) },
        callbacks = ConversationCallbacks(
            onBack = nav.onBack,
            onHeaderClick = { if (state.isGroup) nav.onOpenGroupInfo(vm.conversationId) else state.otherUserId?.let(nav.onOpenContact) },
            onOpenSafety = {
                // a warning leads to the person it is about; otherwise a direct chat opens the other person, a group its members
                val review = state.safety.reviewUserId
                when {
                    review != null -> nav.onOpenContact(review)
                    state.isGroup -> nav.onOpenGroupInfo(vm.conversationId)
                    else -> state.otherUserId?.let(nav.onOpenContact)
                }
            },
            onOpenGroupInfo = { nav.onOpenGroupInfo(vm.conversationId) },
            onSearch = { nav.onSearch(vm.conversationId) },
            onCall = { video -> nav.onCall(vm.conversationId, video) },
            onDraft = vm::onDraft,
            onSend = { vm.send() },
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
            onAtBottom = vm::onAtBottom,
            onJumpToLatest = vm::jumpToLatest,
            onMessageShown = vm::onMessageShown,
            onMessageDisplayed = vm::onMessageDisplayed,
            onUnstage = vm::unstage,
            onSetTimer = vm::setTimer,
            onSendAt = { sendAt -> vm.send(sendAt) },
            onPickMention = vm::pickMention,
            onCreatePoll = vm::sendPoll,
            onOpenStarred = { nav.onOpenStarred(vm.conversationId) },
            onOpenScheduled = { nav.onOpenScheduled(vm.conversationId) },
            onOpenGallery = { nav.onOpenGallery(vm.conversationId) },
            onOpenNotificationSettings = {
                scope.launch {
                    val intent = runCatching { vm.notificationSettingsIntent() }.getOrNull()
                    if (intent == null || runCatching { context.startActivity(intent) }.isFailure) {
                        snackbar.showSnackbar(resources.getString(R.string.notification_settings_unavailable))
                    }
                }
            },
            onTakePhoto = attach.onTakePhoto,
            onRecordVideo = attach.onRecordVideo,
            onSharePosition = attach.onSharePosition,
            onCancelSending = vm::cancelSending,
            onSendOriginalVideo = vm::setSendOriginalVideo,
        ),
    )

    confirmClosePoll?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmClosePoll = null },
            title = { Text(stringResource(R.string.poll_close_title)) },
            text = { Text(stringResource(R.string.poll_close_text)) },
            confirmButton = { TextButton(onClick = { vm.closePoll(id); confirmClosePoll = null }) { Text(stringResource(R.string.poll_close_action)) } },
            dismissButton = { TextButton(onClick = { confirmClosePoll = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    confirmCancelScheduled?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmCancelScheduled = null },
            title = { Text(stringResource(R.string.scheduled_delete_title)) },
            text = { Text(stringResource(R.string.scheduled_delete_text)) },
            confirmButton = {
                TextButton(onClick = { vm.cancelScheduled(id); confirmCancelScheduled = null }) { Text(stringResource(R.string.scheduled_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmCancelScheduled = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    rescheduleTarget?.let { id ->
        val current = items.itemSnapshotList.firstNotNullOfOrNull { (it as? ChatRow.Message)?.message?.takeIf { m -> m.id == id }?.scheduledAt }
        ScheduleDialog(
            initial = current ?: defaultScheduleTime(System.currentTimeMillis()), now = System::currentTimeMillis,
            onConfirm = { vm.reschedule(id, it); rescheduleTarget = null },
            onDismiss = { rescheduleTarget = null },
            confirmLabel = stringResource(R.string.schedule_save),
        )
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_message_title)) },
            text = { Text(stringResource(R.string.delete_message_text)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(id); confirmDelete = null }) { Text(stringResource(R.string.action_delete_for_everyone)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    confirmDeleteForMe?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteForMe = null },
            title = { Text(stringResource(R.string.delete_for_me_title)) },
            text = { Text(stringResource(R.string.delete_for_me_text)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteForMe(id); confirmDeleteForMe = null }) { Text(stringResource(R.string.action_delete_for_me)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteForMe = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    confirmLink?.let { url ->
        LinkConfirmDialog(url, onDismiss = { confirmLink = null }, onConfirm = { confirmLink = null; openLink(context, url) })
    }
}

private const val TAP_MS = 350L
private const val PENDING_SCROLL_TIMEOUT_MS = 5_000L

/**
 * Asks before a link is followed: the whole address (nothing is shortened or fetched), the part that decides where it
 * leads, and a warning if the address hides something. Nothing is opened before the user confirms.
 */
@Composable
private fun LinkConfirmDialog(url: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val info = remember(url) { describeLink(url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_confirm_title)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.link_confirm_text), style = MaterialTheme.typography.bodyMedium)
                if (info != null) {
                    Text(
                        stringResource(
                            when (info.kind) {
                                LinkKind.WEB -> R.string.link_target_web
                                LinkKind.EMAIL -> R.string.link_target_email
                                LinkKind.PHONE -> R.string.link_target_phone
                            },
                            info.target,
                        ),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp),
                    )
                }
                SelectionContainer {
                    Text(url, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
                }
                if (info?.suspicious == true) {
                    Text(
                        stringResource(R.string.link_suspicious), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = info != null) { Text(stringResource(R.string.link_confirm_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Hands a confirmed link to the system: web and mail addresses open, a phone number only opens the dialer. */
private fun openLink(context: Context, url: String) {
    val info = describeLink(url) ?: return
    val intent = Intent(if (info.kind == LinkKind.PHONE) Intent.ACTION_DIAL else Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // a web address must only open an app that declares itself a browser; many mail apps do not declare BROWSABLE
    if (info.kind == LinkKind.WEB) intent.addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, R.string.error_open_link, Toast.LENGTH_LONG).show()
    }
}

private fun LazyPagingItems<ChatRow>.indexOfKey(key: String): Int = itemSnapshotList.indexOfFirst { it?.key == key }

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
    val resources = LocalResources.current
    return remember(context) {
        { e: UiError ->
            resources.getString(
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
                    UiError.INVALID_RESET_CODE -> R.string.error_invalid_reset_code
                    UiError.LAST_ADMIN -> R.string.error_last_admin
                    UiError.PUSH_NOT_CONFIGURED -> R.string.error_push_not_configured
                    UiError.WRONG_PASSWORD -> R.string.error_wrong_password
                    UiError.ACCOUNT_SUSPENDED -> R.string.error_account_suspended
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
    val onSearch: () -> Unit = {},
    /** Place a voice (false) or video (true) call; offered in direct conversations only. */
    val onCall: (video: Boolean) -> Unit = {},
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
    /** The list now shows the newest message at its bottom (true) or not. Only reported once the list is positioned. */
    val onAtBottom: (Boolean) -> Unit = {},
    /** The list shows an older part (after a jump to a quoted message): restart it at the newest message. */
    val onJumpToLatest: () -> Unit = {},
    /** A message with attachments came into view. */
    val onMessageShown: (messageId: String) -> Unit = {},
    /** A message with a timer is on screen (its clock starts). */
    val onMessageDisplayed: (messageId: String) -> Unit = {},
    /** Takes a chosen file out of the preview strip. */
    val onUnstage: (id: String) -> Unit = {},
    val onSetTimer: (seconds: Long) -> Unit = {},
    /** Sends the draft; with a time (epoch ms) it is scheduled instead. */
    val onSendAt: (sendAt: Long?) -> Unit = {},
    /** A person of the mention picker was chosen. */
    val onPickMention: (userId: String) -> Unit = {},
    val onCreatePoll: (question: String, options: List<String>, multiple: Boolean) -> Unit = { _, _, _ -> },
    val onOpenStarred: () -> Unit = {},
    val onOpenScheduled: () -> Unit = {},
    val onOpenGallery: () -> Unit = {},
    /** "Avisering": the system's notification settings of this conversation's own channel. */
    val onOpenNotificationSettings: () -> Unit = {},
    /** The attach menu's "Kamera", "Spela in video" and "Position". */
    val onTakePhoto: () -> Unit = {},
    val onRecordVideo: () -> Unit = {},
    val onSharePosition: () -> Unit = {},
    /** Stops the compression of the staged videos (nothing is sent). */
    val onCancelSending: () -> Unit = {},
    /** "Skicka i originalkvalitet" on or off. */
    val onSendOriginalVideo: (Boolean) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationContent(
    state: ConversationUiState,
    items: LazyPagingItems<ChatRow>,
    playback: PlaybackUi,
    actions: BubbleActions,
    callbacks: ConversationCallbacks,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    snackbarHost: @Composable () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }
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
                            val members = if (state.isGroup) pluralStringResource(R.plurals.members_count, state.memberCount, state.memberCount) else null
                            val typing = state.typingNames.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.typing_names, it.joinToString(", ")) }
                            val timerText = if (state.timerSeconds > 0) timerLabel(state.timerSeconds) else null
                            val timerSpoken = if (timerText != null) stringResource(R.string.disappearing_summary, timerText) else null
                            if (typing != null) {
                                Text(
                                    typing, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (state.presence != null && !state.isGroup) {
                                val seen = rememberListTimeFormatter()
                                Text(
                                    if (state.presence.online) stringResource(R.string.presence_online)
                                    else state.presence.lastSeenAt?.let { stringResource(R.string.presence_last_seen, seen(it)) }.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (state.presence.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (members != null || timerText != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = if (timerSpoken == null) {
                                        Modifier
                                    } else {
                                        // one spoken line: "4 medlemmar, Försvinnande meddelanden: 1 dag"
                                        val spoken = listOfNotNull(members, timerSpoken).joinToString(", ")
                                        Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }
                                    },
                                ) {
                                    if (members != null) {
                                        Text(
                                            members, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (timerText != null) {
                                        // a clock and the short label: the long form is in the conversation menu and the notices
                                        Icon(
                                            Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = if (members != null) 8.dp else 0.dp, end = 2.dp).size(14.dp),
                                        )
                                        Text(
                                            timerText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
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
                    // A direct conversation has the two call buttons; so that the person's name keeps its room, search moves into the menu there.
                    val direct = !state.isGroup && state.otherUserId != null
                    if (direct) CallButtons(state.callActive, callbacks.onCall)
                    SafetyBadge(state.safety, callbacks.onOpenSafety)
                    if (!direct) {
                        IconButton(onClick = callbacks.onSearch, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_in_conversation))
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (direct) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.search_in_conversation)) },
                                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                                    onClick = { menuOpen = false; callbacks.onSearch() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_media_files)) },
                                leadingIcon = { Icon(Icons.Filled.PermMedia, null) },
                                onClick = { menuOpen = false; callbacks.onOpenGallery() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_starred)) },
                                leadingIcon = { Icon(Icons.Filled.Star, null) },
                                onClick = { menuOpen = false; callbacks.onOpenStarred() },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.scheduledCount > 0) "${stringResource(R.string.scheduled_menu)} (${state.scheduledCount})"
                                        else stringResource(R.string.scheduled_menu),
                                    )
                                },
                                leadingIcon = { Icon(Icons.Filled.Schedule, null) },
                                onClick = { menuOpen = false; callbacks.onOpenScheduled() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_notification_settings)) },
                                leadingIcon = { Icon(Icons.Filled.Notifications, null) },
                                onClick = { menuOpen = false; callbacks.onOpenNotificationSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.disappearing_title)) },
                                leadingIcon = { Icon(Icons.Filled.Timer, null) },
                                onClick = { menuOpen = false; timerOpen = true },
                            )
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
        if (timerOpen) {
            DisappearingDialog(
                current = state.timerSeconds, canChange = state.canSetTimer,
                onSelect = { callbacks.onSetTimer(it); timerOpen = false }, onDismiss = { timerOpen = false },
            )
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            ConnectionBanner(state.connection)
            MessageList(state, items, playback, actions, callbacks, listState, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * The two call buttons (voice, video) of a direct conversation. Disabled with an explanation (spoken, and shown as a long-press
 * hint) while a call is in progress: there is one call at a time.
 */
@Composable
private fun CallButtons(callActive: Boolean, onCall: (video: Boolean) -> Unit) {
    val busy = stringResource(R.string.call_busy_hint)
    val voice = stringResource(R.string.call_action_voice)
    val video = stringResource(R.string.call_action_video)
    IconButton(
        onClick = { onCall(false) }, enabled = !callActive,
        modifier = Modifier.size(48.dp).semantics { if (callActive) stateDescription = busy },
    ) {
        Icon(Icons.Filled.Call, contentDescription = voice)
    }
    IconButton(
        onClick = { onCall(true) }, enabled = !callActive,
        modifier = Modifier.size(48.dp).semantics { if (callActive) stateDescription = busy },
    ) {
        Icon(Icons.Filled.Videocam, contentDescription = video)
    }
}

/**
 * The shield (every device of the other members verified) or the warning (an identity change waits for a decision) in the
 * top bar; a tap opens the safety numbers. Nothing while neither applies.
 */
@Composable
private fun SafetyBadge(safety: SafetyUi, onClick: () -> Unit) {
    if (safety.state == SafetyState.NONE) return
    val warning = safety.state == SafetyState.WARNING
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            if (warning) Icons.Filled.Warning else Icons.Filled.VerifiedUser,
            contentDescription = stringResource(if (warning) R.string.safety_warning_description else R.string.safety_verified_description),
            tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

/** How far from the bottom (in dp) the list may be scrolled and still count as "at the bottom". */
private val AT_BOTTOM_SLACK = 32.dp

@Composable
private fun MessageList(
    state: ConversationUiState,
    items: LazyPagingItems<ChatRow>,
    playback: PlaybackUi,
    actions: BubbleActions,
    callbacks: ConversationCallbacks,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val slack = with(density) { AT_BOTTOM_SLACK.roundToPx() }
    // The newest message is at the bottom only if the list starts at it (nothing newer to load) and is not scrolled up.
    val atBottom by remember(listState, items) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < slack &&
                items.loadState.prepend.endOfPaginationReached
        }
    }
    // The list is invisible until it is positioned: at the first unread message, else at the bottom.
    var ready by rememberSaveable { mutableStateOf(false) }
    var viewport by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.loaded, state.openUnread, items.itemCount, items.loadState.refresh, viewport) {
        if (ready || !state.loaded || state.openUnread < 0 || viewport == 0) return@LaunchedEffect
        // not before the first page is there: an empty list would look "at the bottom" with older messages still to come
        if (items.loadState.refresh !is LoadState.NotLoading) return@LaunchedEffect
        if (state.openUnread == 0) {
            ready = true
            return@LaunchedEffect
        }
        val divider = items.indexOfKey(ChatRow.UnreadDivider.key)
        when {
            divider >= 0 -> {
                // put the divider at the top of the screen; the list stops at the bottom when fewer messages follow it
                listState.scrollToItem(divider, -(viewport - with(density) { UNREAD_DIVIDER_HEIGHT.roundToPx() }).coerceAtLeast(0))
                ready = true
            }
            items.loadState.refresh is LoadState.NotLoading && items.itemCount > 0 -> ready = true // not loaded: stay at the bottom
        }
    }
    LaunchedEffect(ready, listState, items) {
        if (!ready) return@LaunchedEffect
        snapshotFlow { atBottom }.distinctUntilChanged().collect { callbacks.onAtBottom(it) }
    }

    // New messages: stay at the bottom unless the user scrolled up to read; own messages always jump down.
    val newestLoaded = if (items.itemCount > 0) items.peek(0)?.key else null
    var pendingBottom by remember { mutableStateOf(false) }
    LaunchedEffect(newestLoaded, state.latestId, ready, pendingBottom) {
        val latest = state.latestId ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (newestLoaded == latest) {
            if (pendingBottom || state.latestOutgoing || listState.firstVisibleItemIndex <= 2) listState.scrollToItem(0)
            pendingBottom = false
        } else if (state.latestOutgoing && !items.loadState.prepend.endOfPaginationReached) {
            // the list shows an older part: restart it at the newest message, which then scrolls into view
            pendingBottom = true
            callbacks.onJumpToLatest()
        }
    }
    val scope = rememberCoroutineScope()

    Box(modifier.chatBackground().onSizeChanged { viewport = it.height }) {
        if (state.loaded && state.latestId == null) {
            Text(
                stringResource(R.string.conversation_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        LazyColumn(
            state = listState, reverseLayout = true,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (ready) 1f else 0f },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(count = items.itemCount, key = items.itemKey { it.key }, contentType = items.itemContentType { it::class }) { index ->
                when (val row = items[index]) {
                    null -> Unit
                    is ChatRow.DateHeader -> DateSeparator(row)
                    is ChatRow.UnreadDivider -> UnreadDivider()
                    is ChatRow.Message -> {
                        // the row above (older) decides whether this one starts a run; a row not loaded yet counts as a start
                        val older = if (index + 1 < items.itemCount) items.peek(index + 1) else null
                        val first = isFirstOfRun(row, older)
                        MessageBubble(if (first == row.firstOfRun) row else row.copy(firstOfRun = first), actions, playback)
                        val m = row.message
                        if (m.expiresIn != null) {
                            LaunchedEffect(m.id, state.resumed) { if (state.resumed) callbacks.onMessageDisplayed(m.id) }
                        }
                        val needsFetch = m.attachments.any { it.transfer is TransferUi.Remote || (it.transfer is TransferUi.Downloading && it.transfer.progress == null) }
                        LaunchedEffect(m.id, needsFetch) { if (needsFetch) callbacks.onMessageShown(m.id) }
                    }
                    is ChatRow.System -> SystemNotice(row) { actions.onOpenContact(row.userId) }
                }
            }
        }
        // jump to the newest message, with the number of messages that arrived since the user scrolled up
        AnimatedVisibility(
            visible = ready && !atBottom, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            val description = if (state.newMessages > 0) {
                pluralStringResource(R.plurals.scroll_to_latest_new, state.newMessages, state.newMessages)
            } else {
                stringResource(R.string.scroll_to_latest)
            }
            BadgedBox(
                badge = {
                    if (state.newMessages > 0) {
                        Badge { Text(if (state.newMessages > 99) "99+" else state.newMessages.toString()) }
                    }
                },
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (items.loadState.prepend.endOfPaginationReached) {
                                listState.animateScrollToItem(0)
                            } else {
                                pendingBottom = true
                                callbacks.onJumpToLatest()
                            }
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = description },
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
            }
        }
    }
}

private val UNREAD_DIVIDER_HEIGHT = 48.dp

/** "Olästa meddelanden": a line with the label in the middle, above the first message that was unread on opening. */
@Composable
private fun UnreadDivider() {
    val label = stringResource(R.string.unread_divider)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    }
}

@Composable
private fun DateSeparator(row: ChatRow.DateHeader) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) { heading() }, contentAlignment = Alignment.Center) {
        Text(
            dayLabel(row.dayStart), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

private val CALL_KINDS = setOf(
    SystemKind.CALL_COMPLETED, SystemKind.CALL_MISSED, SystemKind.CALL_NO_ANSWER, SystemKind.CALL_BUSY, SystemKind.CALL_REJECTED,
)

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
        SystemKind.TIMER_CHANGED -> when {
            row.value == 0L && row.own -> stringResource(R.string.system_timer_off_you)
            row.value == 0L -> stringResource(R.string.system_timer_off, row.personName)
            row.own -> stringResource(R.string.system_timer_set_you, timerLabel(row.value))
            else -> stringResource(R.string.system_timer_set, row.personName, timerLabel(row.value))
        }
        SystemKind.UPDATE_REQUIRED -> stringResource(R.string.system_update_required)
        SystemKind.CALL_COMPLETED -> stringResource(
            if (row.video) R.string.system_call_video_completed else R.string.system_call_completed, callDurationLabel(row.value),
        )
        SystemKind.CALL_MISSED -> stringResource(if (row.video) R.string.system_call_video_missed else R.string.system_call_missed)
        SystemKind.CALL_NO_ANSWER -> stringResource(R.string.system_call_no_answer)
        SystemKind.CALL_BUSY -> stringResource(R.string.system_call_busy)
        SystemKind.CALL_REJECTED -> stringResource(R.string.system_call_rejected)
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
                when {
                    warning -> Icons.Filled.Warning
                    row.kind == SystemKind.TIMER_CHANGED -> Icons.Filled.Timer
                    row.kind == SystemKind.UPDATE_REQUIRED -> Icons.Filled.Info
                    row.kind == SystemKind.CALL_MISSED -> Icons.Filled.CallMissed
                    row.video && row.kind in CALL_KINDS -> Icons.Filled.Videocam
                    row.kind in CALL_KINDS -> Icons.Filled.Call
                    else -> Icons.Filled.Security
                },
                contentDescription = null,
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
            StagedStrip(state.staged, callbacks.onUnstage)
            VideoSendOptions(
                hasVideo = state.staged.any { it.kind == AttachmentKindUi.VIDEO }, original = state.sendOriginalVideo,
                compression = state.compression, onOriginal = callbacks.onSendOriginalVideo, onCancel = callbacks.onCancelSending,
            )
            MentionPicker(state.mentionSuggestions, callbacks.onPickMention)
            val recording = state.recording
            if (recording != null && recording.locked) {
                LockedRecordingBar(recording, callbacks)
            } else {
                InputRow(state, callbacks)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InputRow(state: ConversationUiState, callbacks: ConversationCallbacks) {
    var attachMenu by remember { mutableStateOf(false) }
    var pollOpen by remember { mutableStateOf(false) }
    var scheduleOpen by remember { mutableStateOf(false) }
    val recording = state.recording
    val hasText = state.draft.isNotBlank() || state.staged.isNotEmpty()
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
                        text = { Text(stringResource(R.string.attach_camera)) },
                        leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                        onClick = { attachMenu = false; callbacks.onTakePhoto() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_record_video)) },
                        leadingIcon = { Icon(Icons.Filled.Videocam, null) },
                        onClick = { attachMenu = false; callbacks.onRecordVideo() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_document)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                        onClick = { attachMenu = false; callbacks.onPickDocument() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_location)) },
                        leadingIcon = { Icon(Icons.Filled.LocationOn, null) },
                        onClick = { attachMenu = false; callbacks.onSharePosition() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_poll)) },
                        leadingIcon = { Icon(Icons.Filled.Poll, null) },
                        onClick = { attachMenu = false; pollOpen = true },
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
                placeholder = { Text(stringResource(if (state.staged.isNotEmpty()) R.string.caption_hint else R.string.message_hint)) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        }
        Spacer(Modifier.width(4.dp))
        if (hasText && recording == null) {
            val editing = state.editingMessageId != null
            val scheduleLabel = stringResource(R.string.action_schedule)
            // a tap sends; a long press offers to send later (not while a sent message is edited)
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (state.sending) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primary)
                    .combinedClickable(
                        enabled = !state.sending, role = Role.Button,
                        onClick = callbacks.onSend,
                        onLongClick = if (editing) null else ({ scheduleOpen = true }),
                        onLongClickLabel = if (editing) null else scheduleLabel,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (editing) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(if (editing) R.string.action_save_edit else R.string.action_send),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        } else {
            MicButton(recording != null, callbacks, onSlide = { slidCancel = it })
        }
    }
    if (pollOpen) {
        PollCreateDialog(onCreate = { q, o, m -> pollOpen = false; callbacks.onCreatePoll(q, o, m) }, onDismiss = { pollOpen = false })
    }
    if (scheduleOpen) {
        ScheduleDialog(
            initial = defaultScheduleTime(System.currentTimeMillis()), now = System::currentTimeMillis,
            onConfirm = { scheduleOpen = false; callbacks.onSendAt(it) }, onDismiss = { scheduleOpen = false },
        )
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
