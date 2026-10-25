package se.familychat.app.call

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import se.familychat.core.client.call.IceServerConfig
import se.familychat.core.client.call.MediaSession
import se.familychat.core.client.call.MediaSessionFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [MediaSessionFactory]: WebRTC (`io.github.webrtc-sdk:android`) with DTLS-SRTP, which the library enforces and
 * this class never configures away. The native library and the one [PeerConnectionFactory] are set up lazily, once, on the
 * first call (the lazy delegate is synchronized), so the app start does not pay for it.
 *
 * Audio only for now: [create] accepts `video` so the interface stays stable, but no camera track is added until part B.
 */
@Singleton
class WebRtcMediaSessionFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaSessionFactory {
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
        PeerConnectionFactory.builder().setAudioDeviceModule(audioDevices).createPeerConnectionFactory()
    }

    override fun create(
        iceServers: List<IceServerConfig>,
        relayOnly: Boolean,
        video: Boolean,
        listener: MediaSession.Listener,
    ): MediaSession = WebRtcMediaSession(peerConnectionFactory, configuration(iceServers, relayOnly), listener)

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
