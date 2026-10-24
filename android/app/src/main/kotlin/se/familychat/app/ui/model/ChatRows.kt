package se.familychat.app.ui.model

import se.familychat.core.client.DecryptError
import se.familychat.core.client.DownloadState
import se.familychat.core.client.LocalAttachment
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.StoredMessage
import se.familychat.core.client.SystemEvent
import se.familychat.core.model.User
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/** Everything [buildChatRows] needs besides the messages. */
class ChatRowContext(
    val myUserId: String,
    val isGroup: Boolean,
    val users: Map<String, User>,
    /** Messages that are replied to but outside the loaded window. */
    val referenced: Map<String, StoredMessage> = emptyMap(),
    /** Attachment id -> download progress (0..1, or null while unknown) of running downloads. */
    val downloading: Map<String, Float?> = emptyMap(),
    /**
     * Result of validating the decrypted file of an attachment (see `MediaValidator`): true = may be rendered,
     * false = not what the message claimed. Files not in the map are being checked.
     */
    val validated: Map<String, Boolean> = emptyMap(),
    /** Attachments the engine believes are on disk but whose file is gone (cleaned up); they are fetched again on demand. */
    val missing: Set<String> = emptySet(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val unknownName: String = "?",
)

fun attachmentKindOf(kind: String): AttachmentKindUi = when (kind) {
    "image" -> AttachmentKindUi.IMAGE
    "video" -> AttachmentKindUi.VIDEO
    "voice" -> AttachmentKindUi.VOICE
    else -> AttachmentKindUi.FILE
}

fun StoredMessage.delivery(): DeliveryUi = when {
    !outgoing -> DeliveryUi.NONE
    status == MessageStatus.PENDING -> DeliveryUi.PENDING
    status == MessageStatus.FAILED -> DeliveryUi.FAILED
    status == MessageStatus.SENT -> DeliveryUi.SENT
    status == MessageStatus.DELIVERED -> DeliveryUi.DELIVERED
    status == MessageStatus.READ -> DeliveryUi.READ
    else -> DeliveryUi.NONE
}

internal fun decodeThumbnail(base64: String?): ByteArray? =
    if (base64.isNullOrEmpty()) null else try {
        Base64.getDecoder().decode(base64)
    } catch (e: IllegalArgumentException) {
        null
    }

private fun LocalAttachment.toUi(pending: Boolean, ctx: ChatRowContext): AttachmentUi {
    val transfer: TransferUi = when {
        id in ctx.missing -> TransferUi.Remote
        ctx.validated[id] == false -> TransferUi.Invalid
        id in ctx.downloading -> TransferUi.Downloading(ctx.downloading[id])
        pending -> TransferUi.Uploading
        downloadState == DownloadState.DONE && localPath != null ->
            if (ctx.validated[id] == true) TransferUi.Ready(localPath!!) else TransferUi.Downloading(null)
        downloadState == DownloadState.FAILED -> TransferUi.Failed
        else -> TransferUi.Remote
    }
    return AttachmentUi(id, attachmentKindOf(kind), mime, name, size, width, height, durationMs, decodeThumbnail(thumbnail), transfer)
}

private fun StoredMessage.toUi(ctx: ChatRowContext, byId: Map<String, StoredMessage>): MessageUi {
    val outgoingMine = outgoing
    val name = ctx.users[senderUserId]?.displayName
    val reply = replyTo?.let { rid ->
        val target = byId[rid] ?: ctx.referenced[rid]
        target?.let {
            ReplyUi(
                messageId = it.id,
                senderName = if (it.senderUserId == ctx.myUserId) null else ctx.users[it.senderUserId]?.displayName ?: ctx.unknownName,
                text = if (it.deleted) null else it.text,
                attachmentKind = it.attachments.firstOrNull()?.let { a -> attachmentKindOf(a.kind) },
                deleted = it.deleted,
            )
        }
    }
    return MessageUi(
        id = id, outgoing = outgoingMine,
        senderName = if (ctx.isGroup && !outgoingMine) name ?: ctx.unknownName else null,
        senderAvatar = if (ctx.isGroup && !outgoingMine) AvatarUi(name ?: ctx.unknownName, ctx.users[senderUserId]?.avatarAttachmentId) else null,
        text = text, reply = reply, attachments = attachments.map { it.toUi(outgoingMine && status == MessageStatus.PENDING, ctx) },
        time = serverTs ?: clientTs, delivery = delivery(), edited = edited, deleted = deleted,
        undecryptable = decryptError?.let {
            when (it) {
                DecryptError.UNTRUSTED_IDENTITY -> UndecryptableUi.RETRYABLE_IDENTITY
                DecryptError.CORRUPT -> UndecryptableUi.CORRUPT
                else -> UndecryptableUi.OTHER
            }
        },
        failed = outgoingMine && status == MessageStatus.FAILED,
        canEdit = outgoingMine && !deleted && status != MessageStatus.FAILED && !text.isNullOrBlank(),
        canDelete = outgoingMine && !deleted,
    )
}

/**
 * Turns the stored messages (oldest first) into list rows: date separators between days, system notices, and a flag on the
 * first message of a run by the same sender (where a group shows the sender's name).
 */
fun buildChatRows(messages: List<StoredMessage>, ctx: ChatRowContext): List<ChatRow> {
    val byId = messages.associateBy { it.id }
    val rows = ArrayList<ChatRow>(messages.size + 8)
    var lastDay: Long? = null
    var previousSender: String? = null
    for (m in messages) {
        val time = m.serverTs ?: m.clientTs
        val day = Instant.ofEpochMilli(time).atZone(ctx.zone).toLocalDate().atStartOfDay(ctx.zone).toInstant().toEpochMilli()
        if (day != lastDay) {
            rows += ChatRow.DateHeader(day)
            lastDay = day
            previousSender = null
        }
        val event = m.systemEvent
        if (event != null) {
            previousSender = null
            val (userId, deviceName, kind) = when (event) {
                is SystemEvent.NewDevice -> Triple(event.userId, event.deviceName, SystemKind.NEW_DEVICE)
                is SystemEvent.IdentityChanged -> Triple(event.userId, null, SystemKind.IDENTITY_CHANGED)
            }
            rows += ChatRow.System(m.id, kind, userId, ctx.users[userId]?.displayName ?: ctx.unknownName, deviceName, time)
            continue
        }
        val sender = m.senderUserId
        rows += ChatRow.Message(m.toUi(ctx, byId), firstOfRun = sender != previousSender)
        previousSender = sender
    }
    return rows
}
