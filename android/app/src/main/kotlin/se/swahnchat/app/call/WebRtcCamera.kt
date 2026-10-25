package se.swahnchat.app.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * The local camera of one video call: capturer, texture helper, source and the track that is sent. Created only when the
 * phone has a camera and the CAMERA permission is granted ([create] returns null otherwise, and the call goes on without
 * sending video). The capture runs at 1280x720, 30 fps at most; WebRTC lowers both when the connection or the CPU cannot keep
 * up. Never touches the shared [EglBase] other than to read its context.
 */
class WebRtcCamera private constructor(
    private val capturer: CameraVideoCapturer,
    private val helper: SurfaceTextureHelper,
    private val source: VideoSource,
    val track: VideoTrack,
    front: Boolean,
) {
    private val lock = Any()
    private var closed = false
    private var capturing = false

    private val _front = MutableStateFlow(front)

    /** Whether the camera in use faces the person (the preview is mirrored then). */
    val isFront: StateFlow<Boolean> = _front.asStateFlow()

    private val _enabled = MutableStateFlow(true)

    /** Whether the camera is on: when off, nothing is captured and the track sends black frames. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Starts capturing; the camera stays off if it cannot be opened (the call carries on without video). */
    fun start() = setEnabled(true)

    fun setEnabled(on: Boolean) {
        synchronized(lock) {
            if (closed) return
            _enabled.value = on
            track.setEnabled(on)
            if (on && !capturing) {
                capturing = true
                capturer.startCapture(WIDTH, HEIGHT, FPS)
            } else if (!on && capturing) {
                capturing = false
                stopCapture() // frees the camera: the system's camera indicator goes off
            }
        }
    }

    /** Front to back camera and the other way round; ignored while the camera is off. */
    fun switchCamera() {
        synchronized(lock) {
            if (closed || !capturing) return
            capturer.switchCamera(
                object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                        _front.value = isFrontCamera
                    }

                    override fun onCameraSwitchError(errorDescription: String?) {
                        Log.w(TAG, "camera switch: $errorDescription")
                    }
                },
            )
        }
    }

    private fun stopCapture() {
        try {
            capturer.stopCapture() // waits for the camera thread
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Stops the capture and frees everything; safe to call more than once. The track must no longer be shown by then. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (capturing) {
                capturing = false
                stopCapture()
            }
        }
        capturer.dispose()
        track.dispose()
        source.dispose()
        helper.dispose()
    }

    companion object {
        private const val TAG = "WebRtcCamera"
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val FPS = 30

        /** The camera for a video call, front camera first; null without CAMERA permission or without any camera. */
        fun create(context: Context, factory: PeerConnectionFactory, egl: EglBase): WebRtcCamera? {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return null
            val enumerator = Camera2Enumerator(context)
            val names = enumerator.deviceNames
            val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: return null
            val capturer = enumerator.createCapturer(name, null) ?: return null
            val helper = SurfaceTextureHelper.create("fc-capture", egl.eglBaseContext)
            val source = factory.createVideoSource(false)
            capturer.initialize(helper, context, source.capturerObserver)
            return WebRtcCamera(capturer, helper, source, factory.createVideoTrack(TRACK_ID, source), enumerator.isFrontFacing(name))
        }

        private const val TRACK_ID = "fc-video"
    }
}
