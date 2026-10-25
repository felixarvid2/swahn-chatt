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
    /** Unsent composer text; when set the list shows "Utkast: ..." instead of the last message. */
    val draft: String? = null,
    /** Pinned conversations stay on top of the list (local setting). */
    val pinned: Boolean = false,
    /** Muted: no notification; the unread count is still shown. */
    val muted: Boolean = false,
    /** Archived conversations are shown in their own section at the end of the list. */
    val archived: Boolean = false,
)

/** The conversation list in two parts: the main list (pinned first) and the archived ones. */
@Immutable
data class ConversationSections(val main: List<ConversationItemUi>, val archived: List<ConversationItemUi>) {
    val archivedUnread: Int get() = archived.sumOf { it.unread }
}

/** Splits [items] (newest activity first): pinned conversations first, each group keeps its order; archived ones apart. */
fun arrangeConversations(items: List<ConversationItemUi>): ConversationSections {
    val (archived, active) = items.partition { it.archived }
    return ConversationSections(active.sortedByDescending { it.pinned }, archived)
}

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
    /** "Delete for everyone": your own message, not yet deleted. */
    val canDelete: Boolean,
    /** "Delete for me": any message that has left the outbox; it is only removed from this phone. */
    val canDeleteForMe: Boolean = false,
    /** "Message info": your own message that the server has stored (who received and read it, and when). */
    val canShowInfo: Boolean = false,
    /** Who wrote it (user id); consecutive messages of one sender form a run. */
    val senderId: String = "",
    /** The sender's display name for screen readers, also in direct conversations; null for own messages. */
    val speaker: String? = null,
)

enum class SystemKind {
    NEW_DEVICE,

    /** A new device next to verified ones: nothing is sent to or accepted from it until the user approves it. */
    NEW_DEVICE_BLOCKED,
    IDENTITY_CHANGED,
    MEMBER_ADDED,
    MEMBER_REMOVED,
}

sealed interface ChatRow {
    val key: String

    data class DateHeader(val dayStart: Long) : ChatRow {
        override val key get() = "d$dayStart"
    }

    /** [firstOfRun] is final only in lists built by [buildChatRows]; paged lists decide it per row (see [isFirstOfRun]). */
    data class Message(val message: MessageUi, val firstOfRun: Boolean = true) : ChatRow {
        override val key get() = message.id
    }

    /** "Olästa meddelanden": sits directly above the first message that was unread when the conversation was opened. */
    data object UnreadDivider : ChatRow {
        override val key get() = "unread-divider"
    }

    data class System(
        val id: String,
        val kind: SystemKind,
        val userId: String,
        val personName: String,
        val deviceName: String?,
        val time: Long,
        /** The notice is about this account itself (its own devices). */
        val own: Boolean = false,
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

/** What the header says about the other members' safety numbers. */
enum class SafetyState {
    /** Nothing to show: not everything is verified, and nothing is waiting for a decision. */
    NONE,

    /** Every device of every other member is verified. */
    VERIFIED,

    /** An identity change (or a new device next to verified ones) waits for the user's decision. */
    WARNING,
}

/** [reviewUserId] is whose safety screen to open for a [SafetyState.WARNING]. */
@Immutable
data class SafetyUi(val state: SafetyState = SafetyState.NONE, val reviewUserId: String? = null)

/** What the connection banner says; [HIDDEN] while everything works. */
enum class ConnectionUi { HIDDEN, CONNECTING, OFFLINE }

/** A search result for display. [senderName] null: own message ("Du" is added by the screen). */
@Immutable
data class SearchHitUi(
    val messageId: String,
    val conversationId: String,
    val time: Long,
    val senderName: String?,
    val snippet: se.familychat.app.data.Snippet,
)

/** The results of one conversation (global search groups by conversation, newest hit first). */
@Immutable
data class SearchGroupUi(val conversationId: String, val title: String, val avatar: AvatarUi, val hits: List<SearchHitUi>)

/** Groups [hits] (newest first) by conversation; groups are ordered by their newest hit. */
fun groupSearchHits(hits: List<SearchHitUi>, conversations: Map<String, ConversationItemUi>, unknownTitle: String): List<SearchGroupUi> =
    hits.groupBy { it.conversationId }.map { (id, list) ->
        val c = conversations[id]
        SearchGroupUi(id, c?.title ?: unknownTitle, c?.avatar ?: AvatarUi(unknownTitle), list)
    }
