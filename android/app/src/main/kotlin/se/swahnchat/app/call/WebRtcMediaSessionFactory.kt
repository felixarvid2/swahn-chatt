package se.swahnchat.app.call

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import se.swahnchat.core.client.call.IceServerConfig
import se.swahnchat.core.client.call.MediaSession
import se.swahnchat.core.client.call.MediaSessionFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [MediaSessionFactory]: WebRTC (`io.github.webrtc-sdk:android`) with DTLS-SRTP, which the library enforces and
 * this class never configures away. The native library and the one [PeerConnectionFactory] are set up lazily, once, on the
 * first call (the lazy delegate is synchronized), so the app start does not pay for it.
 *
 * One [EglBase] is shared by the video encoders and decoders and by the call screen's renderers ([eglBase]); it is never
 * released (it lives as long as the process). [current] is the media session of the call in progress, for the call screen.
 * An audio call opens no camera; turning an audio call into a video call during the call is not supported.
 */
@Singleton
class WebRtcMediaSessionFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaSessionFactory {
    /** The one EGL context of video: encoder, decoder and the call screen's renderers. Created on first use, never released. */
    val eglBase: EglBase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { EglBase.create() }

    private val _current = MutableStateFlow<WebRtcMediaSession?>(null)

    /** The session of the call in progress (set when it is created, cleared when it is closed); null between calls. */
    val current: StateFlow<WebRtcMediaSession?> = _current.asStateFlow()

    private val peerConnectionFactory: PeerConnectionFactory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        // A microphone that cannot be opened (an emulator without audio) is reported here; the connection still comes up.
        val audioDevices = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioRecordErrorCallback(
                object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                        Log.w(TAG, "audio record init: $errorMessage")
                    }

                    override fun onWebRtcAudioRecordStartError(
                        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                        errorMessage: String?,
                    ) {
                        Log.w(TAG, "audio record start: $errorCode $errorMessage")
                    }

                    override fun onWebRtcAudioRecordError(errorMessage: String?) {
                        Log.w(TAG, "audio record: $errorMessage")
                    }
                },
            )
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDevices)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    override fun create(
        iceServers: List<IceServerConfig>,
        relayOnly: Boolean,
        video: Boolean,
        listener: MediaSession.Listener,
    ): MediaSession {
        val factory = peerConnectionFactory
        val camera = if (video) WebRtcCamera.create(context, factory, eglBase) else null
        val session = try {
            WebRtcMediaSession(factory, configuration(iceServers, relayOnly), listener, camera, video) { closed ->
                _current.compareAndSet(closed, null)
            }
        } catch (e: Exception) {
            camera?.close()
            throw e
        }
        _current.value = session
        return session
    }

    private fun configuration(iceServers: List<IceServerConfig>, relayOnly: Boolean): PeerConnection.RTCConfiguration {
        val servers = iceServers.filter { it.urls.isNotEmpty() }.map { server ->
            PeerConnection.IceServer.builder(server.urls).apply {
                server.username?.let(::setUsername)
                server.credential?.let(::setPassword)
            }.createIceServer()
        }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        }
    }

    private companion object {
        const val TAG = "WebRtcMedia"
    }
}
