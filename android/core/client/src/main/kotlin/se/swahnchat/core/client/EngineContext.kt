package se.swahnchat.core.client

import kotlinx.coroutines.flow.MutableSharedFlow
import se.swahnchat.core.crypto.KeyManager
import se.swahnchat.core.crypto.MessageCrypto
import se.swahnchat.core.crypto.PersistentSignalProtocolStore
import se.swahnchat.core.crypto.ProtocolStorage
import se.swahnchat.core.network.SwahnChatApi
import se.swahnchat.core.network.ServerConnection

/** Shared state and collaborators of the engine's parts. Not public API. */
internal class EngineContext(
    val store: LocalStore,
    val protocolStorage: ProtocolStorage,
    val config: EngineConfig,
) {
    val signalStore = PersistentSignalProtocolStore(protocolStorage)
    val crypto = MessageCrypto(signalStore)
    val keyManager = KeyManager(signalStore, protocolStorage, config.clock)
    val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 512)

    /** Decrypted call signals; time-critical, so they bypass [events] and the store (see [CallSignalEvent]). */
    val callSignals = MutableSharedFlow<CallSignalEvent>(extraBufferCapacity = 64)

    @Volatile var account: Account? = null
    @Volatile var connection: ServerConnection? = null

    val api: SwahnChatApi get() = connection?.api ?: throw NotSignedInException()
    fun requireAccount(): Account = account ?: throw NotSignedInException()

    fun emit(e: EngineEvent) {
        events.tryEmit(e)
    }

    fun now(): Long = config.clock()

    /** [now] moved by the learned difference between the server's clock and ours (0 until a response was seen). */
    fun serverNow(): Long = now() + (connection?.serverClock?.offsetMs ?: 0L)
}

/** The operation needs a signed-in account (call login/redeemInvite/restoreSession first). */
class NotSignedInException : IllegalStateException("Not signed in")
