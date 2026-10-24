package se.familychat.core.model

import kotlinx.serialization.json.Json

/**
 * The one [Json] configuration used for every API body, WebSocket frame and encrypted [Content].
 *
 * - unknown keys are ignored so a newer server can add fields,
 * - `null` properties are omitted when encoding (the API treats an omitted optional field as absent),
 * - defaults are encoded so fixed fields such as `Content.v` always appear on the wire.
 */
val FamilyChatJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}
