package se.swahnchat.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import se.swahnchat.app.R
import se.swahnchat.app.ui.model.AttachmentKindUi
import se.swahnchat.app.ui.model.AttachmentUi
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.ui.model.DeliveryUi
import se.swahnchat.app.ui.model.MessageUi
import se.swahnchat.app.ui.model.ReplyUi
import se.swahnchat.app.ui.model.TransferUi
import se.swahnchat.app.ui.model.UndecryptableUi
import se.swahnchat.app.ui.theme.LocalStatusColors
import java.io.File

/** Playback state of the voice message that is currently loaded. */
data class PlaybackUi(
    val attachmentId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 1, 1.5 or 2; the same for every voice message and remembered (see `AppPrefs.voiceSpeed`). */
    val speed: Float = 1f,
)

/** What a bubble can ask for. All of them are optional no-ops in previews and screenshots. */
class BubbleActions(
    val onReply: (String) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    /** "Delete for everyone" (the screen asks first). */
    val onDelete: (String) -> Unit = {},
    /** "Delete for me": removes the message from this phone only (the screen asks first). */
    val onDeleteForMe: (String) -> Unit = {},
    /** Opens the message info (delivered and read, per member). */
    val onInfo: (String) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onRetry: (String) -> Unit = {},
    val onOpenAttachment: (messageId: String, attachment: AttachmentUi) -> Unit = { _, _ -> },
    val onDownload: (messageId: String, attachment: AttachmentUi) -> Unit = { _, _ -> },
    val onToggleVoice: (messageId: String, attachment: AttachmentUi) -> Unit = { _, _ -> },
    val onReviewIdentity: () -> Unit = {},
    /** Opens the safety screen of a user (also the own account), from a system notice. */
    val onOpenContact: (userId: String) -> Unit = {},
    val onJumpTo: (String) -> Unit = {},
    /** Next playback speed (1x, 1.5x, 2x) for voice messages. */
    val onCycleVoiceSpeed: () -> Unit = {},
    /** A link in a message text was tapped; the screen confirms the address before anything is opened. */
    val onOpenLink: (String) -> Unit = {},
    /** React with an emoji, or take your own reaction back when it is the same one. */
    val onReact: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    /** Opens the conversation picker to forward the message. */
    val onForward: (String) -> Unit = {},
)

/** Decodes a thumbnail from the message. It is untrusted input: refuse anything that is not a small image. */
private fun decodeThumbnailSafe(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024 && bounds.outMimeType == "image/jpeg") {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } else {
        null
    }
} catch (e: Exception) {
    null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    row: ChatRow.Message,
    actions: BubbleActions,
    playback: PlaybackUi,
    modifier: Modifier = Modifier,
) {
    val m = row.message
    val clock = rememberClockFormatter()
    var menuOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var reactorsOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val container = if (m.outgoing) scheme.primaryContainer else scheme.surfaceContainerHigh
    val content = if (m.outgoing) scheme.onPrimaryContainer else scheme.onSurface
    val shape = if (m.outgoing) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
    val topPadding = if (row.firstOfRun) 8.dp else 2.dp
    val time = clock(m.time)
    val description = bubbleDescription(m, time)
    val links = remember(m.text, m.deleted) { if (m.deleted || m.text.isNullOrBlank()) emptyList() else findLinks(m.text) }
    val openLinkLabels = links.map { l -> stringResource(R.string.link_open_action, describeLink(l.url)?.target ?: l.url) }

    val canReply = !m.deleted && m.undecryptable == null
    val replyLabel = stringResource(R.string.action_reply)
    val infoLabel = stringResource(R.string.action_message_info)
    SwipeToReply(enabled = canReply, onReply = { actions.onReply(m.id) }, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = topPadding),
            horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!m.outgoing && m.senderAvatar != null) {
                Box(Modifier.width(32.dp).padding(end = 4.dp), contentAlignment = Alignment.BottomCenter) {
                    if (row.firstOfRun) Avatar(m.senderAvatar, size = 28.dp)
                }
            }
            Column(Modifier.widthIn(max = 320.dp), horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
                Box {
                    Surface(
                        shape = shape,
                        color = container,
                        contentColor = content,
                        modifier = Modifier
                            .clip(shape)
                            .combinedClickable(
                                onClick = { if (m.failed) actions.onRetry(m.id) },
                                onLongClick = { menuOpen = true },
                                onLongClickLabel = stringResource(R.string.message_actions),
                            )
                            // One spoken line per bubble (who, what, when, status); the pieces it names are hidden below.
                            // Attachments, the quoted reply and buttons keep their own nodes. Links of the (hidden) text
                            // are offered as actions instead.
                            .semantics(mergeDescendants = true) {
                                contentDescription = description
                                // the swipe has no gesture for TalkBack: the same actions are offered here (and in the long-press menu)
                                customActions = buildList {
                                    if (canReply) add(CustomAccessibilityAction(replyLabel) { actions.onReply(m.id); true })
                                    if (m.canShowInfo) add(CustomAccessibilityAction(infoLabel) { actions.onInfo(m.id); true })
                                    links.forEachIndexed { i, l -> add(CustomAccessibilityAction(openLinkLabels[i]) { actions.onOpenLink(l.url); true }) }
                                }
                            },
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).width(IntrinsicSize.Max).widthIn(min = 72.dp)) {
                            if (m.senderName != null && row.firstOfRun) {
                                Text(
                                    m.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                    color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clearAndSetSemantics {},
                                )
                            }
                            when {
                                m.deleted -> DeletedPlaceholder(content)
                                m.undecryptable != null -> UndecryptablePlaceholder(m.undecryptable, actions.onReviewIdentity)
                                else -> BubbleBody(m, actions, playback, content)
                            }
                            BubbleFooter(m, time, content)
                            if (m.failed) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {}) {
                                    Icon(
                                        Icons.Filled.Refresh, contentDescription = null, tint = LocalStatusColors.current.failed,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        stringResource(R.string.message_failed_retry), style = MaterialTheme.typography.labelMedium,
                                        color = LocalStatusColors.current.failed, modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (m.canReact) {
                            QuickReactionRow(
                                selected = m.myReaction,
                                onPick = { menuOpen = false; actions.onReact(m.id, it) },
                                onMore = { menuOpen = false; pickerOpen = true },
                            )
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        }
                        if (canReply) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_reply)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
                                onClick = { menuOpen = false; actions.onReply(m.id) },
                            )
                        }
                        if (m.canForward) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_forward)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Forward, null) },
                                onClick = { menuOpen = false; actions.onForward(m.id) },
                            )
                        }
                        if (m.canEdit) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                onClick = { menuOpen = false; actions.onEdit(m.id) },
                            )
                        }
                        if (!m.text.isNullOrBlank() && !m.deleted) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_copy)) },
                                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                onClick = { menuOpen = false; actions.onCopy(m.text) },
                            )
                        }
                        if (m.failed) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_retry)) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { menuOpen = false; actions.onRetry(m.id) },
                            )
                        }
                        if (m.canShowInfo) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_message_info)) },
                                leadingIcon = { Icon(Icons.Filled.Info, null) },
                                onClick = { menuOpen = false; actions.onInfo(m.id) },
                            )
                        }
                        if (m.canDeleteForMe) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete_for_me)) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = { menuOpen = false; actions.onDeleteForMe(m.id) },
                            )
                        }
                        if (m.canDelete) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete_for_everyone)) },
                                leadingIcon = { Icon(Icons.Filled.DeleteForever, null) },
                                onClick = { menuOpen = false; actions.onDelete(m.id) },
                            )
                        }
                    }
                }
                // reactions sit under the bubble, tucked against its corner
                ReactionsRow(m.reactions, onClick = { reactorsOpen = true }, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
    if (pickerOpen) {
        ReactionPickerDialog(m.myReaction, onPick = { pickerOpen = false; actions.onReact(m.id, it) }, onDismiss = { pickerOpen = false })
    }
    if (reactorsOpen && m.reactions.isNotEmpty()) {
        ReactorsDialog(m.reactions, onRemoveMine = { reactorsOpen = false; actions.onReact(m.id, it) }, onDismiss = { reactorsOpen = false })
    }
}

@Composable
private fun BubbleBody(m: MessageUi, actions: BubbleActions, playback: PlaybackUi, content: Color) {
    if (m.forwarded || m.imported) {
        // "Importerad" is always shown, also on a forwarded message: a file can set `forwarded` and must not hide where a message came from
        val label = listOfNotNull(
            if (m.forwarded) stringResource(R.string.message_forwarded) else null,
            if (m.imported) stringResource(R.string.message_imported) else null,
        ).joinToString(" · ")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp).clearAndSetSemantics {}) {
            Icon(
                if (m.imported) Icons.Filled.History else Icons.AutoMirrored.Filled.Forward, contentDescription = null,
                tint = content.copy(alpha = 0.7f), modifier = Modifier.size(14.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, color = content.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
    m.reply?.let { ReplyQuote(it, onClick = { actions.onJumpTo(it.messageId) }) }
    // Two or more photos and videos form one grid; a single one, voice messages and files keep their own views.
    val media = m.attachments.filter { it.kind == AttachmentKindUi.IMAGE || it.kind == AttachmentKindUi.VIDEO }
    if (media.size >= 2) {
        MediaGrid(m.id, media, actions)
        Spacer(Modifier.height(4.dp))
    }
    for (a in m.attachments) {
        if (media.size >= 2 && a in media) continue
        AttachmentView(m.id, a, actions, playback)
        Spacer(Modifier.height(4.dp))
    }
    if (!m.text.isNullOrBlank()) {
        val linkColor = if (m.outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
        val text = remember(m.text, linkColor, actions) {
            linkify(m.text, SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), actions.onOpenLink)
        }
        // read as part of the bubble's description; the bubble offers the links as accessibility actions
        Text(text, style = MaterialTheme.typography.bodyLarge, color = content, modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {})
    }
}

@Composable
private fun DeletedPlaceholder(content: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp).clearAndSetSemantics {}) {
        Icon(Icons.Filled.Block, contentDescription = null, tint = content.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.message_deleted), fontStyle = FontStyle.Italic, color = content.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun UndecryptablePlaceholder(kind: UndecryptableUi, onReview: () -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics {}) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.message_undecryptable), fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp),
            )
        }
        if (kind == UndecryptableUi.RETRYABLE_IDENTITY) {
            Text(
                stringResource(R.string.message_undecryptable_identity), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {},
            )
            Text(
                stringResource(R.string.action_review_identity), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onReview).padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun BubbleFooter(m: MessageUi, time: String, content: Color) {
    Row(
        // the bubble's description already says time and status
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.expiresIn != null && !m.deleted) {
            Icon(Icons.Filled.Timer, contentDescription = null, tint = content.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        if (m.edited && !m.deleted) {
            Text(stringResource(R.string.message_edited), style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f))
            Spacer(Modifier.width(6.dp))
        }
        Text(time, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f))
        if (m.outgoing) {
            Spacer(Modifier.width(4.dp))
            DeliveryIcon(m.delivery, content, describe = false)
        }
    }
}

/** Clock = waiting, one check = sent, two = delivered, two in colour = read. Every state has a description. */
@Composable
fun DeliveryIcon(delivery: DeliveryUi, base: Color, modifier: Modifier = Modifier, describe: Boolean = true) {
    val status = LocalStatusColors.current
    val (icon, tint, text) = when (delivery) {
        DeliveryUi.PENDING -> Triple(Icons.Filled.Schedule, base.copy(alpha = 0.7f), R.string.status_pending)
        DeliveryUi.SENT -> Triple(Icons.Filled.Done, base.copy(alpha = 0.7f), R.string.status_sent)
        DeliveryUi.DELIVERED -> Triple(Icons.Filled.DoneAll, base.copy(alpha = 0.7f), R.string.status_delivered)
        DeliveryUi.READ -> Triple(Icons.Filled.DoneAll, status.read, R.string.status_read)
        DeliveryUi.FAILED -> Triple(Icons.Filled.ErrorOutline, status.failed, R.string.status_failed)
        DeliveryUi.NONE -> return
    }
    Icon(icon, contentDescription = if (describe) stringResource(text) else null, tint = tint, modifier = modifier.size(16.dp))
}

/** The status word a screen reader says for a delivery state, null if there is none. */
@Composable
private fun deliveryWord(delivery: DeliveryUi): String? = when (delivery) {
    DeliveryUi.PENDING -> stringResource(R.string.status_pending)
    DeliveryUi.SENT -> stringResource(R.string.status_sent)
    DeliveryUi.DELIVERED -> stringResource(R.string.status_delivered)
    DeliveryUi.READ -> stringResource(R.string.status_read)
    DeliveryUi.FAILED -> stringResource(R.string.status_failed)
    DeliveryUi.NONE -> null
}

/**
 * What TalkBack says for a bubble: "Bertil Ek, Ska vi ses vid fyra?, 14:30" or "Du, Foto, redigerad, 14:33, Läst".
 * Who first, then what (a reply, attachments, the text), then when and the delivery status of own messages.
 */
@Composable
fun bubbleDescription(m: MessageUi, time: String): String {
    val parts = ArrayList<String>(6)
    parts += if (m.outgoing) stringResource(R.string.you) else m.speaker ?: m.senderName ?: stringResource(R.string.unknown_user)
    when {
        m.deleted -> parts += stringResource(R.string.message_deleted)
        m.undecryptable != null -> parts += stringResource(R.string.message_undecryptable)
        else -> {
            if (m.forwarded) parts += stringResource(R.string.message_forwarded)
            if (m.imported) parts += stringResource(R.string.message_imported)
            m.reply?.let { parts += stringResource(R.string.bubble_reply_to, it.senderName ?: stringResource(R.string.you)) }
            for (a in m.attachments) {
                val kind = attachmentKindLabel(a.kind)
                parts += when {
                    a.kind == AttachmentKindUi.VOICE && a.durationMs != null -> "$kind ${formatDuration(a.durationMs)}"
                    a.kind == AttachmentKindUi.FILE && a.name != null -> stringResource(R.string.attachment_file_named, a.name)
                    else -> kind
                }
            }
            if (!m.text.isNullOrBlank()) parts += m.text
            if (m.edited) parts += stringResource(R.string.message_edited)
            if (m.reactions.isNotEmpty()) parts += m.reactions.joinToString(" ") { "${it.emoji} ${it.count}" }
        }
    }
    parts += time
    deliveryWord(m.delivery)?.let { parts += it }
    return parts.joinToString(", ")
}

@Composable
fun ReplyQuote(reply: ReplyUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).background(scheme.surface.copy(alpha = 0.55f))
            .clickable(onClick = onClick).height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(scheme.primary))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                reply.senderName ?: stringResource(R.string.you), style = MaterialTheme.typography.labelMedium,
                color = scheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            val body = when {
                reply.deleted -> stringResource(R.string.message_deleted)
                !reply.text.isNullOrBlank() -> reply.text
                reply.attachmentKind != null -> attachmentKindLabel(reply.attachmentKind)
                else -> ""
            }
            Text(body, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun attachmentKindLabel(kind: AttachmentKindUi): String = stringResource(
    when (kind) {
        AttachmentKindUi.IMAGE -> R.string.attachment_photo
        AttachmentKindUi.VIDEO -> R.string.attachment_video
        AttachmentKindUi.VOICE -> R.string.attachment_voice
        AttachmentKindUi.FILE -> R.string.attachment_file
    },
)

// --- attachments ------------------------------------------------------------------------------------------------------

@Composable
fun AttachmentView(messageId: String, a: AttachmentUi, actions: BubbleActions, playback: PlaybackUi) {
    when (a.kind) {
        AttachmentKindUi.IMAGE, AttachmentKindUi.VIDEO -> MediaThumbnail(messageId, a, actions)
        AttachmentKindUi.VOICE -> VoiceView(messageId, a, actions, playback)
        AttachmentKindUi.FILE -> FileChip(messageId, a, actions)
    }
}

@Composable
private fun MediaThumbnail(messageId: String, a: AttachmentUi, actions: BubbleActions) {
    val w = a.width?.takeIf { it > 0 } ?: 4
    val h = a.height?.takeIf { it > 0 } ?: 3
    val ratio = (w.toFloat() / h).coerceIn(0.6f, 1.8f)
    MediaTile(messageId, a, actions, Modifier.width(240.dp).heightIn(max = 320.dp).aspectRatio(ratio))
}

/** Width of the media in a bubble; the grid divides it into two columns. */
private val GRID_WIDTH = 240.dp
private val GRID_GAP = 4.dp
private val GRID_CELL = (GRID_WIDTH - GRID_GAP) / 2

/**
 * Photos and videos of one message as a grid: two side by side; three as one tall picture and two small; four or more as
 * 2 x 2, the last cell saying how many more there are ("+N"). A tap on any cell opens the viewer there, which swipes through all.
 */
@Composable
fun MediaGrid(messageId: String, items: List<AttachmentUi>, actions: BubbleActions) {
    val description = stringResource(R.string.media_grid_description, items.size)
    val tall = GRID_CELL * 2 + GRID_GAP
    Box(Modifier.width(GRID_WIDTH).clip(RoundedCornerShape(10.dp)).semantics { contentDescription = description }) {
        when (items.size) {
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                for (a in items) MediaTile(messageId, a, actions, Modifier.size(GRID_CELL, 160.dp), corner = 0.dp)
            }
            3 -> Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                MediaTile(messageId, items[0], actions, Modifier.size(GRID_CELL, tall), corner = 0.dp)
                Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                    MediaTile(messageId, items[1], actions, Modifier.size(GRID_CELL, GRID_CELL), corner = 0.dp)
                    MediaTile(messageId, items[2], actions, Modifier.size(GRID_CELL, GRID_CELL), corner = 0.dp)
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                for (row in 0..1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                        for (col in 0..1) {
                            val i = row * 2 + col
                            val more = if (i == 3) items.size - 4 else 0
                            MediaTile(messageId, items[i], actions, Modifier.size(GRID_CELL, GRID_CELL), corner = 0.dp, moreCount = more)
                        }
                    }
                }
            }
        }
    }
}

/** One photo or video: its preview, the download/progress badge, and [moreCount] ("+N") over it when the grid has more. */
@Composable
private fun MediaTile(
    messageId: String,
    a: AttachmentUi,
    actions: BubbleActions,
    modifier: Modifier,
    corner: androidx.compose.ui.unit.Dp = 10.dp,
    moreCount: Int = 0,
) {
    val thumb = remember(a.thumbnail) { a.thumbnail?.let { decodeThumbnailSafe(it) } }
    val ready = (a.transfer as? TransferUi.Ready)?.path
    val description = stringResource(if (a.kind == AttachmentKindUi.VIDEO) R.string.attachment_video else R.string.attachment_photo)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClickLabel = description, enabled = a.transfer !is TransferUi.Unavailable) {
                if (ready != null) actions.onOpenAttachment(messageId, a) else actions.onDownload(messageId, a)
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (a.transfer is TransferUi.Invalid || a.transfer is TransferUi.Unavailable) {
            if (thumb != null && a.transfer is TransferUi.Unavailable) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                Icon(
                    Icons.Filled.BrokenImage,
                    contentDescription = stringResource(
                        if (a.transfer is TransferUi.Unavailable) R.string.attachment_unavailable else R.string.attachment_invalid,
                    ),
                    modifier = Modifier.size(40.dp),
                )
            }
        } else {
            if (thumb != null) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
            if (ready != null && a.kind == AttachmentKindUi.IMAGE) {
                AsyncImage(model = File(ready), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
            if (moreCount > 0) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.media_more_count, moreCount), color = Color.White,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                TransferOverlay(a, playIcon = a.kind == AttachmentKindUi.VIDEO && ready != null)
            }
            if (a.kind == AttachmentKindUi.VIDEO && a.durationMs != null && moreCount == 0) {
                Text(
                    formatDuration(a.durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** The round badge on a thumbnail: download arrow, progress, retry or play. */
@Composable
private fun TransferOverlay(a: AttachmentUi, playIcon: Boolean) {
    val badge = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
    when (val t = a.transfer) {
        is TransferUi.Remote -> Box(badge, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.action_download), tint = Color.White)
        }
        is TransferUi.Failed -> Box(badge, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_retry), tint = Color.White)
        }
        is TransferUi.Downloading -> Box(badge, contentAlignment = Alignment.Center) {
            if (t.progress != null) CircularProgressIndicator(progress = { t.progress }, color = Color.White, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            else CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        is TransferUi.Uploading -> Box(badge, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        is TransferUi.Ready -> if (playIcon) Box(badge, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.action_play), tint = Color.White, modifier = Modifier.size(32.dp))
        }
        is TransferUi.Invalid, is TransferUi.Unavailable -> Unit
    }
}

@Composable
private fun VoiceView(messageId: String, a: AttachmentUi, actions: BubbleActions, playback: PlaybackUi) {
    val active = playback.attachmentId == a.id
    val playing = active && playback.playing
    val total = (if (active && playback.durationMs > 0) playback.durationMs else a.durationMs) ?: 0L
    val position = if (active) playback.positionMs else 0L
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 200.dp)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                .clickable(role = Role.Button, enabled = a.transfer !is TransferUi.Unavailable) {
                    if (a.transfer is TransferUi.Ready) actions.onToggleVoice(messageId, a) else actions.onDownload(messageId, a)
                },
            contentAlignment = Alignment.Center,
        ) {
            when (val t = a.transfer) {
                is TransferUi.Downloading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                is TransferUi.Ready -> Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                is TransferUi.Invalid -> Icon(Icons.Filled.BrokenImage, stringResource(R.string.attachment_invalid), tint = MaterialTheme.colorScheme.onPrimary)
                is TransferUi.Unavailable ->
                    Icon(Icons.Filled.BrokenImage, stringResource(R.string.attachment_unavailable), tint = MaterialTheme.colorScheme.onPrimary)
                else -> Icon(Icons.Filled.Download, stringResource(R.string.action_download), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        // the position is spoken through the play button; the bubble's description names the duration
        Column(Modifier.padding(start = 10.dp).weight(1f).clearAndSetSemantics {}) {
            val progress = if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
            Text(
                if (active && position > 0) "${formatDuration(position)} / ${formatDuration(total)}" else formatDuration(total),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (a.transfer is TransferUi.Ready) SpeedChip(playback.speed, actions.onCycleVoiceSpeed)
    }
}

/** "1x" / "1.5x" / "2x": one tap moves to the next speed, for all voice messages. */
@Composable
private fun SpeedChip(speed: Float, onClick: () -> Unit) {
    val label = speedLabel(speed)
    val description = stringResource(R.string.voice_speed_description, label)
    Box(
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.voice_speed_change), onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 1 -> "1x", 1.5 -> "1.5x", 2 -> "2x" (a digit and an x read the same in every language). */
fun speedLabel(speed: Float): String = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

@Composable
private fun FileChip(messageId: String, a: AttachmentUi, actions: BubbleActions) {
    val ready = a.transfer is TransferUi.Ready
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(role = Role.Button, enabled = a.transfer !is TransferUi.Unavailable) {
                if (ready) actions.onOpenAttachment(messageId, a) else actions.onDownload(messageId, a)
            }
            .padding(8.dp),
    ) {
        when (a.transfer) {
            is TransferUi.Downloading, is TransferUi.Uploading ->
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            else -> Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(a.name ?: stringResource(R.string.attachment_file), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                when (a.transfer) {
                    is TransferUi.Invalid -> stringResource(R.string.attachment_invalid)
                    is TransferUi.Unavailable -> stringResource(R.string.attachment_unavailable)
                    else -> formatSize(a.size)
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (a.transfer is TransferUi.Remote || a.transfer is TransferUi.Failed) {
            Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.action_download), modifier = Modifier.padding(start = 8.dp))
        }
    }
}
