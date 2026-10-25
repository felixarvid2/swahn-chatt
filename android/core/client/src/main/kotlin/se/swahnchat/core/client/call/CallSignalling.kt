package se.swahnchat.core.client.call

import kotlinx.coroutines.flow.Flow
import se.swahnchat.core.client.CallSignalEvent
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.SystemEvent
import se.swahnchat.core.model.CallSignal
import se.swahnchat.core.model.TurnCredentials

/** What [CallManager] needs from the messaging engine; the engine's is [EngineCallSignalling], tests use a fake. */
interface CallSignalling {
    /** Decrypted, checked signals from the other member and from this account's other devices. */
    val signals: Flow<CallSignalEvent>

    /** Sends [signal] to all devices of both members of the direct conversation right away; throws when it cannot. */
    suspend fun send(conversationId: String, signal: CallSignal)

    /** The other member of the direct conversation, or null when it is unknown or not direct. */
    suspend fun peerOf(conversationId: String): String?

    /** Fresh TURN/STUN credentials for one call; throws when they cannot be fetched. */
    suspend fun turnCredentials(): TurnCredentials

    /** Adds the history [notice] of the call [callId] to the conversation, on this phone only (nothing is sent). */
    suspend fun recordCall(conversationId: String, callId: String, notice: SystemEvent.Call)

    /** The current time on the server's clock, to judge how old a signal is. */
    fun serverNow(): Long
}

/** [CallSignalling] on top of a [SwahnChatEngine]. */
class EngineCallSignalling(private val engine: SwahnChatEngine) : CallSignalling {
    override val signals: Flow<CallSignalEvent> get() = engine.callSignals
    override suspend fun send(conversationId: String, signal: CallSignal) = engine.sendCallSignal(conversationId, signal)
    override suspend fun peerOf(conversationId: String): String? = engine.directPeer(conversationId)
    override suspend fun turnCredentials(): TurnCredentials = engine.fetchTurnCredentials()
    override suspend fun recordCall(conversationId: String, callId: String, notice: SystemEvent.Call) =
        engine.recordCallNotice(conversationId, callId, notice)

    override fun serverNow(): Long = engine.serverNow()
}
