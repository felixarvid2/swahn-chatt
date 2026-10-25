package se.swahnchat.app.call

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import se.swahnchat.core.client.call.MediaConnectionState
import se.swahnchat.core.client.call.MediaSession
import se.swahnchat.core.model.IceCandidate
import org.webrtc.IceCandidate as RtcIceCandidate

/**
 * One call's [PeerConnection] with a single audio track. Built by [WebRtcMediaSessionFactory].
 *
 * Threading: WebRTC invokes the observer (and so the [MediaSession.Listener]) on its signalling thread; the suspend
 * functions resume on that thread too. [lock] guards the candidate queue and the closed flag only; the PeerConnection is never
 * called while it is held (close() blocks on the signalling thread, which fires the observer). Remote candidates that arrive before the remote description is set (the signalling path does not order them) are held back
 * and added right after it.
 *
 * Neither the SDP nor candidates are ever logged.
 */
internal class WebRtcMediaSession(
    factory: PeerConnectionFactory,
    config: PeerConnection.RTCConfiguration,
    private val listener: MediaSession.Listener,
) : MediaSession {
    private val lock = Any()
    @Volatile private var closed = false
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<RtcIceCandidate>()

    private val audioSource: AudioSource = factory.createAudioSource(MediaConstraints())
    private val audioTrack: AudioTrack = factory.createAudioTrack(TRACK_ID, audioSource)
    private val peerConnection: PeerConnection

    init {
        peerConnection = factory.createPeerConnection(config, Observer())
            ?: run {
                audioTrack.dispose()
                audioSource.dispose()
                throw WebRtcException("PeerConnection could not be created")
            }
        peerConnection.addTrack(audioTrack, listOf(STREAM_ID))
    }

    override suspend fun createOffer(): String {
        val offer = createDescription { peerConnection.createOffer(it, MediaConstraints()) }
        setDescription { peerConnection.setLocalDescription(it, offer) }
        return offer.description
    }

    override suspend fun acceptOffer(sdp: String): String {
        setDescription { peerConnection.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, sdp)) }
        onRemoteDescriptionSet()
        val answer = createDescription { peerConnection.createAnswer(it, MediaConstraints()) }
        setDescription { peerConnection.setLocalDescription(it, answer) }
        return answer.description
    }

    override suspend fun acceptAnswer(sdp: String) {
        setDescription { peerConnection.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
        onRemoteDescriptionSet()
    }

    override fun addRemoteCandidates(candidates: List<IceCandidate>) {
        val converted = candidates.map { RtcIceCandidate(it.sdpMid, it.sdpMLineIndex, it.candidate) }
        val ready = synchronized(lock) {
            if (closed) return
            if (!remoteDescriptionSet) pendingRemoteCandidates += converted
            remoteDescriptionSet
        }
        if (ready) converted.forEach { peerConnection.addIceCandidate(it) }
    }

    override fun setMuted(muted: Boolean) {
        if (!closed) audioTrack.setEnabled(!muted)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pendingRemoteCandidates.clear()
        }
        // Outside the lock: close() waits for WebRTC's signalling thread, which fires the observer meanwhile.
        peerConnection.close()
        peerConnection.dispose()
        audioTrack.dispose()
        audioSource.dispose()
    }

    private fun onRemoteDescriptionSet() {
        val pending = synchronized(lock) {
            if (closed) return
            remoteDescriptionSet = true
            pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() }
        }
        pending.forEach { peerConnection.addIceCandidate(it) }
    }

    private suspend fun createDescription(start: (SdpResultObserver) -> Unit): SessionDescription =
        suspendCancellableCoroutine<SessionDescription?> { continuation -> start(SdpResultObserver(continuation)) }
            ?: throw WebRtcException("no session description")

    private suspend fun setDescription(start: (SdpResultObserver) -> Unit) {
        suspendCancellableCoroutine<SessionDescription?> { continuation -> start(SdpResultObserver(continuation)) }
    }

    private inner class Observer : PeerObserverAdapter() {
        override fun onIceCandidate(candidate: RtcIceCandidate) {
            if (closed) return
            listener.onLocalCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (closed) return
            listener.onConnectionState(
                when (state) {
                    PeerConnection.PeerConnectionState.NEW, PeerConnection.PeerConnectionState.CONNECTING -> MediaConnectionState.CONNECTING
                    PeerConnection.PeerConnectionState.CONNECTED -> MediaConnectionState.CONNECTED
                    PeerConnection.PeerConnectionState.DISCONNECTED -> MediaConnectionState.DISCONNECTED
                    PeerConnection.PeerConnectionState.FAILED -> MediaConnectionState.FAILED
                    PeerConnection.PeerConnectionState.CLOSED -> MediaConnectionState.CLOSED
                },
            )
        }
    }

    private companion object {
        const val TRACK_ID = "fc-audio"
        const val STREAM_ID = "fc-stream"
    }
}
