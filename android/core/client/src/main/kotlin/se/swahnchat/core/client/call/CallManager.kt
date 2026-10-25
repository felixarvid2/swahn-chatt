package se.swahnchat.core.client.call

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.swahnchat.core.client.CallSignalEvent
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.CallOutcome
import se.swahnchat.core.client.SystemEvent
import se.swahnchat.core.model.CallAction
import se.swahnchat.core.model.CallSignal
import se.swahnchat.core.model.IceCandidate
import java.util.UUID
import kotlin.math.abs
import java.util.concurrent.ConcurrentLinkedQueue

/** The durations of a call, in milliseconds; tests use the defaults with virtual time. */
data class CallTimings(
    /** How long a call rings, outgoing and incoming. */
    val ringMs: Long = 45_000,
    /** From answered to connected. */
    val connectMs: Long = 30_000,
    /** Local ICE candidates are sent in batches, at most one signal per this interval (server limit: 60 call messages a minute). */
    val iceBatchMs: Long = 250,
    /** How long [CallState.Ended] stays before the state is [CallState.Idle] again. */
    val endedLingerMs: Long = 2_000,
)

/**
 * The state machine of 1:1 calls: platform-free, signalling through [CallSignalling] and media through a
 * [MediaSessionFactory]. One call at a time; everything the other side sends is untrusted input, so a signal only
 * counts when it belongs to the current call, comes from the other member (or, where stated, our own other devices) and,
 * once the caller has locked on to the first answering device, from that device only.
 *
 * Call [start] once at app start so signals are collected; [startCall], [accept], [reject] and [hangup] are the user's
 * actions, [state] what to show. Every path that ends a call closes the [MediaSession] and cancels the call's timers.
 *
 * @param relayOnly read when a media session is created: true forces all media through the TURN relay (hides the IP address).
 */
class CallManager(
    private val signalling: CallSignalling,
    private val mediaFactory: MediaSessionFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val relayOnly: () -> Boolean = { false },
    private val timings: CallTimings = CallTimings(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(
        engine: SwahnChatEngine,
        mediaFactory: MediaSessionFactory,
        scope: CoroutineScope,
        clock: () -> Long = System::currentTimeMillis,
        relayOnly: () -> Boolean = { false },
    ) : this(EngineCallSignalling(engine), mediaFactory, scope, clock, relayOnly)

    private enum class Phase { RINGING_OUT, RINGING_IN, CONNECTING, CONNECTED }

    /** The (user, device) a signal came from. */
    private data class Origin(val userId: String, val deviceId: String)

    private class Active(
        val callId: String,
        val conversationId: String,
        val peerUserId: String,
        val video: Boolean,
        val outgoing: Boolean,
        var phase: Phase,
    ) {
        /** The caller's offer SDP while the callee's phone rings. */
        var offerSdp: String? = null

        /** Caller: the first answering device. Callee: the device that sent the offer. Signals of other devices are ignored. */
        var locked: Origin? = null

        @Volatile var session: MediaSession? = null

        /** The device we are locked on to is unverified and new (see [CallState.InCall.peerDeviceNew]). */
        var peerDeviceNew = false

        /** True once the remote description is set, so remote candidates can be added. */
        var remoteReady = false
        val pendingRemote = ArrayList<Pair<Origin, IceCandidate>>()
        val localQueue = ConcurrentLinkedQueue<IceCandidate>()
        val iceKick = Channel<Unit>(Channel.CONFLATED)
        val mediaEvents = Channel<MediaConnectionState>(Channel.UNLIMITED)
        var iceSignalsSent = 0

        /** Clock time (ms) the media connected; null if the call never did. */
        var connectedAt: Long? = null

        @Volatile var closed = false
        var ringJob: Job? = null
        var connectJob: Job? = null
        var setupJob: Job? = null
        var remoteJob: Job? = null
        var iceJob: Job? = null
        var mediaJob: Job? = null
    }

    private val lock = Mutex()

    @Volatile private var active: Active? = null
    private val seen = LinkedHashSet<String>()

    /** Per peer user: when we last answered an offer with BUSY, and when offers last rang this phone. */
    private val lastBusy = HashMap<String, Long>()
    private val rings = HashMap<String, ArrayDeque<Long>>()
    private var lingerJob: Job? = null
    private var collector: Job? = null

    private val _state = MutableStateFlow<CallState>(CallState.Idle)

    /** What to show. */
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _muted = MutableStateFlow(false)

    /** Whether the microphone is muted in the current call. */
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    // --- life cycle ---------------------------------------------------------------------------------------------------

    /** Starts collecting the engine's call signals (once; later calls do nothing). Signals that arrive earlier are lost by design. */
    fun start() {
        if (collector?.isActive == true) return
        collector = scope.launch { signalling.signals.collect { handle(it) } }
    }

    /** Stops collecting and hangs up a call in progress. */
    suspend fun stop() {
        collector?.cancel()
        collector = null
        hangup()
    }

    // --- the user's actions -------------------------------------------------------------------------------------------

    /**
     * Calls the other member of the direct conversation [conversationId]; returns the call id once the call rings
     * (the offer is sent in the background; if that fails the call ends with [EndReason.FAILED]).
     *
     * @throws IllegalStateException another call is in progress
     * @throws IllegalArgumentException the conversation is not a known direct conversation
     */
    suspend fun startCall(conversationId: String, video: Boolean): String {
        val peer = signalling.peerOf(conversationId) ?: throw IllegalArgumentException("Calls need a direct conversation")
        return lock.withLock {
            check(active == null) { "A call is already in progress" }
            val a = Active(newId(), conversationId, peer, video, outgoing = true, phase = Phase.RINGING_OUT)
            seen += a.callId
            begin(a, CallState.Outgoing(a.callId, conversationId, peer, video))
            a.setupJob = scope.launch { setupCaller(a) }
            a.callId
        }
    }

    /** Answers the ringing call: sets up the media session, sends the answer to all devices. */
    suspend fun accept() = lock.withLock {
        val a = active?.takeIf { it.phase == Phase.RINGING_IN } ?: return@withLock
        a.ringJob?.cancel()
        enterConnecting(a)
        a.setupJob = scope.launch { setupCallee(a) }
    }

    /** Rejects the ringing call; this account's other devices stop ringing too. */
    suspend fun reject() = lock.withLock {
        val a = active?.takeIf { it.phase == Phase.RINGING_IN } ?: return@withLock
        end(a, EndReason.REJECTED, CallAction.REJECT)
    }

    /** Ends the call, whatever its state (a ringing incoming call is rejected). */
    suspend fun hangup() = lock.withLock {
        val a = active ?: return@withLock
        if (a.phase == Phase.RINGING_IN) end(a, EndReason.REJECTED, CallAction.REJECT) else end(a, EndReason.HANGUP_LOCAL, CallAction.HANGUP)
    }

    /** Mutes or unmutes the microphone of the current call (remembered for a session that is still being set up). */
    fun setMuted(muted: Boolean) {
        _muted.value = muted
        active?.session?.setMuted(muted)
    }

    // --- incoming signals ---------------------------------------------------------------------------------------------

    private suspend fun handle(e: CallSignalEvent) = lock.withLock {
        val s = e.signal
        if (s.action == CallAction.OFFER) return@withLock onOffer(e)
        val a = active ?: return@withLock
        if (s.callId != a.callId || e.conversationId != a.conversationId) return@withLock // unknown call: ignored
        if (e.fromSelf) return@withLock onOwnDevice(a, e)
        if (e.fromUserId != a.peerUserId) return@withLock
        when (s.action) {
            CallAction.ANSWER -> onAnswer(a, e)
            CallAction.ICE -> onIce(a, e)
            CallAction.HANGUP, CallAction.REJECT, CallAction.BUSY -> onFinal(a, e)
        }
    }

    private suspend fun onOffer(e: CallSignalEvent) {
        val s = e.signal
        val sdp = s.sdp
        val acceptable = !e.fromSelf && sdp != null && s.callId !in seen && // offers only come from the other member, once
            signalling.serverNow() - e.serverTs <= MAX_SIGNAL_AGE_MS &&
            sentAtPlausible(s.sentAt) &&
            signalling.peerOf(e.conversationId) == e.fromUserId
        if (!acceptable) return
        checkNotNull(sdp)
        seen += s.callId
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        val now = clock()
        if (active != null) {
            // at most one BUSY per peer user per window, further offers are ignored until it has passed
            val last = lastBusy[e.fromUserId]
            if (last != null && now - last < BUSY_WINDOW_MS) return
            lastBusy.values.removeAll { now - it >= BUSY_WINDOW_MS }
            lastBusy[e.fromUserId] = now
            scope.launch { sendQuietly(e.conversationId, signal(s.callId, CallAction.BUSY, s.video)) }
            return
        }
        // at most MAX_RINGS rings per peer user per window; the rest is ignored (neither rings nor answers BUSY)
        val recent = rings.getOrPut(e.fromUserId) { ArrayDeque() }
        while (recent.isNotEmpty() && now - recent.first() >= RING_WINDOW_MS) recent.removeFirst()
        if (recent.size >= MAX_RINGS) return
        recent.addLast(now)
        rings.values.removeAll { it.isEmpty() }
        val a = Active(s.callId, e.conversationId, e.fromUserId, s.video, outgoing = false, phase = Phase.RINGING_IN)
        a.offerSdp = sdp
        a.locked = Origin(e.fromUserId, e.fromDeviceId)
        a.peerDeviceNew = isNewDevice(e.fromUserId, e.fromDeviceId)
        begin(a, CallState.Incoming(a.callId, a.conversationId, a.peerUserId, a.video, a.peerDeviceNew))
    }

    /** The sender's clock must be within [MAX_CLOCK_SKEW_MS] of ours; an offer without it is dropped. */
    private fun sentAtPlausible(sentAt: Long?) = sentAt != null && abs(clock() - sentAt) <= MAX_CLOCK_SKEW_MS

    private suspend fun isNewDevice(userId: String, deviceId: String): Boolean = try {
        signalling.isNewUnverifiedDevice(userId, deviceId)
    } catch (c: CancellationException) {
        throw c
    } catch (ex: Exception) {
        true // when it cannot be told, warn
    }

    /** Our own other devices: they only matter while this phone rings. */
    private fun onOwnDevice(a: Active, e: CallSignalEvent) {
        if (a.phase != Phase.RINGING_IN) return
        when (e.signal.action) {
            CallAction.ANSWER -> end(a, EndReason.ANSWERED_ELSEWHERE)
            CallAction.REJECT -> end(a, EndReason.REJECTED)
            // The other device was busy: the caller has ended on that BUSY already, so this phone stops ringing as well.
            CallAction.BUSY -> end(a, EndReason.MISSED)
        }
    }

    /** The caller locks on to the first answer; the answer of any other device is ignored from then on. */
    private suspend fun onAnswer(a: Active, e: CallSignalEvent) {
        val sdp = e.signal.sdp
        if (!a.outgoing || a.locked != null || a.phase != Phase.RINGING_OUT || sdp == null) return
        a.locked = Origin(e.fromUserId, e.fromDeviceId)
        a.ringJob?.cancel()
        a.peerDeviceNew = isNewDevice(e.fromUserId, e.fromDeviceId)
        enterConnecting(a)
        a.remoteJob = scope.launch {
            try {
                val session = a.session ?: error("No media session")
                session.acceptAnswer(sdp)
            } catch (c: CancellationException) {
                throw c
            } catch (ex: Exception) {
                return@launch fail(a, hangup = true)
            }
            lock.withLock { if (active === a) remoteDescriptionSet(a) }
        }
    }

    private fun onIce(a: Active, e: CallSignalEvent) {
        val origin = Origin(e.fromUserId, e.fromDeviceId)
        if (a.locked != null && a.locked != origin) return
        val candidates = e.signal.candidates.orEmpty().take(MAX_ICE_PER_SIGNAL)
        val session = a.session
        if (a.remoteReady && session != null) {
            session.addRemoteCandidates(candidates)
        } else {
            for (c in candidates) if (a.pendingRemote.size < MAX_PENDING_REMOTE) a.pendingRemote += origin to c
        }
    }

    /** HANGUP, REJECT or BUSY from the locked device, or before the caller has locked from any device of the callee. */
    private fun onFinal(a: Active, e: CallSignalEvent) {
        val locked = a.locked
        val allowed = if (locked != null) locked == Origin(e.fromUserId, e.fromDeviceId) else a.outgoing
        if (!allowed) return
        val reason = when (e.signal.action) {
            CallAction.HANGUP -> if (a.phase == Phase.RINGING_IN) EndReason.MISSED else EndReason.HANGUP_REMOTE
            CallAction.REJECT -> if (a.outgoing) EndReason.REJECTED else return
            else -> if (a.outgoing) EndReason.BUSY else return
        }
        end(a, reason)
    }

    // --- setting up a call --------------------------------------------------------------------------------------------

    private fun begin(a: Active, state: CallState) {
        lingerJob?.cancel()
        active = a
        _muted.value = false
        _state.value = state
        a.ringJob = scope.launch {
            delay(timings.ringMs)
            lock.withLock {
                if (active !== a) return@withLock
                if (a.outgoing) end(a, EndReason.NO_ANSWER, CallAction.HANGUP) else end(a, EndReason.MISSED)
            }
        }
    }

    private fun enterConnecting(a: Active) {
        a.phase = Phase.CONNECTING
        _state.value = CallState.Connecting(a.callId, a.conversationId, a.peerUserId, a.video, a.outgoing, a.peerDeviceNew)
        a.connectJob = scope.launch {
            delay(timings.connectMs)
            lock.withLock { if (active === a) end(a, EndReason.FAILED, CallAction.HANGUP) }
        }
    }

    private suspend fun setupCaller(a: Active) {
        try {
            val session = createSession(a) ?: return
            val offer = session.createOffer()
            if (active !== a) return
            signalling.send(a.conversationId, signal(a.callId, CallAction.OFFER, a.video, sdp = offer))
            lock.withLock { if (active === a) startIce(a) }
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            fail(a, hangup = true)
        }
    }

    private suspend fun setupCallee(a: Active) {
        try {
            val session = createSession(a) ?: return
            val answer = session.acceptOffer(a.offerSdp ?: error("No offer"))
            lock.withLock {
                if (active !== a) return
                remoteDescriptionSet(a)
            }
            signalling.send(a.conversationId, signal(a.callId, CallAction.ANSWER, a.video, sdp = answer))
            lock.withLock { if (active === a) startIce(a) }
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            fail(a, hangup = true)
        }
    }

    /** Fetches fresh credentials (no relay when that fails) and creates the media session, unless the call has ended meanwhile. */
    private suspend fun createSession(a: Active): MediaSession? {
        val turn = try {
            signalling.turnCredentials()
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            null
        }
        val servers = turn?.takeIf { it.urls.isNotEmpty() }?.let { listOf(IceServerConfig(it.urls, it.username, it.credential)) }.orEmpty()
        return lock.withLock {
            if (active !== a) return@withLock null
            val listener = object : MediaSession.Listener {
                override fun onLocalCandidate(candidate: IceCandidate) {
                    if (a.closed) return
                    a.localQueue.add(candidate)
                    a.iceKick.trySend(Unit)
                }

                override fun onConnectionState(state: MediaConnectionState) {
                    a.mediaEvents.trySend(state)
                }
            }
            val session = mediaFactory.create(servers, relayOnly(), a.video, listener)
            a.session = session
            if (_muted.value) session.setMuted(true)
            a.mediaJob = scope.launch { for (s in a.mediaEvents) onMediaState(a, s) }
            session
        }
    }

    /** The remote description is set: candidates that arrived early (from the device we talk to) can go in. */
    private fun remoteDescriptionSet(a: Active) {
        a.remoteReady = true
        val session = a.session ?: return
        val early = a.pendingRemote.filter { a.locked == null || it.first == a.locked }.map { it.second }
        a.pendingRemote.clear()
        if (early.isNotEmpty()) session.addRemoteCandidates(early)
    }

    private suspend fun onMediaState(a: Active, s: MediaConnectionState) = lock.withLock {
        if (active !== a) return@withLock
        when (s) {
            MediaConnectionState.CONNECTED -> if (a.phase == Phase.CONNECTING) {
                a.connectJob?.cancel()
                a.phase = Phase.CONNECTED
                val now = clock()
                a.connectedAt = now
                _state.value = CallState.Connected(a.callId, a.conversationId, a.peerUserId, a.video, a.outgoing, now, a.peerDeviceNew)
            }
            MediaConnectionState.FAILED -> end(a, EndReason.FAILED, CallAction.HANGUP)
            else -> Unit
        }
    }

    // --- local ICE candidates -----------------------------------------------------------------------------------------

    /**
     * Starts sending our candidates, which is only done after the offer (caller) or the answer (callee) went out, so the
     * other side knows the call. They go out in batches of at most [MAX_ICE_PER_SIGNAL], one signal per
     * [CallTimings.iceBatchMs] and [MAX_ICE_SIGNALS] per call; a batch that cannot be sent is dropped and the call goes on.
     */
    private fun startIce(a: Active) {
        a.iceJob = scope.launch {
            if (a.localQueue.isNotEmpty()) a.iceKick.trySend(Unit)
            while (a.iceKick.receiveCatching().isSuccess) {
                delay(timings.iceBatchMs)
                sendIceBatch(a)
                if (a.localQueue.isNotEmpty()) a.iceKick.trySend(Unit)
            }
        }
    }

    private suspend fun sendIceBatch(a: Active) {
        val batch = ArrayList<IceCandidate>()
        while (batch.size < MAX_ICE_PER_SIGNAL) batch += a.localQueue.poll() ?: break
        if (batch.isEmpty()) return
        if (a.iceSignalsSent >= MAX_ICE_SIGNALS) {
            a.localQueue.clear()
            return
        }
        a.iceSignalsSent++
        try {
            signalling.send(a.conversationId, signal(a.callId, CallAction.ICE, a.video, candidates = batch))
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            // dropped: a lost candidate costs little, a rate limit must not end the call
        }
    }

    // --- ending -------------------------------------------------------------------------------------------------------

    private suspend fun fail(a: Active, hangup: Boolean) = lock.withLock {
        end(a, EndReason.FAILED, CallAction.HANGUP.takeIf { hangup })
    }

    /**
     * Ends [a] (call with the lock held): cancels every timer and job, closes the media session and shows [reason]. [notify]
     * is a signal for the other side ([CallAction.HANGUP] or [CallAction.REJECT]), sent best effort after a setup that is
     * still sending the offer or answer has finished, so it cannot overtake it.
     */
    private fun end(a: Active, reason: EndReason, notify: String? = null) {
        if (active !== a) return
        active = null
        a.closed = true
        a.ringJob?.cancel()
        a.connectJob?.cancel()
        a.remoteJob?.cancel()
        a.iceJob?.cancel()
        a.mediaJob?.cancel()
        a.iceKick.close()
        a.mediaEvents.close()
        val setup = a.setupJob?.also { it.cancel() }
        try {
            a.session?.close()
        } catch (ex: Exception) {
            // closing is best effort; the call is over either way
        }
        if (notify != null) {
            scope.launch {
                setup?.join()
                sendQuietly(a.conversationId, signal(a.callId, notify, a.video))
            }
        }
        callNotice(a, reason)?.let { notice ->
            scope.launch { recordQuietly(a.conversationId, a.callId, notice) }
        }
        val ended = CallState.Ended(a.callId, reason)
        _state.value = ended
        _muted.value = false
        lingerJob?.cancel()
        lingerJob = scope.launch {
            delay(timings.endedLingerMs)
            if (_state.value === ended) _state.value = CallState.Idle
        }
    }

    /**
     * The history notice of a call that ends as [reason], or null when it leaves none: a call that connected leaves its
     * duration whatever ended it; one that did not leaves "missed", "no answer", "busy" or "declined". A call answered on
     * another own device, and one whose media setup failed before it connected, leave nothing (the former is that device's
     * call, the latter would be a notice for a call nobody had).
     */
    private fun callNotice(a: Active, reason: EndReason): SystemEvent.Call? {
        val connected = a.connectedAt
        val outcome = when {
            connected != null -> CallOutcome.COMPLETED
            reason == EndReason.MISSED -> CallOutcome.MISSED
            reason == EndReason.NO_ANSWER -> CallOutcome.NO_ANSWER
            reason == EndReason.BUSY -> CallOutcome.BUSY
            reason == EndReason.REJECTED -> CallOutcome.REJECTED
            reason == EndReason.HANGUP_LOCAL && a.phase == Phase.RINGING_OUT -> CallOutcome.NO_ANSWER // we gave up while it rang
            else -> return null
        }
        val seconds = if (connected != null) ((clock() - connected) / MS_PER_SECOND).coerceAtLeast(0) else 0L
        return SystemEvent.Call(a.peerUserId, outcome, a.video, seconds)
    }

    /** Local only: nothing is sent to the server for a history notice. */
    private suspend fun recordQuietly(conversationId: String, callId: String, notice: SystemEvent.Call) {
        try {
            signalling.recordCall(conversationId, callId, notice)
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            // a missing history line must not matter more than the call
        }
    }

    /** A signal stamped with our clock, which the receiver checks for offers. */
    private fun signal(
        callId: String,
        action: String,
        video: Boolean,
        sdp: String? = null,
        candidates: List<IceCandidate>? = null,
    ) = CallSignal(callId, action, video, sdp = sdp, candidates = candidates, sentAt = clock())

    private suspend fun sendQuietly(conversationId: String, signal: CallSignal) {
        try {
            signalling.send(conversationId, signal)
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            // a hangup, reject or busy that cannot be sent is not worth more than the other side's own timeout
        }
    }

    private companion object {
        const val MAX_SIGNAL_AGE_MS = 60_000L
        const val MAX_SEEN = 64
        const val MAX_CLOCK_SKEW_MS = 5 * 60_000L
        const val BUSY_WINDOW_MS = 10_000L
        const val RING_WINDOW_MS = 60_000L
        const val MAX_RINGS = 3
        const val MAX_ICE_PER_SIGNAL = 50
        const val MAX_ICE_SIGNALS = 20
        const val MAX_PENDING_REMOTE = 200
        const val MS_PER_SECOND = 1000L
    }
}
