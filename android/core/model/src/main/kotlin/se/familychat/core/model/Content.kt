package se.familychat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object ContentType {
    const val TEXT = "text"
    const val EDIT = "edit"
    const val DELETE = "delete"

    /** A reaction to a message (sent as message kind `reaction`). */
    const val REACTION = "reaction"

    /** A change of the conversation's disappearing-messages timer (sent as an ordinary `message`). */
    const val TIMER = "timer"

    /** One step of a 1:1 call's signalling (sent as message kind `call`, never stored; see [CallSignal]). */
    const val CALL = "call"

    /** The types this app version understands; anything else is well-formed content from a newer app (see [ContentCodec]). */
    val KNOWN: Set<String> = setOf(TEXT, EDIT, DELETE, REACTION, TIMER, CALL)
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
 * the message this one replies to. `type = "reaction"`: [targetMessageId] is the message reacted to, [emoji] the reaction,
 * [remove] true when the reaction is taken back. `type = "timer"`: [seconds] is the new disappearing-messages timer (0: off).
 * `type = "call"`: [call] is the signalling step.
 *
 * Every field added after version 2 shipped is optional and omitted when null, so older apps ignore it: [expiresIn]
 * (seconds a text message lives on the receiver's phone once displayed), [forwarded].
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
    val emoji: String? = null,
    val remove: Boolean? = null,
    val seconds: Long? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val forwarded: Boolean? = null,
    @SerialName("call") val call: CallSignal? = null,
) {
    /** True for a type this app version knows how to apply. */
    val isKnownType: Boolean get() = type in ContentType.KNOWN

    companion object {
        const val VERSION = 2

        /** Shortest and longest disappearing-messages timer, the server's own limits (docs/API.md). */
        const val MIN_TIMER_SECONDS = 300L
        const val MAX_TIMER_SECONDS = 31_536_000L

        /** Largest SDP and ICE candidate a call signal may carry, and most candidates per ICE signal (docs/API.md). */
        const val MAX_SDP_BYTES = 32 * 1024
        const val MAX_CANDIDATE_BYTES = 1024
        const val MAX_CANDIDATES = 50

        /** 0 is "off". */
        fun isValidTimer(seconds: Long?): Boolean = seconds != null && (seconds == 0L || seconds in MIN_TIMER_SECONDS..MAX_TIMER_SECONDS)
    }
}

/** Encodes [Content] to the padded plaintext handed to the Signal cipher and back. */
object ContentCodec {
    fun encode(content: Content): ByteArray =
        Padding.pad(FamilyChatJson.encodeToString(Content.serializer(), content).toByteArray(Charsets.UTF_8))

    /**
     * @throws InvalidPaddingException on bad padding
     * @throws kotlinx.serialization.SerializationException if the JSON is malformed
     * @throws IllegalArgumentException on an unsupported version, malformed content of a known type, or missing binding fields
     *
     * Content of a type this version does not know (a newer app's) is returned as it is when it is well-formed: version 2
     * and the binding fields present. It is the caller's job to look at [Content.isKnownType] and to check the binding to
     * the event; nothing about an unknown type is interpreted here.
     */
    fun decode(padded: ByteArray): Content {
        val json = Padding.unpad(padded).toString(Charsets.UTF_8)
        val content = FamilyChatJson.decodeFromString(Content.serializer(), json)
        require(content.v == Content.VERSION) { "unsupported content version ${content.v}" }
        require(content.type.isNotEmpty()) { "content has no type" }
        require(!content.conversationId.isNullOrEmpty()) { "content has no conversation_id" }
        require(!content.messageId.isNullOrEmpty()) { "content has no message_id" }
        when (content.type) {
            ContentType.TEXT -> require(content.targetMessageId == null) { "a message has no target" }
            ContentType.EDIT -> require(!content.targetMessageId.isNullOrEmpty()) { "edit without target_message_id" }
            ContentType.DELETE -> {
                require(!content.targetMessageId.isNullOrEmpty()) { "delete without target_message_id" }
                require(content.text == null && content.attachments.isEmpty()) { "a delete carries no content" }
            }
            ContentType.REACTION -> {
                require(!content.targetMessageId.isNullOrEmpty()) { "reaction without target_message_id" }
                require(Emoji.isValid(content.emoji)) { "reaction without a single valid emoji" }
                require(content.text == null && content.attachments.isEmpty()) { "a reaction carries no content" }
            }
            ContentType.TIMER -> {
                require(Content.isValidTimer(content.seconds)) { "timer without a valid seconds value" }
                require(content.targetMessageId == null) { "a timer has no target" }
            }
            ContentType.CALL -> requireValidCall(content)
            else -> Unit // a newer app's type: well-formed and bound, nothing more is known about it
        }
        return content
    }
}

private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** The rules for a `call` content: a signal bound to nothing but its call, with DTLS-SRTP kept in every SDP. */
private fun requireValidCall(content: Content) {
    require(content.targetMessageId == null) { "a call signal has no target" }
    require(content.text == null && content.attachments.isEmpty()) { "a call signal carries no content" }
    val call = requireNotNull(content.call) { "call content without call" }
    require(UUID_PATTERN.matches(call.callId)) { "call_id is not a UUID" }
    require(call.action in CallAction.ALL) { "unknown call action" }
    when (call.action) {
        CallAction.OFFER, CallAction.ANSWER -> {
            val sdp = call.sdp
            require(!sdp.isNullOrBlank()) { "${call.action} without sdp" }
            require(sdp.toByteArray(Charsets.UTF_8).size <= Content.MAX_SDP_BYTES) { "sdp too large" }
            require("a=fingerprint:" in sdp) { "sdp without a DTLS fingerprint" }
        }
        CallAction.ICE -> {
            val candidates = call.candidates
            require(candidates != null && candidates.size in 1..Content.MAX_CANDIDATES) { "ice needs 1..${Content.MAX_CANDIDATES} candidates" }
            require(candidates.all { it.candidate.toByteArray(Charsets.UTF_8).size <= Content.MAX_CANDIDATE_BYTES }) { "ice candidate too large" }
        }
    }
}
