package se.familychat.app.platform.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** All background work of the app, in one place. */
@Singleton
class WorkScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val wm: WorkManager get() = WorkManager.getInstance(context)

    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Delivers the outbox as soon as there is a network, retrying with exponential backoff. Enqueued on every send and
     * when connectivity returns. [ExistingWorkPolicy.KEEP]: a run that is already queued or running picks up the new entry,
     * because it loops until the outbox is empty.
     */
    fun enqueueOutbox() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(OUTBOX, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Expedited one-shot sync, triggered by a push wake-up. A second push while one runs queues another run after it,
     * so an event that arrived after the running sync read its last page is not missed.
     */
    fun enqueueSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(needsNetwork)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(SYNC, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Safety net: a sync every 15 minutes (WorkManager's minimum) even if push never arrives. */
    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        wm.cancelUniqueWork(OUTBOX)
        wm.cancelUniqueWork(SYNC)
        wm.cancelUniqueWork(PERIODIC)
    }

    companion object {
        const val OUTBOX = "outbox"
        const val SYNC = "sync"
        const val PERIODIC = "periodic-sync"
        const val BACKOFF_SECONDS = 30L
    }
}

/** Outcome of a background run before it is mapped to WorkManager's `Result`. */
enum class BackgroundResult { DONE, RETRY }
