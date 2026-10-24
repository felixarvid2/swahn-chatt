package se.familychat.app.data.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import se.familychat.core.client.Account
import se.familychat.core.client.DecryptError
import se.familychat.core.client.DownloadState
import se.familychat.core.client.FailureReason
import se.familychat.core.client.LocalAttachment
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.OutboxAttachment
import se.familychat.core.client.OutboxEntry
import se.familychat.core.client.StoredMessage
import se.familychat.core.client.SystemEvent
import se.familychat.core.model.Conversation
import se.familychat.core.model.ConversationMember
import se.familychat.core.model.Device
import se.familychat.core.model.Envelope
import se.familychat.core.model.FamilyChatJson
import se.familychat.core.model.User

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
    if (list.isEmpty()) "[]" else FamilyChatJson.encodeToString(attachmentsSerializer, list.map { it.toJson() })

fun decodeAttachments(json: String): List<LocalAttachment> =
    if (json == "[]" || json.isEmpty()) emptyList()
    else try {
        FamilyChatJson.decodeFromString(attachmentsSerializer, json).map { it.toModel() }
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
        },
        systemUserId = when (sys) {
            null -> null
            is SystemEvent.NewDevice -> sys.userId
            is SystemEvent.IdentityChanged -> sys.userId
            is SystemEvent.MemberAdded -> sys.userId
            is SystemEvent.MemberRemoved -> sys.userId
        },
        systemDeviceId = when (sys) {
            is SystemEvent.NewDevice -> sys.deviceId
            is SystemEvent.IdentityChanged -> sys.deviceId
            else -> null
        },
        systemDeviceName = (sys as? SystemEvent.NewDevice)?.deviceName,
        pendingEnvelopeType = pendingEnvelopeType, pendingEnvelopeBody = pendingEnvelopeBody,
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
        else -> null
    }
    return StoredMessage(
        id = id, conversationId = conversationId, senderUserId = senderUserId, senderDeviceId = senderDeviceId,
        outgoing = outgoing, status = enumOrNull<MessageStatus>(status) ?: MessageStatus.RECEIVED, seq = seq,
        clientTs = clientTs, serverTs = serverTs, text = text, replyTo = replyTo,
        attachments = decodeAttachments(attachments), edited = edited, editedAt = editedAt, deleted = deleted,
        decryptError = enumOrNull<DecryptError>(decryptError), failureReason = enumOrNull<FailureReason>(failureReason),
        deliveredTo = deliveredTo.toSet(), readBy = readBy.toSet(), systemEvent = sys,
        pendingEnvelopeType = pendingEnvelopeType, pendingEnvelopeBody = pendingEnvelopeBody,
    )
}

private const val SYSTEM_NEW_DEVICE = "new_device"
private const val SYSTEM_NEW_DEVICE_BLOCKED = "new_device_blocked"
private const val SYSTEM_IDENTITY_CHANGED = "identity_changed"
private const val SYSTEM_MEMBER_ADDED = "member_added"
private const val SYSTEM_MEMBER_REMOVED = "member_removed"

// --- outbox ---------------------------------------------------------------------------------------------------------

internal fun OutboxEntry.toEntity() = OutboxEntity(
    messageId = messageId, conversationId = conversationId, kind = kind, targetMessageId = targetMessageId, text = text,
    replyTo = replyTo,
    attachments = FamilyChatJson.encodeToString(
        outboxAttachmentsSerializer,
        attachments.map {
            OutboxAttachmentJson(
                it.localPath, it.mime, it.name, it.kind, it.width, it.height, it.durationMs, it.thumbnail,
                it.uploadedId, it.uploadedKey, it.uploadedSha256, it.plaintextSize,
            )
        },
    ),
    clientTs = clientTs, envelopes = envelopes?.let { FamilyChatJson.encodeToString(envelopesSerializer, it) },
    attempts = attempts, lastError = lastError, createdAt = createdAt,
)

internal fun OutboxEntity.toModel() = OutboxEntry(
    messageId = messageId, conversationId = conversationId, kind = kind, targetMessageId = targetMessageId, text = text,
    replyTo = replyTo,
    attachments = FamilyChatJson.decodeFromString(outboxAttachmentsSerializer, attachments).map {
        OutboxAttachment(
            it.localPath, it.mime, it.name, it.kind, it.width, it.height, it.durationMs, it.thumbnail,
            it.uploadedId, it.uploadedKey, it.uploadedSha256, it.plaintextSize,
        )
    },
    clientTs = clientTs, envelopes = envelopes?.let { FamilyChatJson.decodeFromString(envelopesSerializer, it) },
    attempts = attempts, lastError = lastError, createdAt = createdAt,
)
