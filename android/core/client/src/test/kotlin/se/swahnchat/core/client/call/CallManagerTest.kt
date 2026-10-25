package se.swahnchat.core.client.call

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.swahnchat.core.client.CallSignalEvent
import se.swahnchat.core.model.CallAction
import se.swahnchat.core.model.CallSignal
import se.swahnchat.core.model.IceCandidate
import se.swahnchat.core.model.TurnCredentials
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val EPOCH = 1_700_000_000_000L
private const val AB = "conv-ab"
private const val BD = "conv-bd"

@OptIn(ExperimentalCoroutinesApi::class)
class CallManagerTest {
    /** Users a, b, d; conversation a-b and b-d. [Phone]s are devices with their own manager and media. */
    private inner class World(val testScope: TestScope) {
        val net = FakeNet { EPOCH + testScope.currentTime }.also {
            it.members[AB] = setOf("a", "b")
            it.members[BD] = setOf("b", "d")
        }

        inner class Phone(val user: String, val device: String, relayOnly: Boolean = false) {
            val signalling = FakeSignalling(net, user, device)
            val media = FakeMedia()
            val manager = CallManager(
                signalling, media, testScope.backgroundScope, clock = { EPOCH + testScope.currentTime },
                relayOnly = { relayOnly }, newId = { ids.next() },
            ).also {
                it.start()
                testScope.runCurrent() // the collector must be subscribed before anything is emitted
            }
            val state get() = manager.state.value
            val session get() = media.last

            /** Injects an event as if [from] had sent [signal] (bypassing the other manager). */
            fun receive(from: Phone, signal: CallSignal, conversation: String = AB, serverTs: Long = net.now(), fromSelf: Boolean = from.user == user) {
                signalling.flow.tryEmit(CallSignalEvent(conversation, from.user, from.device, fromSelf, serverTs, signal))
            }
        }

        private val ids = (1..50).map { "00000000-0000-4000-8000-%012d".format(it) }.iterator()
        fun phone(user: String, device: String, relayOnly: Boolean = false) = Phone(user, device, relayOnly)
    }

    private fun world(block: suspend World.() -> Unit) = runTest {
        val w = World(this)
        w.block()
        // Nothing may be left running after the calls have ended: the scheduler goes idle without further time passing.
        advanceUntilIdle()
    }

    private fun offer(id: String, video: Boolean = false) = CallSignal(id, CallAction.OFFER, video, sdp = FAKE_SDP_PREFIX + "offer")
    private fun answer(id: String, sdp: String = FAKE_SDP_PREFIX + "answer") = CallSignal(id, CallAction.ANSWER, sdp = sdp)
    private fun ice(id: String, vararg n: Int) = CallSignal(id, CallAction.ICE, candidates = n.map { IceCandidate("0", 0, "candidate:$it") })
    private fun plain(id: String, action: String) = CallSignal(id, action)

    /** a calls b (one device); both reach Connected. Returns the call id. */
    private suspend fun World.connect(a: World.Phone, b: World.Phone): String {
        val id = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        b.manager.accept()
        testScope.runCurrent()
        a.session.state(MediaConnectionState.CONNECTED)
        b.session.state(MediaConnectionState.CONNECTED)
        testScope.runCurrent()
        return id
    }

    @Test
    fun `a call rings, is answered, connects and hangs up on both sides`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val id = a.manager.startCall(AB, video = true)
        assertEquals(CallState.Outgoing(id, AB, "b", true), a.state)
        testScope.runCurrent()
        assertEquals(CallState.Incoming(id, AB, "a", true), b.state)
        assertEquals(listOf(CallAction.OFFER), a.signalling.sentActions())
        assertTrue(a.session.video)

        b.manager.accept()
        testScope.runCurrent()
        assertEquals(CallState.Connecting(id, AB, "a", true, outgoing = false), b.state)
        assertEquals(CallState.Connecting(id, AB, "b", true, outgoing = true), a.state)
        assertEquals(listOf(FAKE_SDP_PREFIX + "offer"), b.session.acceptedOffers)
        assertEquals(listOf(FAKE_SDP_PREFIX + "answer"), a.session.acceptedAnswers)

        testScope.advanceTimeBy(1_000)
        a.session.state(MediaConnectionState.CONNECTED)
        b.session.state(MediaConnectionState.CONNECTED)
        testScope.runCurrent()
        val connected = assertIs<CallState.Connected>(a.state)
        assertEquals(EPOCH + 1_000, connected.since)
        assertIs<CallState.Connected>(b.state)

        a.manager.hangup()
        testScope.runCurrent()
        assertEquals(CallState.Ended(id, EndReason.HANGUP_LOCAL), a.state)
        assertEquals(CallState.Ended(id, EndReason.HANGUP_REMOTE), b.state)
        assertEquals(1, a.session.closed)
        assertEquals(1, b.session.closed)
        testScope.advanceTimeBy(5_000)
        assertEquals(CallState.Idle, a.state)
        assertEquals(CallState.Idle, b.state)
    }

    // --- rule 1 and 2: one call, who may call ------------------------------------------------------------------------

    @Test
    fun `an offer while in a call is answered with busy and changes nothing`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val d = phone("d", "d1")
        val id = connect(a, b)
        val dId = d.manager.startCall(BD, video = false)
        testScope.runCurrent()
        assertEquals(listOf(CallAction.ANSWER, CallAction.BUSY), b.signalling.sentActions())
        assertEquals(dId, b.signalling.sent.last().signal.callId)
        assertEquals(BD, b.signalling.sent.last().conversationId)
        assertIs<CallState.Connected>(b.state)
        assertEquals(id, (b.state as CallState.Connected).callId)
        assertEquals(1, b.media.sessions.size)
        // the busy callee ends the caller's attempt (before any device answered)
        assertEquals(CallState.Ended(dId, EndReason.BUSY), d.state)
        assertEquals(1, d.media.last.closed)
    }

    @Test
    fun `starting a second call is refused`() = world {
        val a = phone("a", "a1")
        phone("b", "b1")
        a.manager.startCall(AB, video = false)
        assertFailsWith<IllegalStateException> { a.manager.startCall(AB, video = false) }
        assertFailsWith<IllegalArgumentException> { phone("x", "x1").manager.startCall("nope", video = false) }
    }

    @Test
    fun `offers from ourselves, from a stranger, replayed or stale are ignored`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val b2 = phone("b", "b2")
        val d = phone("d", "d1")
        val id = "00000000-0000-4000-8000-0000000000aa"
        b.receive(b2, offer(id)) // our own other device
        b.receive(d, offer(id)) // not a member of the conversation a-b
        b.receive(a, offer(id), serverTs = net.now() - 61_000) // stale
        testScope.runCurrent()
        assertEquals(CallState.Idle, b.state)

        b.receive(a, offer(id))
        testScope.runCurrent()
        assertIs<CallState.Incoming>(b.state)
        b.manager.reject()
        testScope.advanceTimeBy(5_000)
        assertEquals(CallState.Idle, b.state)
        b.receive(a, offer(id)) // replay of a call already seen
        testScope.runCurrent()
        assertEquals(CallState.Idle, b.state)
        assertEquals(0, b.media.sessions.size)
    }

    // --- rule 3: all devices ring ------------------------------------------------------------------------------------

    @Test
    fun `all devices of the callee ring and an answer on one stops the others without sending`() = world {
        val a = phone("a", "a1")
        val b1 = phone("b", "b1")
        val b2 = phone("b", "b2")
        val id = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        assertIs<CallState.Incoming>(b1.state)
        assertIs<CallState.Incoming>(b2.state)

        b1.manager.accept()
        testScope.runCurrent()
        assertEquals(CallState.Ended(id, EndReason.ANSWERED_ELSEWHERE), b2.state)
        assertEquals(emptyList(), b2.signalling.sent)
        assertEquals(0, b2.media.sessions.size)
        assertEquals(CallAction.ANSWER, b1.signalling.sent.single().signal.action)
        assertIs<CallState.Connecting>(a.state)
    }

    @Test
    fun `a reject on one device stops the others and the caller`() = world {
        val a = phone("a", "a1")
        val b1 = phone("b", "b1")
        val b2 = phone("b", "b2")
        val id = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        b1.manager.reject()
        testScope.runCurrent()
        assertEquals(CallState.Ended(id, EndReason.REJECTED), b1.state)
        assertEquals(CallState.Ended(id, EndReason.REJECTED), b2.state)
        assertEquals(emptyList(), b2.signalling.sent)
        assertEquals(CallState.Ended(id, EndReason.REJECTED), a.state)
        assertEquals(1, a.session.closed)
    }

    // --- rule 4: the caller locks on to the first answer --------------------------------------------------------------

    @Test
    fun `the caller locks on to the first answering device and ignores the rest`() = world {
        val a = phone("a", "a1")
        val b1 = phone("b", "b1")
        val b2 = phone("b", "b2")
        val id = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        // candidates arrive before the answer: from both devices, only the answering one's may be used
        a.receive(b2, ice(id, 20))
        a.receive(b1, ice(id, 10))
        testScope.runCurrent()
        assertEquals(emptyList(), a.session.remoteCandidates)

        a.receive(b1, answer(id, FAKE_SDP_PREFIX + "from-b1"))
        a.receive(b2, answer(id, FAKE_SDP_PREFIX + "from-b2"))
        testScope.runCurrent()
        assertEquals(listOf(FAKE_SDP_PREFIX + "from-b1"), a.session.acceptedAnswers)
        assertEquals(listOf("candidate:10"), a.session.remoteCandidates.map { it.candidate })

        a.receive(b2, ice(id, 21))
        a.receive(b1, ice(id, 11))
        a.receive(b2, plain(id, CallAction.HANGUP))
        a.receive(b2, plain(id, CallAction.REJECT))
        a.receive(b2, plain(id, CallAction.BUSY))
        testScope.runCurrent()
        assertEquals(listOf("candidate:10", "candidate:11"), a.session.remoteCandidates.map { it.candidate })
        assertIs<CallState.Connecting>(a.state)
    }

    @Test
    fun `candidates of the callee that arrive before it accepted are used after it did`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val id = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        b.receive(a, ice(id, 1, 2))
        b.receive(phone("a", "a2"), ice(id, 3)) // another device of the caller is not the one that offered
        testScope.runCurrent()
        b.manager.accept()
        testScope.runCurrent()
        assertEquals(listOf("candidate:1", "candidate:2"), b.session.remoteCandidates.map { it.candidate })
        b.receive(a, ice(id, 4))
        testScope.runCurrent()
        assertEquals(listOf(1, 2, 4), b.session.remoteCandidates.map { it.candidate.removePrefix("candidate:").toInt() })
    }

    // --- rule 5: local candidates are batched -------------------------------------------------------------------------

    @Test
    fun `local candidates go out in batches of at most 50, one signal per 250 ms`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val id = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        // gathered before the offer is out of the door (here: right away): nothing is sent before the offer
        for (n in 1..120) a.session.candidate(n)
        testScope.advanceTimeBy(1_000)
        val ices = a.signalling.sentActions(CallAction.ICE)
        assertEquals(listOf(50, 50, 20), ices.map { it.signal.candidates!!.size })
        assertEquals(CallAction.OFFER, a.signalling.sent.first().signal.action)
        for ((x, y) in ices.zipWithNext()) assertTrue(y.at - x.at >= 250, "ice signals ${y.at - x.at} ms apart")
        assertEquals(120, ices.sumOf { it.signal.candidates!!.size })
        assertEquals((1..120).map { "candidate:$it" }, ices.flatMap { it.signal.candidates!! }.map { it.candidate })
        // the callee got them (buffered until it accepts)
        b.manager.accept()
        testScope.runCurrent()
        assertEquals(120, b.session.remoteCandidates.size)
        assertEquals(id, ices.first().signal.callId)
    }

    @Test
    fun `at most 20 ice signals per call and a failed batch is dropped without ending the call`() = world {
        val a = phone("a", "a1")
        phone("b", "b1")
        a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        var fail = true
        a.signalling.failure = { if (it.action == CallAction.ICE && fail) IOException("429") else null }
        a.session.candidate(1)
        testScope.advanceTimeBy(300)
        assertEquals(0, a.signalling.sentActions(CallAction.ICE).size)
        assertIs<CallState.Outgoing>(a.state)
        fail = false
        for (n in 2..40) {
            a.session.candidate(n)
            testScope.advanceTimeBy(300)
        }
        // one signal was lost to the failure, which counts against the cap
        assertEquals(19, a.signalling.sentActions(CallAction.ICE).size)
        for (n in 41..60) {
            a.session.candidate(n)
            testScope.advanceTimeBy(300)
        }
        assertEquals(19, a.signalling.sentActions(CallAction.ICE).size) // nothing beyond the cap of 20
        assertIs<CallState.Outgoing>(a.state)
    }

    // --- rule 6: timeouts --------------------------------------------------------------------------------------------

    @Test
    fun `an unanswered outgoing call hangs up after 45 s`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val id = a.manager.startCall(AB, video = false)
        testScope.advanceTimeBy(44_000)
        assertIs<CallState.Outgoing>(a.state)
        testScope.advanceTimeBy(1_100)
        assertEquals(CallState.Ended(id, EndReason.NO_ANSWER), a.state)
        assertEquals(listOf(CallAction.OFFER, CallAction.HANGUP), a.signalling.sentActions())
        assertEquals(1, a.session.closed)
        // the callee, which rang as long, has ended too (the hangup arrives first or its own 45 s: missed either way)
        assertEquals(CallState.Ended(id, EndReason.MISSED), b.state)
    }

    @Test
    fun `an unanswered incoming call is missed after 45 s and sends nothing`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val id = "00000000-0000-4000-8000-0000000000bb"
        b.receive(a, offer(id))
        testScope.runCurrent()
        testScope.advanceTimeBy(44_000)
        assertIs<CallState.Incoming>(b.state)
        testScope.advanceTimeBy(1_100)
        assertEquals(CallState.Ended(id, EndReason.MISSED), b.state)
        assertEquals(emptyList(), b.signalling.sent)
        assertEquals(0, b.media.sessions.size)
    }

    @Test
    fun `a call that does not connect within 30 s fails with a hangup`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val id = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        b.manager.accept()
        testScope.advanceTimeBy(29_000)
        assertIs<CallState.Connecting>(a.state)
        testScope.advanceTimeBy(1_100)
        assertEquals(CallState.Ended(id, EndReason.FAILED), a.state)
        assertEquals(CallState.Ended(id, EndReason.FAILED), b.state)
        assertEquals(CallAction.HANGUP, a.signalling.sent.last().signal.action)
        assertEquals(1, a.session.closed)
        assertEquals(1, b.session.closed)
    }

    @Test
    fun `connecting in time cancels the timeout`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        connect(a, b)
        testScope.advanceTimeBy(120_000)
        assertIs<CallState.Connected>(a.state)
        assertIs<CallState.Connected>(b.state)
        a.session.state(MediaConnectionState.DISCONNECTED)
        testScope.runCurrent()
        assertIs<CallState.Connected>(a.state)
        a.session.state(MediaConnectionState.FAILED)
        testScope.runCurrent()
        assertIs<CallState.Ended>(a.state)
        assertEquals(EndReason.FAILED, (a.state as CallState.Ended).reason)
        assertEquals(CallAction.HANGUP, a.signalling.sent.last().signal.action)
        assertEquals(EndReason.HANGUP_REMOTE, (b.state as CallState.Ended).reason)
    }

    // --- rule 7: hangup, reject, busy ---------------------------------------------------------------------------------

    @Test
    fun `hangup, reject and busy from the callee end the call before the caller has locked`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val cases = listOf(CallAction.REJECT to EndReason.REJECTED, CallAction.BUSY to EndReason.BUSY, CallAction.HANGUP to EndReason.HANGUP_REMOTE)
        for ((action, reason) in cases) {
            val id = a.manager.startCall(AB, video = false)
            testScope.runCurrent()
            a.receive(b, plain(id, action))
            testScope.runCurrent()
            assertEquals(CallState.Ended(id, reason), a.state, action)
            b.manager.hangup()
            testScope.advanceTimeBy(5_000)
        }
    }

    @Test
    fun `a hangup of the caller before the callee answered counts as missed`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val id = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        a.manager.hangup()
        testScope.runCurrent()
        assertEquals(CallState.Ended(id, EndReason.MISSED), b.state)
        assertEquals(listOf(CallAction.OFFER, CallAction.HANGUP), a.signalling.sentActions())
        assertEquals(CallState.Ended(id, EndReason.HANGUP_LOCAL), a.state)
    }

    @Test
    fun `signals for another call and from the wrong people are ignored`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val d = phone("d", "d1")
        val id = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        val other = "00000000-0000-4000-8000-0000000000cc"
        for (action in listOf(CallAction.HANGUP, CallAction.REJECT, CallAction.BUSY, CallAction.ANSWER)) {
            a.receive(b, if (action == CallAction.ANSWER) answer(other) else plain(other, action))
        }
        a.receive(b, ice(other, 1))
        a.receive(d, plain(id, CallAction.HANGUP)) // not the callee
        a.receive(a, plain(id, CallAction.HANGUP), fromSelf = true) // an own device cannot end the outgoing call
        a.receive(b, plain(id, CallAction.HANGUP), conversation = BD) // right call id, wrong conversation
        testScope.runCurrent()
        assertEquals(CallState.Outgoing(id, AB, "b", false), a.state)
        assertEquals(0, a.session.remoteCandidates.size)
        assertEquals(0, a.session.acceptedAnswers.size)
        // a callee's answer that is not for the outgoing phase does not apply twice
        a.receive(b, answer(id))
        a.receive(b, answer(id, FAKE_SDP_PREFIX + "again"))
        testScope.runCurrent()
        assertEquals(1, a.session.acceptedAnswers.size)
        assertIs<CallState.Connecting>(a.state)
        b.manager.hangup()
    }

    // --- rules 8 and 9: ice servers ------------------------------------------------------------------------------------

    @Test
    fun `credentials are fetched fresh for every call and turned into ice servers`() = world {
        val a = phone("a", "a1", relayOnly = true)
        val b = phone("b", "b1")
        connect(a, b)
        assertEquals(1, a.signalling.turnFetches)
        assertEquals(1, b.signalling.turnFetches)
        assertEquals(listOf(IceServerConfig(listOf("stun:s:3478", "turn:t:3478"), "user", "secret")), a.session.iceServers)
        assertTrue(a.session.relayOnly)
        assertTrue(!b.session.relayOnly)
        a.manager.hangup()
        testScope.advanceTimeBy(5_000)
        a.signalling.turn = { TurnCredentials(listOf("stun:s:3478"), null, null, 0) }
        connect(a, b)
        assertEquals(2, a.signalling.turnFetches)
        assertEquals(listOf(IceServerConfig(listOf("stun:s:3478"), null, null)), a.session.iceServers)
    }

    @Test
    fun `a failed credential fetch does not stop the call`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        a.signalling.turn = { throw IOException("turn down") }
        b.signalling.turn = { TurnCredentials(emptyList(), null, null, 0) }
        connect(a, b)
        assertEquals(emptyList(), a.session.iceServers)
        assertEquals(emptyList(), b.session.iceServers)
        assertIs<CallState.Connected>(a.state)
    }

    // --- rule 10 and failures: sessions are closed on every path ---------------------------------------------------------

    @Test
    fun `a failing offer ends the call as failed and closes the session`() = world {
        val a = phone("a", "a1")
        val id = a.manager.startCall(AB, video = false)
        a.signalling.failure = { if (it.action == CallAction.OFFER) NETWORK_DOWN else null }
        testScope.runCurrent()
        assertEquals(CallState.Ended(id, EndReason.FAILED), a.state)
        assertEquals(1, a.session.closed)
        testScope.advanceTimeBy(1_000)
        assertEquals(CallAction.HANGUP, a.signalling.sent.single().signal.action) // the offer may have arrived: tell the callee
    }

    @Test
    fun `a media factory that throws ends the call as failed`() = world {
        val a = phone("a", "a1")
        a.media.createFailure = IllegalStateException("no audio")
        val id = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        assertEquals(CallState.Ended(id, EndReason.FAILED), a.state)
        assertEquals(0, a.media.sessions.size)
    }

    @Test
    fun `accepting with a failing media session ends as failed and tells the caller`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val id = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        b.media.createFailure = IllegalStateException("no audio")
        b.manager.accept()
        testScope.runCurrent()
        assertEquals(CallState.Ended(id, EndReason.FAILED), b.state)
        assertEquals(CallAction.HANGUP, b.signalling.sent.last().signal.action)
        assertEquals(CallState.Ended(id, EndReason.HANGUP_REMOTE), a.state)
    }

    @Test
    fun `hanging up while the offer is being set up cancels the setup and still hangs up`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val id = a.manager.startCall(AB, video = false)
        a.manager.hangup() // before the setup coroutine has even run
        testScope.runCurrent()
        assertEquals(CallState.Ended(id, EndReason.HANGUP_LOCAL), a.state)
        assertTrue(a.media.sessions.isEmpty())
        assertEquals(listOf(CallAction.HANGUP), a.signalling.sentActions())
        assertEquals(CallState.Idle, b.state)
    }

    @Test
    fun `every way a call ends closes the media session exactly once`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        // local hangup, remote hangup, reject, failure: covered above for the pair; here the session counts across calls
        val id = connect(a, b)
        b.manager.hangup()
        testScope.runCurrent()
        assertEquals(CallState.Ended(id, EndReason.HANGUP_LOCAL), b.state)
        assertEquals(1, b.session.closed)
        assertEquals(1, a.session.closed)
        // late media events and signals of the ended call do nothing
        a.session.state(MediaConnectionState.FAILED)
        a.receive(b, plain(id, CallAction.HANGUP))
        testScope.runCurrent()
        assertEquals(1, a.session.closed)
        assertEquals(EndReason.HANGUP_REMOTE, (a.state as CallState.Ended).reason)
    }

    @Test
    fun `mute reaches the session, also one that is created later`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        a.manager.startCall(AB, video = false)
        a.manager.setMuted(true)
        assertTrue(a.manager.muted.value)
        testScope.runCurrent()
        assertTrue(a.session.micMuted)
        a.manager.setMuted(false)
        assertTrue(!a.session.micMuted)
        a.manager.hangup()
        testScope.runCurrent()
        assertTrue(!a.manager.muted.value)
        b.manager.hangup()
    }

    @Test
    fun `a new call can start right after one ended, while the old state lingers`() = world {
        val a = phone("a", "a1")
        val b = phone("b", "b1")
        val first = a.manager.startCall(AB, video = false)
        testScope.runCurrent()
        a.manager.hangup()
        testScope.runCurrent()
        assertEquals(CallState.Ended(first, EndReason.HANGUP_LOCAL), a.state)
        val second = a.manager.startCall(AB, video = false)
        testScope.advanceTimeBy(5_000) // the linger of the first must not reset the second
        assertEquals(CallState.Outgoing(second, AB, "b", false), a.state)
        a.manager.hangup()
        b.manager.hangup()
    }
}
