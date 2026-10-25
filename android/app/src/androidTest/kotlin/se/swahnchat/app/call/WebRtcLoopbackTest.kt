package se.swahnchat.app.call

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.Camera2Enumerator
import se.swahnchat.app.appContext
import se.swahnchat.core.client.call.MediaConnectionState
import se.swahnchat.core.client.call.MediaSession
import se.swahnchat.core.model.IceCandidate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two real WebRTC sessions in one process, wired by an in-memory "signalling channel" (no server): offer, answer and ICE
 * candidates are handed straight to the other side. Both must reach CONNECTED over host candidates. The microphone is not
 * needed (the emulator runs with -noaudio): a failing audio record start is only logged and does not stop the connection.
 */
@RunWith(AndroidJUnit4::class)
class WebRtcLoopbackTest {
    @get:Rule val audioPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)

    /** One side's listener: forwards local candidates to the other side and records the connection states. */
    private class Side(val name: String) : MediaSession.Listener {
        lateinit var session: MediaSession
        lateinit var peer: Side
        val states = CopyOnWriteArrayList<MediaConnectionState>()
        val connected = CompletableDeferred<Unit>()

        override fun onLocalCandidate(candidate: IceCandidate) = peer.session.addRemoteCandidates(listOf(candidate))

        override fun onConnectionState(state: MediaConnectionState) {
            states += state
            if (state == MediaConnectionState.CONNECTED) connected.complete(Unit)
            if (state == MediaConnectionState.FAILED) connected.completeExceptionally(AssertionError("$name failed: $states"))
        }
    }

    @Test fun twoSessionsConnectOverHostCandidates() = runBlocking {
        val factory = WebRtcMediaSessionFactory(appContext)
        val caller = Side("caller")
        val callee = Side("callee")
        caller.peer = callee
        callee.peer = caller
        caller.session = factory.create(emptyList(), relayOnly = false, video = false, listener = caller)
        callee.session = factory.create(emptyList(), relayOnly = false, video = false, listener = callee)
        try {
            val offer = caller.session.createOffer()
            assertTrue("a=fingerprint:sha-256" in offer, "the offer must carry a DTLS fingerprint")
            val answer = callee.session.acceptOffer(offer)
            assertTrue("a=fingerprint:sha-256" in answer, "the answer must carry a DTLS fingerprint")
            caller.session.acceptAnswer(answer)
            withTimeout(CONNECT_TIMEOUT_MS) {
                caller.connected.await()
                callee.connected.await()
            }
        } finally {
            caller.session.close()
            callee.session.close()
        }
    }

    /**
     * The same with `video = true` on both sides. With a camera (an emulated front camera may or may not exist) both sides
     * must also get a remote video track; without one the session still works: no local track, receive-only video, the call
     * connects as audio. Never depends on frames arriving.
     */
    @Test fun twoVideoSessionsConnectAndTheCallScreenFindsTheTracks() = runBlocking<Unit> {
        val factory = WebRtcMediaSessionFactory(appContext)
        val hasCamera = Camera2Enumerator(appContext).deviceNames.isNotEmpty()
        val caller = Side("caller")
        val callee = Side("callee")
        caller.peer = callee
        callee.peer = caller
        caller.session = factory.create(emptyList(), relayOnly = false, video = true, listener = caller)
        assertTrue(caller.session === factory.current.value, "the factory exposes the session of the call")
        callee.session = factory.create(emptyList(), relayOnly = false, video = true, listener = callee)
        val callerVideo = caller.session as WebRtcMediaSession
        val calleeVideo = callee.session as WebRtcMediaSession
        try {
            assertEquals(hasCamera, callerVideo.localVideoTrack != null, "a local video track exactly when the phone has a camera")
            assertEquals(hasCamera, calleeVideo.localVideoTrack != null)
            val offer = caller.session.createOffer()
            assertTrue("m=video" in offer, "a video call offers a video section")
            val answer = callee.session.acceptOffer(offer)
            caller.session.acceptAnswer(answer)
            withTimeout(CONNECT_TIMEOUT_MS) {
                caller.connected.await()
                callee.connected.await()
                if (hasCamera) {
                    callerVideo.remoteVideoTrack.filterNotNull().first()
                    calleeVideo.remoteVideoTrack.filterNotNull().first()
                }
            }
            if (!hasCamera) {
                assertNull(callerVideo.remoteVideoTrack.value, "no camera on either side: nothing to receive")
            }
        } finally {
            caller.session.close()
            callee.session.close()
        }
        assertNull(callerVideo.remoteVideoTrack.value, "closing clears the remote track")
        assertNull(callerVideo.localVideoTrack, "closing clears the local track")
        assertNull(factory.current.value, "closing clears the factory's current session")
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
