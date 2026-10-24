package se.familychat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object ContentType {
    const val TEXT = "text"
    const val EDIT = "edit"
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
 * For `type = "edit"` the message being edited is named by the outer `target_message_id`; [text] is the new text.
 * [replyTo] is the id of the message this one replies to.
 */
@Serializable
data class Content(
    val v: Int = VERSION,
    val type: String = ContentType.TEXT,
    val text: String? = null,
    val attachments: List<AttachmentRef> = emptyList(),
    @SerialName("reply_to") val replyTo: String? = null,
) {
    companion object {
        const val VERSION = 1
    }
}

/** Encodes [Content] to the padded plaintext handed to the Signal cipher and back. */
object ContentCodec {
    fun encode(content: Content): ByteArray =
        Padding.pad(FamilyChatJson.encodeToString(Content.serializer(), content).toByteArray(Charsets.UTF_8))

    /**
     * @throws InvalidPaddingException on bad padding
     * @throws kotlinx.serialization.SerializationException if the JSON is malformed
     * @throws IllegalArgumentException on an unsupported version
     */
    fun decode(padded: ByteArray): Content {
        val json = Padding.unpad(padded).toString(Charsets.UTF_8)
        val content = FamilyChatJson.decodeFromString(Content.serializer(), json)
        require(content.v in 1..Content.VERSION) { "unsupported content version ${content.v}" }
        return content
    }
}
