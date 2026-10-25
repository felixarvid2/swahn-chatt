package se.swahnchat.app.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import se.swahnchat.core.client.call.MediaConnectionState
import se.swahnchat.core.client.call.MediaSession
import se.swahnchat.core.model.IceCandidate
import org.webrtc.IceCandidate as RtcIceCandidate

/**
 * One call's [PeerConnection] with an audio track and, for a video call, a video transceiver: sending when the phone has a
 * camera and the permission ([camera]), else receive-only so the other side can still be seen. Built by
 * [WebRtcMediaSessionFactory]. The video members are the app-only API the call screen uses ([localVideoTrack],
 * [remoteVideoTrack], [setCameraEnabled], [switchCamera]); the core only knows [MediaSession].
 *
 * Threading: WebRTC invokes the observer (and so the [MediaSession.Listener]) on its signalling thread; the suspend
 * functions resume on that thread too. [lock] guards the candidate queue and the closed flag only; the PeerConnection is never
 * called while it is held (close() blocks on the signalling thread, which fires the observer). Remote candidates that arrive before the remote description is set (the signalling path does not order them) are held back
 * and added right after it.
 *
 * Neither the SDP nor candidates are ever logged.
 */
class WebRtcMediaSession internal constructor(
    factory: PeerConnectionFactory,
    config: PeerConnection.RTCConfiguration,
    private val listener: MediaSession.Listener,
    private val camera: WebRtcCamera? = null,
    video: Boolean = false,
    private val onClosed: (WebRtcMediaSession) -> Unit = {},
) : MediaSession {
    private val lock = Any()
    @Volatile private var closed = false
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<RtcIceCandidate>()

    private val audioSource: AudioSource = factory.createAudioSource(MediaConstraints())
    private val audioTrack: AudioTrack = factory.createAudioTrack(TRACK_ID, audioSource)
    private val peerConnection: PeerConnection
    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)

    /** The track of the other side's camera once it arrived; null before that and after [close]. */
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    /** The track of our own camera for the preview; null for an audio call, without a camera or without the permission. */
    val localVideoTrack: VideoTrack? get() = camera?.track.takeUnless { closed }

    /** Whether our camera is on (false without a camera). */
    val cameraEnabled: StateFlow<Boolean> = camera?.enabled ?: MutableStateFlow(false).asStateFlow()

    /** Whether the camera in use faces the person: the preview is mirrored then. */
    val cameraFront: StateFlow<Boolean> = camera?.isFront ?: MutableStateFlow(true).asStateFlow()

    init {
        peerConnection = factory.createPeerConnection(config, Observer())
            ?: run {
                camera?.close()
                audioTrack.dispose()
                audioSource.dispose()
                throw WebRtcException("PeerConnection could not be created")
            }
        peerConnection.addTrack(audioTrack, listOf(STREAM_ID))
        if (video) addVideo()
    }

    private fun addVideo() {
        if (camera != null) {
            peerConnection.addTransceiver(camera.track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(STREAM_ID)))
            camera.start()
        } else {
            peerConnection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
        }
    }

    /** Camera on or off during the call; off stops the capture, so the camera is free and nothing but black is sent. */
    fun setCameraEnabled(on: Boolean) {
        if (!closed) camera?.setEnabled(on)
    }

    fun switchCamera() {
        if (!closed) camera?.switchCamera()
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
        _remoteVideoTrack.value = null // before the tracks are disposed: the screen drops its renderers on this
        onClosed(this)
        // Outside the lock: close() waits for WebRTC's signalling thread, which fires the observer meanwhile.
        peerConnection.close()
        peerConnection.dispose()
        audioTrack.dispose()
        audioSource.dispose()
        camera?.close() // after the connection: it no longer reads the track
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

        override fun onTrack(transceiver: RtpTransceiver) {
            if (closed) return
            (transceiver.receiver.track() as? VideoTrack)?.let { _remoteVideoTrack.value = it }
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
