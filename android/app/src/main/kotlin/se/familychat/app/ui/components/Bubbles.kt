package se.familychat.app.ui.components

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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import se.familychat.app.R
import se.familychat.app.ui.model.AttachmentKindUi
import se.familychat.app.ui.model.AttachmentUi
import se.familychat.app.ui.model.ChatRow
import se.familychat.app.ui.model.DeliveryUi
import se.familychat.app.ui.model.MessageUi
import se.familychat.app.ui.model.ReplyUi
import se.familychat.app.ui.model.TransferUi
import se.familychat.app.ui.model.UndecryptableUi
import se.familychat.app.ui.theme.LocalStatusColors
import java.io.File

/** Playback state of the voice message that is currently loaded. */
data class PlaybackUi(val attachmentId: String? = null, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0)

/** What a bubble can ask for. All of them are optional no-ops in previews and screenshots. */
class BubbleActions(
    val onReply: (String) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onRetry: (String) -> Unit = {},
    val onOpenAttachment: (messageId: String, attachment: AttachmentUi) -> Unit = { _, _ -> },
    val onDownload: (messageId: String, attachment: AttachmentUi) -> Unit = { _, _ -> },
    val onToggleVoice: (messageId: String, attachment: AttachmentUi) -> Unit = { _, _ -> },
    val onReviewIdentity: () -> Unit = {},
    /** Opens the safety screen of a user (also the own account), from a system notice. */
    val onOpenContact: (userId: String) -> Unit = {},
    val onJumpTo: (String) -> Unit = {},
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
    val scheme = MaterialTheme.colorScheme
    val container = if (m.outgoing) scheme.primaryContainer else scheme.surfaceContainerHigh
    val content = if (m.outgoing) scheme.onPrimaryContainer else scheme.onSurface
    val shape = if (m.outgoing) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
    val topPadding = if (row.firstOfRun) 8.dp else 2.dp

    Row(
        modifier = modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = topPadding),
        horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!m.outgoing && m.senderAvatar != null) {
            Box(Modifier.width(32.dp).padding(end = 4.dp), contentAlignment = Alignment.BottomCenter) {
                if (row.firstOfRun) Avatar(m.senderAvatar, size = 28.dp)
            }
        }
        Box(Modifier.widthIn(max = 320.dp)) {
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
                    ),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).width(IntrinsicSize.Max).widthIn(min = 72.dp)) {
                    if (m.senderName != null && row.firstOfRun) {
                        Text(
                            m.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    when {
                        m.deleted -> DeletedPlaceholder(content)
                        m.undecryptable != null -> UndecryptablePlaceholder(m.undecryptable, actions.onReviewIdentity)
                        else -> BubbleBody(m, actions, playback, content)
                    }
                    BubbleFooter(m, clock(m.time), content)
                    if (m.failed) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = LocalStatusColors.current.failed, modifier = Modifier.size(16.dp))
                            Text(
                                stringResource(R.string.message_failed_retry), style = MaterialTheme.typography.labelMedium,
                                color = LocalStatusColors.current.failed, modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val canReply = !m.deleted && m.undecryptable == null
                if (canReply) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reply)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
                        onClick = { menuOpen = false; actions.onReply(m.id) },
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
                if (m.canDelete) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menuOpen = false; actions.onDelete(m.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BubbleBody(m: MessageUi, actions: BubbleActions, playback: PlaybackUi, content: Color) {
    m.reply?.let { ReplyQuote(it, onClick = { actions.onJumpTo(it.messageId) }) }
    for (a in m.attachments) {
        AttachmentView(m.id, a, actions, playback)
        Spacer(Modifier.height(4.dp))
    }
    if (!m.text.isNullOrBlank()) {
        Text(m.text, style = MaterialTheme.typography.bodyLarge, color = content, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun DeletedPlaceholder(content: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.message_undecryptable), fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp),
            )
        }
        if (kind == UndecryptableUi.RETRYABLE_IDENTITY) {
            Text(
                stringResource(R.string.message_undecryptable_identity), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
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
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.edited && !m.deleted) {
            Text(stringResource(R.string.message_edited), style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f))
            Spacer(Modifier.width(6.dp))
        }
        Text(time, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f))
        if (m.outgoing) {
            Spacer(Modifier.width(4.dp))
            DeliveryIcon(m.delivery, content)
        }
    }
}

/** Clock = waiting, one check = sent, two = delivered, two in colour = read. Every state has a description. */
@Composable
fun DeliveryIcon(delivery: DeliveryUi, base: Color, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    val (icon, tint, text) = when (delivery) {
        DeliveryUi.PENDING -> Triple(Icons.Filled.Schedule, base.copy(alpha = 0.7f), R.string.status_pending)
        DeliveryUi.SENT -> Triple(Icons.Filled.Done, base.copy(alpha = 0.7f), R.string.status_sent)
        DeliveryUi.DELIVERED -> Triple(Icons.Filled.DoneAll, base.copy(alpha = 0.7f), R.string.status_delivered)
        DeliveryUi.READ -> Triple(Icons.Filled.DoneAll, status.read, R.string.status_read)
        DeliveryUi.FAILED -> Triple(Icons.Filled.ErrorOutline, status.failed, R.string.status_failed)
        DeliveryUi.NONE -> return
    }
    Icon(icon, contentDescription = stringResource(text), tint = tint, modifier = modifier.size(16.dp))
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
    val thumb = remember(a.thumbnail) { a.thumbnail?.let { decodeThumbnailSafe(it) } }
    val ready = (a.transfer as? TransferUi.Ready)?.path
    val description = stringResource(if (a.kind == AttachmentKindUi.VIDEO) R.string.attachment_video else R.string.attachment_photo)
    Box(
        modifier = Modifier
            .width(240.dp)
            .heightIn(max = 320.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClickLabel = description) {
                if (ready != null) actions.onOpenAttachment(messageId, a) else actions.onDownload(messageId, a)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (a.transfer is TransferUi.Invalid) {
            Icon(Icons.Filled.BrokenImage, contentDescription = stringResource(R.string.attachment_invalid), modifier = Modifier.size(40.dp))
        } else {
            if (thumb != null) {
                Image(thumb, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
            if (ready != null && a.kind == AttachmentKindUi.IMAGE) {
                AsyncImage(model = File(ready), contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
            TransferOverlay(a, playIcon = a.kind == AttachmentKindUi.VIDEO && ready != null)
            if (a.kind == AttachmentKindUi.VIDEO && a.durationMs != null) {
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
        is TransferUi.Invalid -> Unit
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
                .clickable(role = Role.Button) {
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
                else -> Icon(Icons.Filled.Download, stringResource(R.string.action_download), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            val progress = if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
            Text(
                if (active && position > 0) "${formatDuration(position)} / ${formatDuration(total)}" else formatDuration(total),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun FileChip(messageId: String, a: AttachmentUi, actions: BubbleActions) {
    val ready = a.transfer is TransferUi.Ready
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(role = Role.Button) { if (ready) actions.onOpenAttachment(messageId, a) else actions.onDownload(messageId, a) }
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
                if (a.transfer is TransferUi.Invalid) stringResource(R.string.attachment_invalid) else formatSize(a.size),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (a.transfer is TransferUi.Remote || a.transfer is TransferUi.Failed) {
            Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.action_download), modifier = Modifier.padding(start = 8.dp))
        }
    }
}
