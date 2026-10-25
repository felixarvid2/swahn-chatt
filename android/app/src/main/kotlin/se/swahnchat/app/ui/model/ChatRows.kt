package se.swahnchat.app.ui.model

import se.swahnchat.core.client.DecryptError
import se.swahnchat.core.client.DownloadState
import se.swahnchat.core.client.LocalAttachment
import se.swahnchat.core.client.MessageStatus
import se.swahnchat.core.client.Reaction
import se.swahnchat.core.client.StoredMessage
import se.swahnchat.core.client.CallOutcome
import se.swahnchat.core.client.SystemEvent
import se.swahnchat.core.model.User
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import androidx.paging.PagingData
import androidx.paging.insertSeparators

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
    /** Message id -> the reactions that stand on it (not taken back), oldest first. */
    val reactions: Map<String, List<Reaction>> = emptyMap(),
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
        key == null && localPath == null -> TransferUi.Unavailable
        downloadState == DownloadState.FAILED -> TransferUi.Failed
        else -> TransferUi.Remote
    }
    return AttachmentUi(id, attachmentKindOf(kind), mime, name, size, width, height, durationMs, decodeThumbnail(thumbnail), transfer)
}

/** Reactions grouped by emoji, the most used first (ties: the one used first). */
internal fun groupReactions(reactions: List<Reaction>, ctx: ChatRowContext): List<ReactionUi> =
    reactions.filter { it.emoji != null }.groupBy { it.emoji!! }.map { (emoji, list) ->
        ReactionUi(
            emoji,
            list.map { r ->
                val user = ctx.users[r.userId]
                val name = user?.displayName ?: ctx.unknownName
                ReactorUi(r.userId, name, AvatarUi(name, user?.avatarAttachmentId), r.userId == ctx.myUserId)
            },
        )
    }.sortedByDescending { it.count }

private fun StoredMessage.toUi(ctx: ChatRowContext, replyTarget: StoredMessage?): MessageUi {
    val outgoingMine = outgoing
    val sentAndUsable = !deleted && decryptError == null && status != MessageStatus.PENDING && status != MessageStatus.FAILED
    val name = ctx.users[senderUserId]?.displayName
    val reply = replyTarget?.let {
        ReplyUi(
            messageId = it.id,
            senderName = if (it.senderUserId == ctx.myUserId) null else ctx.users[it.senderUserId]?.displayName ?: ctx.unknownName,
            text = if (it.deleted) null else it.text,
            attachmentKind = it.attachments.firstOrNull()?.let { a -> attachmentKindOf(a.kind) },
            deleted = it.deleted,
        )
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
        canDeleteForMe = !(outgoingMine && status == MessageStatus.PENDING),
        canShowInfo = outgoingMine && !deleted && (status == MessageStatus.SENT || status == MessageStatus.DELIVERED || status == MessageStatus.READ),
        senderId = senderUserId,
        speaker = if (outgoingMine) null else name ?: ctx.unknownName,
        reactions = if (deleted) emptyList() else groupReactions(ctx.reactions[id].orEmpty(), ctx),
        myReaction = ctx.reactions[id]?.firstOrNull { it.userId == ctx.myUserId }?.emoji,
        canReact = sentAndUsable, canForward = sentAndUsable && (!text.isNullOrBlank() || attachments.isNotEmpty()),
        forwarded = forwarded, imported = imported, expiresIn = expiresIn,
    )
}

/**
 * One stored message as a list row: a notice for system events, else a message bubble. [replyTarget] is the message it
 * quotes (anywhere in the conversation, not only on screen). [ChatRow.Message.firstOfRun] is left at its default; see
 * [isFirstOfRun].
 */
fun StoredMessage.toChatRow(ctx: ChatRowContext, replyTarget: StoredMessage? = null): ChatRow {
    val event = systemEvent
    if (event == null) return ChatRow.Message(toUi(ctx, replyTarget))
    val time = serverTs ?: clientTs
    val (userId, deviceName, kind) = when (event) {
        is SystemEvent.NewDevice ->
            Triple(event.userId, event.deviceName, if (event.needsApproval) SystemKind.NEW_DEVICE_BLOCKED else SystemKind.NEW_DEVICE)
        is SystemEvent.IdentityChanged -> Triple(event.userId, null, SystemKind.IDENTITY_CHANGED)
        is SystemEvent.MemberAdded -> Triple(event.userId, null, SystemKind.MEMBER_ADDED)
        is SystemEvent.MemberRemoved -> Triple(event.userId, null, SystemKind.MEMBER_REMOVED)
        is SystemEvent.TimerChanged -> Triple(event.userId, null, SystemKind.TIMER_CHANGED)
        SystemEvent.UpdateRequired -> Triple("", null, SystemKind.UPDATE_REQUIRED)
        is SystemEvent.Call -> Triple(event.peerUserId, null, event.outcome.toKind())
    }
    val value = (event as? SystemEvent.TimerChanged)?.seconds ?: (event as? SystemEvent.Call)?.durationSeconds ?: 0L
    return ChatRow.System(
        id, kind, userId, ctx.users[userId]?.displayName ?: ctx.unknownName, deviceName, time,
        own = userId == ctx.myUserId, value = value, video = (event as? SystemEvent.Call)?.video ?: false,
    )
}

private fun CallOutcome.toKind(): SystemKind = when (this) {
    CallOutcome.COMPLETED -> SystemKind.CALL_COMPLETED
    CallOutcome.MISSED -> SystemKind.CALL_MISSED
    CallOutcome.NO_ANSWER -> SystemKind.CALL_NO_ANSWER
    CallOutcome.BUSY -> SystemKind.CALL_BUSY
    CallOutcome.REJECTED -> SystemKind.CALL_REJECTED
}

private fun ChatRow.timeOrNull(): Long? = when (this) {
    is ChatRow.Message -> message.time
    is ChatRow.System -> time
    else -> null
}

private fun dayStart(time: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(time).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * The date separator that belongs between two neighbouring rows of a list that is ordered NEWEST first (the paged list,
 * which a reversed `LazyColumn` shows oldest-first): [newer] is the row nearer the bottom. A separator for the day of
 * [newer] goes above its first message, i.e. when [older] is from another day or when there is no older row at all.
 */
fun dateSeparatorBetween(newer: ChatRow?, older: ChatRow?, zone: ZoneId): ChatRow.DateHeader? {
    val day = newer?.timeOrNull()?.let { dayStart(it, zone) } ?: return null
    val olderDay = older?.timeOrNull()?.let { dayStart(it, zone) }
    return if (olderDay != day) ChatRow.DateHeader(day) else null
}

/** True if [row] starts a run of messages by one sender; [older] is the row directly above it (null: top of the list). */
fun isFirstOfRun(row: ChatRow.Message, older: ChatRow?): Boolean =
    older !is ChatRow.Message || older.message.senderId != row.message.senderId

/**
 * Adds date separators and, if [firstUnreadId] is set, the "unread" divider to a page stream of rows ordered newest
 * first. The oldest separator only appears once the start of the conversation was loaded (default terminal behaviour).
 */
fun PagingData<ChatRow>.withSeparators(zone: ZoneId, firstUnreadId: String?): PagingData<ChatRow> {
    val dated = insertSeparators<ChatRow, ChatRow> { newer, older -> dateSeparatorBetween(newer, older, zone) }
    if (firstUnreadId == null) return dated
    return dated.insertSeparators<ChatRow, ChatRow> { newer, _ ->
        if (newer is ChatRow.Message && newer.message.id == firstUnreadId) ChatRow.UnreadDivider else null
    }
}

/**
 * Turns the stored messages (oldest first) into list rows: date separators between days, system notices, and a flag on the
 * first message of a run by the same sender (where a group shows the sender's name). The conversation screen builds the
 * same rows page by page (see [toChatRow], [withSeparators], [isFirstOfRun]); this is the one-shot form.
 */
fun buildChatRows(messages: List<StoredMessage>, ctx: ChatRowContext): List<ChatRow> {
    val byId = messages.associateBy { it.id }
    val rows = ArrayList<ChatRow>(messages.size + 8)
    var lastDay: Long? = null
    var previousSender: String? = null
    for (m in messages) {
        val day = dayStart(m.serverTs ?: m.clientTs, ctx.zone)
        if (day != lastDay) {
            rows += ChatRow.DateHeader(day)
            lastDay = day
            previousSender = null
        }
        val row = m.toChatRow(ctx, m.replyTo?.let { byId[it] ?: ctx.referenced[it] })
        if (row is ChatRow.Message) {
            rows += row.copy(firstOfRun = m.senderUserId != previousSender)
            previousSender = m.senderUserId
        } else {
            rows += row
            previousSender = null
        }
    }
    return rows
}
