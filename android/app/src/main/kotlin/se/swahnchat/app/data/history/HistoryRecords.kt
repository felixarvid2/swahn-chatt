package se.swahnchat.app.data.history

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The records inside the archive of a history file (JSON; `manifest.json` is one object, the `.jsonl` entries have one record
// per line). Nothing here names a key, token, session, device or server attachment: see HistoryExporter.

internal object HistoryEntries {
    const val MANIFEST = "manifest.json"
    const val CONVERSATIONS = "conversations.jsonl"
    const val MESSAGES = "messages.jsonl"
    const val MEDIA_PREFIX = "media/"
}

@Serializable
data class HistoryManifest(
    val format: String = FORMAT,
    val version: Int = HistoryFormat.VERSION,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("app_version") val appVersion: String = "",
    /** The account the history belongs to; an import is refused for any other. */
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("includes_media") val includesMedia: Boolean,
    val conversations: Int = 0,
    val messages: Int = 0,
    /** Messages left out because they disappear (they have a timer), failed, or are still unsent. */
    @SerialName("left_out") val leftOut: Int = 0,
) {
    companion object {
        // Protocol constant from the FamilyChat era; must not change (compatibility).
        const val FORMAT = "familychat-history"
    }
}

@Serializable
data class HistoryMember(@SerialName("user_id") val userId: String, @SerialName("display_name") val displayName: String = "")

@Serializable
data class HistoryConversation(
    val id: String,
    val type: String = "direct",
    val name: String? = null,
    val members: List<HistoryMember> = emptyList(),
)

@Serializable
data class HistoryAttachment(
    val name: String? = null,
    val mime: String = "application/octet-stream",
    val kind: String = "file",
    val size: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val thumbnail: String? = null,
    /** Archive entry of the file (`media/<n>`), when the export included media. */
    val media: String? = null,
)

@Serializable
data class HistoryReaction(@SerialName("user_id") val userId: String, val emoji: String)

@Serializable
data class HistoryMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("client_ts") val clientTs: Long = 0,
    @SerialName("server_ts") val serverTs: Long? = null,
    val seq: Long? = null,
    val text: String? = null,
    @SerialName("reply_to") val replyTo: String? = null,
    val edited: Boolean = false,
    @SerialName("edited_at") val editedAt: Long? = null,
    val forwarded: Boolean = false,
    val attachments: List<HistoryAttachment> = emptyList(),
    val reactions: List<HistoryReaction> = emptyList(),
)
