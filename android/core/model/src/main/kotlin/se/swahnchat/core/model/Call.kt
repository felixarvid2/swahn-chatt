package se.swahnchat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a [CallSignal] does. Signals travel as encrypted [Content] of type [ContentType.CALL] (message kind `call`). */
object CallAction {
    const val OFFER = "offer"
    const val ANSWER = "answer"
    const val ICE = "ice"
    const val HANGUP = "hangup"
    const val REJECT = "reject"
    const val BUSY = "busy"

    val ALL: Set<String> = setOf(OFFER, ANSWER, ICE, HANGUP, REJECT, BUSY)
}

/**
 * One step of a 1:1 call's signalling, end-to-end encrypted like a message: the server never sees the SDP, the ICE
 * candidates or whether it is an audio or a video call. [sdp] is set for offers and answers, [candidates] for ICE.
 * [sentAt] is the sender's clock (epoch ms): the sender always sets it, it is nullable only so older signals still decode;
 * a receiver drops an offer without it or far from its own clock (a server cannot hold an offer back and replay it later).
 */
@Serializable
data class CallSignal(
    @SerialName("call_id") val callId: String,
    @SerialName("action") val action: String,
    @SerialName("video") val video: Boolean = false,
    @SerialName("sdp") val sdp: String? = null,
    @SerialName("candidates") val candidates: List<IceCandidate>? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("sent_at") val sentAt: Long? = null,
)

/** A WebRTC ICE candidate as carried in a [CallSignal]. */
@Serializable
data class IceCandidate(
    @SerialName("sdp_mid") val sdpMid: String?,
    @SerialName("sdp_m_line_index") val sdpMLineIndex: Int,
    @SerialName("candidate") val candidate: String,
)

/** `GET /calls/turn`: TURN REST credentials, or STUN-only [urls] with null [username] and [credential] (API.md). */
@Serializable
data class TurnCredentials(
    @SerialName("urls") val urls: List<String>,
    @SerialName("username") val username: String? = null,
    @SerialName("credential") val credential: String? = null,
    @SerialName("ttl") val ttl: Long = 0,
)
