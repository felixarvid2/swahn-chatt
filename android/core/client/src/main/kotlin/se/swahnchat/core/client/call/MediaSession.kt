package se.swahnchat.core.client.call

import se.swahnchat.core.model.IceCandidate

/** One ICE server for a call, from `GET /calls/turn`; [username] and [credential] are null for STUN. */
data class IceServerConfig(val urls: List<String>, val username: String? = null, val credential: String? = null)

enum class MediaConnectionState { CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/**
 * The media side of one call (WebRTC in the app, a fake in tests). Media is DTLS-SRTP end to end; the DTLS
 * fingerprints travel inside the encrypted SDP, so an implementation must never disable or weaken DTLS.
 */
interface MediaSession {
    /** Creates the local offer and sets it as the local description; returns its SDP. */
    suspend fun createOffer(): String

    /** Sets the remote offer, creates the answer and sets it locally; returns the answer's SDP. */
    suspend fun acceptOffer(sdp: String): String

    suspend fun acceptAnswer(sdp: String)

    fun addRemoteCandidates(candidates: List<IceCandidate>)

    fun setMuted(muted: Boolean)

    /** Ends the session and frees its resources; safe to call more than once. */
    fun close()

    interface Listener {
        fun onLocalCandidate(candidate: IceCandidate)

        fun onConnectionState(state: MediaConnectionState)
    }
}

fun interface MediaSessionFactory {
    fun create(iceServers: List<IceServerConfig>, relayOnly: Boolean, video: Boolean, listener: MediaSession.Listener): MediaSession
}
