package se.swahnchat.app.ui.model

import androidx.compose.runtime.Immutable

/** A person or group picture: the server's avatar attachment if there is one, else initials. */
@Immutable
data class AvatarUi(val name: String, val attachmentId: String? = null)

enum class PreviewKind { NONE, TEXT, PHOTO, VIDEO, VOICE, FILE, LOCATION, DELETED, UNDECRYPTABLE }

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
    /** A message that mentions this account is unread: the row shows an "@". */
    val mentioned: Boolean = false,
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

    /** Merged in from a history export without the file: there is nothing to download (it has no key). */
    data object Unavailable : TransferUi
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

/** One person's reaction, for the list of who reacted. */
@Immutable
data class ReactorUi(val userId: String, val name: String, val avatar: AvatarUi, val isMe: Boolean)

/** All reactions with one emoji under a message: [reactors] in the order they reacted. */
@Immutable
data class ReactionUi(val emoji: String, val reactors: List<ReactorUi>) {
    val count: Int get() = reactors.size
    val mine: Boolean get() = reactors.any { it.isMe }
}

/** A person named with `@` in a message. */
@Immutable
data class MentionUi(val userId: String, val name: String, val isMe: Boolean)

/** One answer of a poll with what has been voted for it. */
@Immutable
data class PollOptionUi(
    val id: Int,
    val text: String,
    /** The people who chose it, in the order they voted. */
    val voters: List<ReactorUi>,
    val mine: Boolean,
) {
    val count: Int get() = voters.size
}

/** A poll as the bubble shows it; the question is the message text. */
@Immutable
data class PollUi(
    val options: List<PollOptionUi>,
    val multiple: Boolean,
    val closed: Boolean,
    /** People who voted at all (in a poll with several choices this is less than the sum of the counts). */
    val voterCount: Int,
    /** The vote can be changed: sent, not deleted, not closed. */
    val canVote: Boolean,
    /** Only the creator, while it is open. */
    val canClose: Boolean,
) {
    val myChoice: List<Int> get() = options.filter { it.mine }.map { it.id }
}

/** A shared position as the bubble shows it (offline: coordinates and accuracy, never a map from the internet). */
@Immutable
data class LocationUi(val lat: Double, val lon: Double, val accuracyM: Double, val label: String?)

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
    /** Reactions grouped by emoji, the most used first. */
    val reactions: List<ReactionUi> = emptyList(),
    /** The emoji you reacted with, if any (tapping it again takes it back). */
    val myReaction: String? = null,
    /** A message that has left the outbox and is not deleted: it can be reacted to and forwarded. */
    val canReact: Boolean = false,
    val canForward: Boolean = false,
    /** "Vidarebefordrat": sent on from another conversation (set by the sender inside the encrypted content). */
    val forwarded: Boolean = false,
    /** "Importerad": merged in from an exported history file. */
    val imported: Boolean = false,
    /** Seconds this message lives once displayed (the sender's timer), null: it stays. */
    val expiresIn: Long? = null,
    /** People named with `@` in the text (only members, as the receiver checked). */
    val mentions: List<MentionUi> = emptyList(),
    val poll: PollUi? = null,
    /** Starred on this phone (local only). */
    val starred: Boolean = false,
    /** A starred message can be marked: it is not deleted and could be decrypted. */
    val canStar: Boolean = false,
    /** Written for later and not sent yet: shown dimmed with the time it will go out (epoch ms). */
    val scheduledAt: Long? = null,
    /** Set when the message is a shared position. */
    val location: LocationUi? = null,
)

enum class SystemKind {
    NEW_DEVICE,

    /** A new device next to verified ones: nothing is sent to or accepted from it until the user approves it. */
    NEW_DEVICE_BLOCKED,
    IDENTITY_CHANGED,
    MEMBER_ADDED,
    MEMBER_REMOVED,

    /** [ChatRow.System.value] is the new timer in seconds (0: off). */
    TIMER_CHANGED,

    /** Content of a newer app version was ignored. */
    UPDATE_REQUIRED,

    /** A call that connected; [ChatRow.System.value] is its length in seconds. */
    CALL_COMPLETED,
    CALL_MISSED,
    CALL_NO_ANSWER,
    CALL_BUSY,
    CALL_REJECTED,
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
        /** The number a notice is about: the timer in seconds of [SystemKind.TIMER_CHANGED]. */
        val value: Long = 0,
        /** A call notice is about a video call. */
        val video: Boolean = false,
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
    val snippet: se.swahnchat.app.data.Snippet,
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
