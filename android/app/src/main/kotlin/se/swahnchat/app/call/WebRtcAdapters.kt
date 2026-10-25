package se.swahnchat.app.call

import kotlinx.coroutines.CancellableContinuation
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [PeerConnection.Observer] with every callback a no-op, so that a session only overrides what it needs (the interface has
 * ten abstract methods, most of which are irrelevant to an audio-only unified-plan call). Callbacks arrive on WebRTC's
 * signalling thread.
 */
@Suppress("EmptyFunctionBlock")
internal open class PeerObserverAdapter : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}

    override fun onIceConnectionReceivingChange(receiving: Boolean) {}

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

    override fun onIceCandidate(candidate: IceCandidate) {}

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

    override fun onAddStream(stream: MediaStream) {}

    override fun onRemoveStream(stream: MediaStream) {}

    override fun onDataChannel(channel: DataChannel) {}

    override fun onRenegotiationNeeded() {}
}

/** Resumes [continuation] with the result of one `createOffer`/`createAnswer` (a description) or `set*Description` (null). */
internal class SdpResultObserver(private val continuation: CancellableContinuation<SessionDescription?>) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)

    override fun onSetSuccess() = continuation.resume(null)

    override fun onCreateFailure(error: String?) = continuation.resumeWithException(WebRtcException("create failed: $error"))

    override fun onSetFailure(error: String?) = continuation.resumeWithException(WebRtcException("set failed: $error"))
}

/** A WebRTC operation failed; the message is WebRTC's own error text (never SDP or candidates). */
internal class WebRtcException(message: String) : Exception(message)
