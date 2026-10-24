package se.familychat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object ContentType {
    const val TEXT = "text"
    const val EDIT = "edit"
    const val DELETE = "delete"
}

object AttachmentKind {
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val VOICE = "voice"
    const val FILE = "file"
}

/**
 * Reference to an encrypted attachment, carried inside the Signal-encrypted [Content]. The server only ever sees
 * the ciphertext blob [id] points to.
 */
@Serializable
data class AttachmentRef(
    /** Server attachment id (from `POST /attachments`). */
    val id: String,
    /** base64 of the raw 32-byte Tink streaming key, fresh per file. */
    val key: String,
    /** hex SHA-256 of the uploaded ciphertext. */
    val sha256: String,
    /** Size of the plaintext in bytes. */
    val size: Long,
    val mime: String,
    val name: String? = null,
    /** [AttachmentKind] constant. */
    val kind: String = AttachmentKind.FILE,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** base64 of a small JPEG preview, optional. */
    val thumbnail: String? = null,
)

/**
 * The plaintext of a message (before libsignal encryption): versioned JSON, padded to a multiple of
 * [Padding.BLOCK_SIZE] bytes by [ContentCodec].
 *
 * The content is bound to its context, because the server controls every outer field: [conversationId] and
 * [messageId] repeat the conversation and message id of the envelope, and edits and deletes name the message they change
 * in [targetMessageId]. The receiver rejects the message when they differ from the event it arrived in.
 *
 * `type = "edit"`: [text] is the new text. `type = "delete"`: only the binding fields are set. [replyTo] is the id of
 * the message this one replies to.
 */
@Serializable
data class Content(
    val v: Int = VERSION,
    val type: String = ContentType.TEXT,
    val text: String? = null,
    val attachments: List<AttachmentRef> = emptyList(),
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("target_message_id") val targetMessageId: String? = null,
) {
    companion object {
        const val VERSION = 2
    }
}

/** Encodes [Content] to the padded plaintext handed to the Signal cipher and back. */
object ContentCodec {
    fun encode(content: Content): ByteArray =
        Padding.pad(FamilyChatJson.encodeToString(Content.serializer(), content).toByteArray(Charsets.UTF_8))

    /**
     * @throws InvalidPaddingException on bad padding
     * @throws kotlinx.serialization.SerializationException if the JSON is malformed
     * @throws IllegalArgumentException on an unsupported version, an unknown type, or missing/inconsistent binding fields
     */
    fun decode(padded: ByteArray): Content {
        val json = Padding.unpad(padded).toString(Charsets.UTF_8)
        val content = FamilyChatJson.decodeFromString(Content.serializer(), json)
        require(content.v == Content.VERSION) { "unsupported content version ${content.v}" }
        require(!content.conversationId.isNullOrEmpty()) { "content has no conversation_id" }
        require(!content.messageId.isNullOrEmpty()) { "content has no message_id" }
        when (content.type) {
            ContentType.TEXT -> require(content.targetMessageId == null) { "a message has no target" }
            ContentType.EDIT -> require(!content.targetMessageId.isNullOrEmpty()) { "edit without target_message_id" }
            ContentType.DELETE -> {
                require(!content.targetMessageId.isNullOrEmpty()) { "delete without target_message_id" }
                require(content.text == null && content.attachments.isEmpty()) { "a delete carries no content" }
            }
            else -> throw IllegalArgumentException("unknown content type '${content.type}'")
        }
        return content
    }
}
