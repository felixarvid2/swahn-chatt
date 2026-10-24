package se.familychat.app.ui.model

import androidx.compose.runtime.Immutable

/** A person or group picture: the server's avatar attachment if there is one, else initials. */
@Immutable
data class AvatarUi(val name: String, val attachmentId: String? = null)

enum class PreviewKind { NONE, TEXT, PHOTO, VIDEO, VOICE, FILE, DELETED, UNDECRYPTABLE }

enum class DeliveryUi { NONE, PENDING, SENT, DELIVERED, READ, FAILED }

/** One line of the conversation list. */
@Immutable
data class ConversationItemUi(
    val id: String,
    val title: String,
    val isGroup: Boolean,
    val avatar: AvatarUi,
    val previewKind: PreviewKind,
    val previewText: String?,
    /** Name shown before the text in groups ("Anna: Hej"), "Du" is added by the screen for own messages. */
    val previewSender: String?,
    val previewOwn: Boolean,
    val previewDelivery: DeliveryUi,
    val lastActivity: Long?,
    val unread: Int,
    val typing: Boolean = false,
)

enum class AttachmentKindUi { IMAGE, VIDEO, VOICE, FILE }

/** What the user can do with an attachment right now. */
sealed interface TransferUi {
    /** Not on this device yet (received), tap to download. */
    data object Remote : TransferUi

    /** Being downloaded; [progress] 0..1, or null if the size is unknown. */
    data class Downloading(val progress: Float?) : TransferUi

    /** Downloading failed; tap to retry. */
    data object Failed : TransferUi

    /** Decrypted and validated: safe to render at [path]. */
    data class Ready(val path: String) : TransferUi

    /** The file is not what the message claimed (checked after decryption); never rendered. */
    data object Invalid : TransferUi

    /** Outgoing: still waiting to be uploaded with the message. */
    data object Uploading : TransferUi
}

@Immutable
class AttachmentUi(
    val id: String,
    val kind: AttachmentKindUi,
    val mime: String,
    val name: String?,
    val size: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    /** Decoded-on-demand JPEG thumbnail from the encrypted message. */
    val thumbnail: ByteArray?,
    val transfer: TransferUi,
)

@Immutable
data class ReplyUi(
    val messageId: String,
    /** null: the reply is to one of our own messages ("You"). */
    val senderName: String?,
    val text: String?,
    val attachmentKind: AttachmentKindUi?,
    val deleted: Boolean,
)

enum class UndecryptableUi { CORRUPT, RETRYABLE_IDENTITY, OTHER }

@Immutable
data class MessageUi(
    val id: String,
    val outgoing: Boolean,
    /** Shown above incoming messages in groups; null otherwise. */
    val senderName: String?,
    val senderAvatar: AvatarUi?,
    val text: String?,
    val reply: ReplyUi?,
    val attachments: List<AttachmentUi>,
    val time: Long,
    val delivery: DeliveryUi,
    val edited: Boolean,
    val deleted: Boolean,
    val undecryptable: UndecryptableUi?,
    val failed: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

enum class SystemKind { NEW_DEVICE, IDENTITY_CHANGED }

sealed interface ChatRow {
    val key: String

    data class DateHeader(val dayStart: Long) : ChatRow {
        override val key get() = "d$dayStart"
    }

    data class Message(val message: MessageUi, val firstOfRun: Boolean) : ChatRow {
        override val key get() = message.id
    }

    data class System(
        val id: String,
        val kind: SystemKind,
        val userId: String,
        val personName: String,
        val deviceName: String?,
        val time: Long,
    ) : ChatRow {
        override val key get() = id
    }
}

@Immutable
data class MemberUi(
    val userId: String,
    val name: String,
    val username: String,
    val avatar: AvatarUi,
    val isAdmin: Boolean,
    val isMe: Boolean,
)

@Immutable
data class UserUi(val id: String, val name: String, val username: String, val avatar: AvatarUi)
