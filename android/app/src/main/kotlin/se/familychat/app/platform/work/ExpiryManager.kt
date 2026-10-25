package se.familychat.app.platform.work

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import se.familychat.app.di.ApplicationScope
import se.familychat.app.media.AttachmentPreparer
import se.familychat.core.client.FamilyChatEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes disappearing messages from this phone when their time has come, and keeps track of when that is.
 *
 * What is removed is decided by the engine, from `expires_in` of messages that members sent encrypted and the time the
 * message was first displayed here; nothing the server says about timers is involved (SECURITY.md). Three triggers: an
 * in-process timer while the app lives (precise), a WorkManager job for when it does not ([WorkScheduler.scheduleExpiry]),
 * and a check at app start and whenever the app comes to the front.
 */
@Singleton
class ExpiryManager @Inject constructor(
    private val engine: FamilyChatEngine,
    private val work: WorkScheduler,
    private val preparer: AttachmentPreparer,
    @ApplicationScope private val scope: CoroutineScope,
    private val clock: ExpiryClock,
) {
    private var timer: Job? = null
    private val purging = Mutex()

    /** Removes what is due now (text, downloaded files, the copies of files you sent, search rows), then schedules the next. */
    suspend fun purgeAndSchedule() {
        if (engine.account.value == null) return
        // One purge at a time: the timer and the WorkManager job can be due together, and a caller must not return while
        // another purge is still removing files. Not cancellable, so a new schedule cannot cut it off between message and file.
        purging.withLock {
            withContext(NonCancellable) {
                val removed = engine.purgeExpired()
                for (m in removed) for (a in m.attachments) preparer.deleteOutgoingCopy(a.localPath)
            }
        }
        scheduleNext()
    }

    /** Arranges the next removal: the earliest time of any message that has one. */
    suspend fun scheduleNext() {
        val next = engine.nextExpiry()
        synchronized(this) { timer?.cancel(); timer = null }
        if (next == null) {
            work.cancelExpiry()
            return
        }
        work.scheduleExpiry(next, clock.now())
        val job = scope.launch {
            delay((next - clock.now()).coerceAtLeast(0))
            purgeAndSchedule()
        }
        synchronized(this) { timer = job }
    }

    /** Messages were just displayed and may have started a clock ([earliest] from `markDisplayed`). */
    fun onDisplayed(earliest: Long?) {
        if (earliest != null) scope.launch { scheduleNext() }
    }
}

/** The clock of [ExpiryManager]; a class of its own so tests can set the time. */
open class ExpiryClock @Inject constructor() {
    open fun now(): Long = System.currentTimeMillis()
}
