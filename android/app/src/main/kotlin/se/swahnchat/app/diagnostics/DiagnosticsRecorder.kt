package se.swahnchat.app.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.di.ApplicationScope
import se.swahnchat.core.client.EngineEvent
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.client.MessageStatus
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.call.CallManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns what the engine and the call manager already say into log events: the realtime connection coming and going, sync runs,
 * messages that failed for good and the phases of a call. It reads state and events only; the core modules know nothing about the log.
 */
@Singleton
class DiagnosticsRecorder @Inject constructor(
    private val engine: SwahnChatEngine,
    private val calls: CallManager,
    private val store: LocalStore,
    private val prefs: AppPrefs,
    private val log: EventLog,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val reportedFailures = LinkedHashSet<String>()

    fun start() {
        log.record(AppStarted(se.swahnchat.app.BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT))
        scope.launch { safely { engine.realtimeState.collect { log.record(RealtimeChange(it)) } } }
        scope.launch {
            safely {
                calls.state.map { it.toPhase() }.distinctUntilChanged().collect { (phase, reason) -> log.record(CallChange(phase, reason)) }
            }
        }
        scope.launch {
            safely {
                engine.events.collect { event ->
                    when (event) {
                        is EngineEvent.SyncCompleted -> {
                            prefs.lastSyncAt = System.currentTimeMillis()
                            if (event.applied > 0) log.record(SyncCycle(SyncTrigger.REALTIME, event.applied, ErrorClass.NONE))
                        }
                        is EngineEvent.MessageChanged -> noteIfFailed(event.messageId)
                        else -> Unit
                    }
                }
            }
        }
    }

    private suspend fun noteIfFailed(messageId: String) {
        val m = store.getMessage(messageId) ?: return
        if (!m.outgoing || m.status != MessageStatus.FAILED) return
        synchronized(reportedFailures) {
            if (!reportedFailures.add(messageId)) return
            while (reportedFailures.size > MAX_REMEMBERED) reportedFailures.remove(reportedFailures.first())
        }
        log.record(SendFailed(log.ref(m.conversationId), m.failureReason))
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // diagnostics must never take the app down
        }
    }

    private companion object {
        const val MAX_REMEMBERED = 200
    }
}
