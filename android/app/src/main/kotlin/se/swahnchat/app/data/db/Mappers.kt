package se.swahnchat.app.data.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import se.swahnchat.core.client.Account
import se.swahnchat.core.client.DecryptError
import se.swahnchat.core.client.DownloadState
import se.swahnchat.core.client.FailureReason
import se.swahnchat.core.client.LocalAttachment
import se.swahnchat.core.client.MessageStatus
import se.swahnchat.core.client.OutboxAttachment
import se.swahnchat.core.client.OutboxEntry
import se.swahnchat.core.client.PollInfo
import se.swahnchat.core.client.PollVote
import se.swahnchat.core.client.Reaction
import se.swahnchat.core.client.StoredMessage
import se.swahnchat.core.client.CallOutcome
import se.swahnchat.core.client.SystemEvent
import se.swahnchat.core.model.Conversation
import se.swahnchat.core.model.ConversationMember
import se.swahnchat.core.model.Device
import se.swahnchat.core.model.Envelope
import se.swahnchat.core.model.LocationSpec
import se.swahnchat.core.model.PollOption
import se.swahnchat.core.model.PollSpec
import se.swahnchat.core.model.SwahnChatJson
import se.swahnchat.core.model.User

/** JSON form of [LocalAttachment] inside a message row. */
@Serializable
internal data class AttachmentJson(
    val id: String,
    val key: String? = null,
    val sha256: String? = null,
    val size: Long = 0,
    val mime: String,
    val name: String? = null,
    val kind: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbnail: String? = null,
    val localPath: String? = null,
    val downloadState: String = DownloadState.NONE.name,
)

@Serializable
internal data class OutboxAttachmentJson(
    val localPath: String,
    val mime: String,
    val name: String? = null,
    val kind: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbnail: String? = null,
    val uploadedId: String? = null,
    val uploadedKey: String? = null,
    val uploadedSha256: String? = null,
    val plaintextSize: Long? = null,
)

/** JSON form of [PollInfo] inside a message row. */
@Serializable
internal data class PollJson(val options: List<PollOption>, val multiple: Boolean = false, val closed: Boolean = false)

internal fun PollInfo.toJson(): String = SwahnChatJson.encodeToString(PollJson.serializer(), PollJson(options, multiple, closed))

internal fun String.toPollInfo(): PollInfo? = try {
    SwahnChatJson.decodeFromString(PollJson.serializer(), this).let { PollInfo(it.options, it.multiple, it.closed) }
} catch (e: Exception) {
    null
}

internal fun PollSpec.toJson(): String = SwahnChatJson.encodeToString(PollSpec.serializer(), this)

internal fun String.toPollSpec(): PollSpec? = try {
    SwahnChatJson.decodeFromString(PollSpec.serializer(), this)
} catch (e: Exception) {
    null
}

internal fun LocationSpec.toJson(): String = SwahnChatJson.encodeToString(LocationSpec.serializer(), this)

internal fun String.toLocationSpec(): LocationSpec? = try {
    SwahnChatJson.decodeFromString(LocationSpec.serializer(), this).takeIf { it.isValid }
} catch (e: Exception) {
    null
}

/** `,id1,id2,` so that a query can look for `,id,`; empty for none. */
internal fun List<String>.toMentionColumn(): String = if (isEmpty()) "" else joinToString(",", prefix = ",", postfix = ",")
internal fun String.toMentionList(): List<String> = split(',').filter { it.isNotEmpty() }

internal fun List<Int>.toIdColumn(): String = joinToString(",")
internal fun String.toIdList(): List<Int> = split(',').mapNotNull { it.toIntOrNull() }

private val attachmentsSerializer = ListSerializer(AttachmentJson.serializer())
private val outboxAttachmentsSerializer = ListSerializer(OutboxAttachmentJson.serializer())
private val envelopesSerializer = ListSerializer(Envelope.serializer())

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

private fun String.toSet(): Set<String> = if (isEmpty()) emptySet() else split(',').toSet()
private fun Set<String>.join(): String = sorted().joinToString(",")

// --- account --------------------------------------------------------------------------------------------------------

internal fun Account.toEntity() = AccountEntity(
    serverUrl = serverUrl, userId = userId, deviceId = deviceId, deviceNumber = deviceNumber, username = username,
    displayName = displayName, isAdmin = isAdmin, deviceName = deviceName,
)

internal fun AccountEntity.toModel() = Account(serverUrl, userId, deviceId, deviceNumber, username, displayName, isAdmin, deviceName)

// --- directory ------------------------------------------------------------------------------------------------------

internal fun User.toEntity() = UserEntity(id, username, displayName, avatarAttachmentId, isAdmin, createdAt)
internal fun UserEntity.toModel() = User(id, username, displayName, avatarAttachmentId, isAdmin, createdAt)

internal fun Device.toEntity() = DeviceEntity(id, userId, number, name, createdAt, lastSeenAt, identityKey)
internal fun DeviceEntity.toModel() = Device(id, userId, number, name, createdAt, lastSeenAt, identityKey)

// --- conversations --------------------------------------------------------------------------------------------------

internal fun ConversationEntity.toModel(members: List<MemberEntity>) = Conversation(
    id = id, type = type, name = name, avatarAttachmentId = avatarAttachmentId, createdBy = createdBy,
    createdAt = createdAt, updatedAt = updatedAt, lastMessageAt = lastMessageAt, lastMessageSeq = lastMessageSeq,
    myReadSeq = myReadSeq, unreadCount = 0,
    members = members.map { ConversationMember(it.userId, it.role, it.joinedAt) },
)

// --- messages -------------------------------------------------------------------------------------------------------

internal fun LocalAttachment.toJson() = AttachmentJson(
    id, key, sha256, size, mime, name, kind, width, height, durationMs, thumbnail, localPath, downloadState.name,
)

internal fun AttachmentJson.toModel() = LocalAttachment(
    id, key, sha256, size, mime, name, kind, width, height, durationMs, thumbnail, localPath,
    enumOrNull<DownloadState>(downloadState) ?: DownloadState.NONE,
)

internal fun encodeAttachments(list: List<LocalAttachment>): String =
    if (list.isEmpty()) "[]" else SwahnChatJson.encodeToString(attachmentsSerializer, list.map { it.toJson() })

fun decodeAttachments(json: String): List<LocalAttachment> =
    if (json == "[]" || json.isEmpty()) emptyList()
    else try {
        SwahnChatJson.decodeFromString(attachmentsSerializer, json).map { it.toModel() }
    } catch (e: Exception) {
        emptyList()
    }

internal fun StoredMessage.toEntity(): MessageEntity {
    val sys = systemEvent
    return MessageEntity(
        id = id, conversationId = conversationId, senderUserId = senderUserId, senderDeviceId = senderDeviceId,
        outgoing = outgoing, status = status.name, seq = seq, clientTs = clientTs, serverTs = serverTs, text = text,
        replyTo = replyTo, attachments = encodeAttachments(attachments), edited = edited, editedAt = editedAt,
        deleted = deleted, decryptError = decryptError?.name, failureReason = failureReason?.name,
        deliveredTo = deliveredTo.join(), readBy = readBy.join(),
        systemType = when (sys) {
            null -> null
            is SystemEvent.NewDevice -> if (sys.needsApproval) SYSTEM_NEW_DEVICE_BLOCKED else SYSTEM_NEW_DEVICE
            is SystemEvent.IdentityChanged -> SYSTEM_IDENTITY_CHANGED
            is SystemEvent.MemberAdded -> SYSTEM_MEMBER_ADDED
            is SystemEvent.MemberRemoved -> SYSTEM_MEMBER_REMOVED
            is SystemEvent.TimerChanged -> SYSTEM_TIMER_CHANGED
            SystemEvent.UpdateRequired -> SYSTEM_UPDATE_REQUIRED
            is SystemEvent.Call -> callSystemType(sys)
        },
        systemUserId = when (sys) {
            null -> null
            SystemEvent.UpdateRequired -> null
            is SystemEvent.TimerChanged -> sys.userId
            is SystemEvent.NewDevice -> sys.userId
            is SystemEvent.IdentityChanged -> sys.userId
            is SystemEvent.MemberAdded -> sys.userId
            is SystemEvent.MemberRemoved -> sys.userId
            is SystemEvent.Call -> sys.peerUserId
        },
        systemDeviceId = when (sys) {
            is SystemEvent.NewDevice -> sys.deviceId
            is SystemEvent.IdentityChanged -> sys.deviceId
            else -> null
        },
        systemDeviceName = (sys as? SystemEvent.NewDevice)?.deviceName,
        pendingEnvelopeType = pendingEnvelopeType, pendingEnvelopeBody = pendingEnvelopeBody,
        forwarded = forwarded, imported = imported, expiresIn = expiresIn, expireAt = expireAt,
        systemValue = (sys as? SystemEvent.TimerChanged)?.seconds ?: (sys as? SystemEvent.Call)?.durationSeconds,
        mentions = mentions.toMentionColumn(), poll = poll?.toJson(), scheduledAt = scheduledAt, location = location?.toJson(),
    )
}

internal fun MessageEntity.toModel(): StoredMessage {
    val sys: SystemEvent? = when (systemType) {
        SYSTEM_NEW_DEVICE -> SystemEvent.NewDevice(systemUserId.orEmpty(), systemDeviceId.orEmpty(), systemDeviceName.orEmpty())
        SYSTEM_NEW_DEVICE_BLOCKED ->
            SystemEvent.NewDevice(systemUserId.orEmpty(), systemDeviceId.orEmpty(), systemDeviceName.orEmpty(), needsApproval = true)
        SYSTEM_IDENTITY_CHANGED -> SystemEvent.IdentityChanged(systemUserId.orEmpty(), systemDeviceId.orEmpty())
        SYSTEM_MEMBER_ADDED -> SystemEvent.MemberAdded(systemUserId.orEmpty())
        SYSTEM_MEMBER_REMOVED -> SystemEvent.MemberRemoved(systemUserId.orEmpty())
        SYSTEM_TIMER_CHANGED -> SystemEvent.TimerChanged(systemUserId.orEmpty(), systemValue ?: 0L)
        SYSTEM_UPDATE_REQUIRED -> SystemEvent.UpdateRequired
        else -> callFromSystemType(systemType, systemUserId.orEmpty(), systemValue ?: 0L)
    }
    return StoredMessage(
        id = id, conversationId = conversationId, senderUserId = senderUserId, senderDeviceId = senderDeviceId,
        outgoing = outgoing, status = enumOrNull<MessageStatus>(status) ?: MessageStatus.RECEIVED, seq = seq,
        clientTs = clientTs, serverTs = serverTs, text = text, replyTo = replyTo,
        attachments = decodeAttachments(attachments), edited = edited, editedAt = editedAt, deleted = deleted,
        decryptError = enumOrNull<DecryptError>(decryptError), failureReason = enumOrNull<FailureReason>(failureReason),
        deliveredTo = deliveredTo.toSet(), readBy = readBy.toSet(), systemEvent = sys,
        pendingEnvelopeType = pendingEnvelopeType, pendingEnvelopeBody = pendingEnvelopeBody,
        forwarded = forwarded, imported = imported, expiresIn = expiresIn, expireAt = expireAt,
        mentions = mentions.toMentionList(), poll = poll?.toPollInfo(), scheduledAt = scheduledAt, location = location?.toLocationSpec(),
    )
}

/** `call_<outcome>` or `call_<outcome>_video`, the outcome in lower case; the duration is in `systemValue`, the peer in `systemUserId`. */
private const val SYSTEM_CALL_PREFIX = "call_"
private const val SYSTEM_CALL_VIDEO_SUFFIX = "_video"

private fun callSystemType(call: SystemEvent.Call): String =
    SYSTEM_CALL_PREFIX + call.outcome.name.lowercase() + if (call.video) SYSTEM_CALL_VIDEO_SUFFIX else ""

/** The call notice stored as [type], or null for any other (or an unknown) type. */
private fun callFromSystemType(type: String?, peerUserId: String, seconds: Long): SystemEvent.Call? {
    if (type == null || !type.startsWith(SYSTEM_CALL_PREFIX)) return null
    val video = type.endsWith(SYSTEM_CALL_VIDEO_SUFFIX)
    val name = type.removePrefix(SYSTEM_CALL_PREFIX).removeSuffix(if (video) SYSTEM_CALL_VIDEO_SUFFIX else "").uppercase()
    val outcome = CallOutcome.entries.firstOrNull { it.name == name } ?: return null
    return SystemEvent.Call(peerUserId, outcome, video, seconds)
}

private const val SYSTEM_NEW_DEVICE = "new_device"
private const val SYSTEM_NEW_DEVICE_BLOCKED = "new_device_blocked"
private const val SYSTEM_IDENTITY_CHANGED = "identity_changed"
private const val SYSTEM_MEMBER_ADDED = "member_added"
private const val SYSTEM_MEMBER_REMOVED = "member_removed"
private const val SYSTEM_TIMER_CHANGED = "timer_changed"
private const val SYSTEM_UPDATE_REQUIRED = "update_required"

// --- outbox ---------------------------------------------------------------------------------------------------------

internal fun OutboxEntry.toEntity() = OutboxEntity(
    messageId = messageId, conversationId = conversationId, kind = kind, targetMessageId = targetMessageId, text = text,
    replyTo = replyTo,
    attachments = SwahnChatJson.encodeToString(
        outboxAttachmentsSerializer,
        attachments.map {
            OutboxAttachmentJson(
                it.localPath, it.mime, it.name, it.kind, it.width, it.height, it.durationMs, it.thumbnail,
                it.uploadedId, it.uploadedKey, it.uploadedSha256, it.plaintextSize,
            )
        },
    ),
    clientTs = clientTs, envelopes = envelopes?.let { SwahnChatJson.encodeToString(envelopesSerializer, it) },
    attempts = attempts, lastError = lastError, createdAt = createdAt,
    forwarded = forwarded, expiresIn = expiresIn, remove = remove,
    mentions = mentions.toMentionColumn(), poll = poll?.toJson(), optionIds = optionIds?.toIdColumn(), close = close, sendAt = sendAt,
    location = location?.toJson(),
)

internal fun OutboxEntity.toModel() = OutboxEntry(
    messageId = messageId, conversationId = conversationId, kind = kind, targetMessageId = targetMessageId, text = text,
    replyTo = replyTo,
    attachments = SwahnChatJson.decodeFromString(outboxAttachmentsSerializer, attachments).map {
        OutboxAttachment(
            it.localPath, it.mime, it.name, it.kind, it.width, it.height, it.durationMs, it.thumbnail,
            it.uploadedId, it.uploadedKey, it.uploadedSha256, it.plaintextSize,
        )
    },
    clientTs = clientTs, envelopes = envelopes?.let { SwahnChatJson.decodeFromString(envelopesSerializer, it) },
    attempts = attempts, lastError = lastError, createdAt = createdAt,
    forwarded = forwarded, expiresIn = expiresIn, remove = remove,
    mentions = mentions.toMentionList(), poll = poll?.toPollSpec(), optionIds = optionIds?.toIdList(), close = close, sendAt = sendAt,
    location = location?.toLocationSpec(),
)

internal fun ReactionEntity.toModel() = Reaction(messageId, conversationId, userId, emoji, seq, at)

fun PollVoteEntity.toPollVote() = PollVote(messageId, conversationId, userId, optionIds.toIdList(), seq, at)
internal fun PollVoteEntity.toModel() = PollVote(messageId, conversationId, userId, optionIds.toIdList(), seq, at)
internal fun PollVote.toEntity() = PollVoteEntity(messageId, userId, conversationId, optionIds.toIdColumn(), seq, at)
