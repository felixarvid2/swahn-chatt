package se.swahnchat.app.call

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.swahnchat.app.appContext
import se.swahnchat.core.client.call.MediaConnectionState
import se.swahnchat.core.client.call.MediaSession
import se.swahnchat.core.model.IceCandidate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue

/**
 * Two real WebRTC sessions in one process, wired by an in-memory "signalling channel" (no server): offer, answer and ICE
 * candidates are handed straight to the other side. Both must reach CONNECTED over host candidates. The microphone is not
 * needed (the emulator runs with -noaudio): a failing audio record start is only logged and does not stop the connection.
 */
@RunWith(AndroidJUnit4::class)
class WebRtcLoopbackTest {
    @get:Rule val audioPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

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

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
