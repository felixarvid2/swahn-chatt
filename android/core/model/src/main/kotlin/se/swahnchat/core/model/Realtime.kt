package se.swahnchat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Frames the server sends on `/ws`. */
sealed interface ServerFrame {
    /** Sent right after connecting; [cursor] is the latest event seq for this device. */
    data class Hello(val cursor: Long) : ServerFrame

    /** New events exist; call `GET /sync`. */
    data class Sync(val cursor: Long) : ServerFrame
    data class Typing(val conversationId: String, val userId: String, val state: TypingState) : ServerFrame
    data object Pong : ServerFrame

    /** A frame type this client does not know; ignore it. */
    data class Unknown(val raw: String) : ServerFrame

    companion object {
        /** Parses one text frame; returns null if it is not a JSON object with a `type`. */
        fun parse(text: String): ServerFrame? {
            val obj = try {
                SwahnChatJson.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                return null
            }
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return null
            return try {
                when (type) {
                    "hello" -> Hello(obj.long("cursor"))
                    "sync" -> Sync(obj.long("cursor"))
                    "typing" -> Typing(
                        conversationId = obj.string("conversation_id"),
                        userId = obj.string("user_id"),
                        state = TypingState.fromWire(obj.string("state")),
                    )
                    "pong" -> Pong
                    else -> Unknown(text)
                }
            } catch (e: Exception) {
                Unknown(text)
            }
        }

        private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.longOrNull ?: error("bad $key")
        private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    }
}

enum class TypingState(val wire: String) {
    START("start"), STOP("stop");

    companion object {
        fun fromWire(value: String): TypingState = entries.firstOrNull { it.wire == value } ?: STOP
    }
}

/** Frames the client sends on `/ws`. */
sealed interface ClientFrame {
    fun toJson(): String

    @Serializable
    data class Typing(
        @SerialName("conversation_id") val conversationId: String,
        val state: String,
        val type: String = "typing",
    ) : ClientFrame {
        override fun toJson(): String = SwahnChatJson.encodeToString(serializer(), this)
    }

    data object Ping : ClientFrame {
        override fun toJson(): String = """{"type":"ping"}"""
    }
}
