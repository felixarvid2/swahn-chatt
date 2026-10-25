package se.swahnchat.core.model

import kotlinx.serialization.json.decodeFromJsonElement

/** A sync [Event] with its `data` decoded according to its `type`. */
sealed interface ParsedEvent {
    val event: Event

    /** A message, edit or delete addressed to this device (the envelope may be null for deletes). */
    data class MessageReceived(override val event: Event, val message: Message) : ParsedEvent
    data class Receipt(override val event: Event, val receipt: ReceiptData) : ParsedEvent
    data class ConversationUpdated(override val event: Event, val conversation: Conversation) : ParsedEvent
    data class ConversationRemoved(override val event: Event, val conversationId: String) : ParsedEvent
    data class DevicesChanged(override val event: Event, val userId: String) : ParsedEvent
    data class UserUpdated(override val event: Event, val user: User) : ParsedEvent
    data class UserRemoved(override val event: Event, val userId: String) : ParsedEvent

    /** An event type this client does not know (newer server) or whose data could not be decoded. */
    data class Unknown(override val event: Event, val reason: String) : ParsedEvent
}

/** Decodes [Event.data]; never throws (malformed data becomes [ParsedEvent.Unknown]). */
fun parseEvent(event: Event): ParsedEvent {
    val data = event.data ?: return ParsedEvent.Unknown(event, "no data")
    return try {
        when (event.type) {
            EventType.MESSAGE ->
                ParsedEvent.MessageReceived(event, SwahnChatJson.decodeFromJsonElement<Message>(data))
            EventType.RECEIPT ->
                ParsedEvent.Receipt(event, SwahnChatJson.decodeFromJsonElement<ReceiptData>(data))
            EventType.CONVERSATION_UPDATED ->
                ParsedEvent.ConversationUpdated(event, SwahnChatJson.decodeFromJsonElement<Conversation>(data))
            EventType.CONVERSATION_REMOVED ->
                ParsedEvent.ConversationRemoved(
                    event, SwahnChatJson.decodeFromJsonElement<ConversationRemovedData>(data).conversationId,
                )
            EventType.DEVICES_CHANGED ->
                ParsedEvent.DevicesChanged(event, SwahnChatJson.decodeFromJsonElement<UserIdData>(data).userId)
            EventType.USER_UPDATED ->
                ParsedEvent.UserUpdated(event, SwahnChatJson.decodeFromJsonElement<User>(data))
            EventType.USER_REMOVED ->
                ParsedEvent.UserRemoved(event, SwahnChatJson.decodeFromJsonElement<UserIdData>(data).userId)
            else -> ParsedEvent.Unknown(event, "unknown event type ${event.type}")
        }
    } catch (e: Exception) {
        ParsedEvent.Unknown(event, "bad ${event.type} data: ${e.message}")
    }
}
