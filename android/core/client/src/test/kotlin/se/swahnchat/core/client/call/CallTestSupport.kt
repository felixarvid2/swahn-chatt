package se.swahnchat.core.client.call

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import se.swahnchat.core.client.CallSignalEvent
import se.swahnchat.core.client.SystemEvent
import se.swahnchat.core.model.CallSignal
import se.swahnchat.core.model.IceCandidate
import se.swahnchat.core.model.TurnCredentials
import java.io.IOException

/** A signal as one phone sent it, with the (fake) time. */
data class SentSignal(val conversationId: String, val signal: CallSignal, val at: Long)

/** The signalling of several phones in one process: what one sends, the other devices of both members receive. */
class FakeNet(val now: () -> Long) {
    val members = HashMap<String, Set<String>>() // conversation -> its two users
    val phones = ArrayList<FakeSignalling>()

    fun deliver(from: FakeSignalling, conversationId: String, signal: CallSignal) {
        val users = members.getValue(conversationId)
        for (p in phones) {
            if (p === from || p.userId !in users) continue
            p.flow.tryEmit(CallSignalEvent(conversationId, from.userId, from.deviceId, p.userId == from.userId, now(), signal))
        }
    }
}

class FakeSignalling(val net: FakeNet, val userId: String, val deviceId: String) : CallSignalling {
    val flow = MutableSharedFlow<CallSignalEvent>(extraBufferCapacity = 256)
    val sent = ArrayList<SentSignal>()

    /** The history notices the manager asked for, with their conversation and call id. */
    val notices = ArrayList<Triple<String, String, SystemEvent.Call>>()

    /** Decides per signal whether the send fails (null: it goes out). */
    var failure: (CallSignal) -> Throwable? = { null }
    var turn: () -> TurnCredentials = { TurnCredentials(listOf("stun:s:3478", "turn:t:3478"), "user", "secret", 3600) }
    var turnFetches = 0

    init {
        net.phones += this
    }

    override val signals: Flow<CallSignalEvent> get() = flow

    override suspend fun send(conversationId: String, signal: CallSignal) {
        failure(signal)?.let { throw it }
        sent += SentSignal(conversationId, signal, net.now())
        net.deliver(this, conversationId, signal)
    }

    override suspend fun peerOf(conversationId: String): String? =
        net.members[conversationId]?.takeIf { it.size == 2 }?.firstOrNull { it != userId }

    override suspend fun turnCredentials(): TurnCredentials {
        turnFetches++
        return turn()
    }

    override suspend fun recordCall(conversationId: String, callId: String, notice: SystemEvent.Call) {
        notices += Triple(conversationId, callId, notice)
    }

    override fun serverNow(): Long = net.now()

    fun sentActions(action: String) = sent.filter { it.signal.action == action }
    fun sentActions() = sent.map { it.signal.action }
}

const val FAKE_SDP_PREFIX = "v=0\r\na=fingerprint:sha-256 AA:BB\r\n"

class FakeMedia : MediaSessionFactory {
    val sessions = ArrayList<FakeSession>()
    var createFailure: Throwable? = null

    override fun create(iceServers: List<IceServerConfig>, relayOnly: Boolean, video: Boolean, listener: MediaSession.Listener): MediaSession {
        createFailure?.let { throw it }
        return FakeSession(iceServers, relayOnly, video, listener).also { sessions += it }
    }

    val last get() = sessions.last()
}

class FakeSession(
    val iceServers: List<IceServerConfig>,
    val relayOnly: Boolean,
    val video: Boolean,
    val listener: MediaSession.Listener,
) : MediaSession {
    var closed = 0
    var micMuted = false
    val acceptedOffers = ArrayList<String>()
    val acceptedAnswers = ArrayList<String>()
    val remoteCandidates = ArrayList<IceCandidate>()
    var offerFailure: Throwable? = null

    override suspend fun createOffer(): String {
        offerFailure?.let { throw it }
        return FAKE_SDP_PREFIX + "offer"
    }

    override suspend fun acceptOffer(sdp: String): String {
        acceptedOffers += sdp
        return FAKE_SDP_PREFIX + "answer"
    }

    override suspend fun acceptAnswer(sdp: String) {
        acceptedAnswers += sdp
    }

    override fun addRemoteCandidates(candidates: List<IceCandidate>) {
        remoteCandidates += candidates
    }

    override fun setMuted(muted: Boolean) {
        micMuted = muted
    }

    override fun close() {
        closed++
    }

    fun candidate(n: Int) = listener.onLocalCandidate(IceCandidate("0", 0, "candidate:$n"))
    fun state(s: MediaConnectionState) = listener.onConnectionState(s)
}

val NETWORK_DOWN = IOException("down")
