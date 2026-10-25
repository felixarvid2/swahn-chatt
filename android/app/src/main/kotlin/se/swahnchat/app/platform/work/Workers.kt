package se.swahnchat.app.platform.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import se.swahnchat.app.R
import se.swahnchat.app.data.SessionManager
import se.swahnchat.app.platform.notify.Notifier

/** Result of one background run, mapped to WorkManager's. */
private fun BackgroundResult.toWork(attempt: Int, maxAttempts: Int): androidx.work.ListenableWorker.Result = when (this) {
    BackgroundResult.DONE -> androidx.work.ListenableWorker.Result.success()
    BackgroundResult.RETRY ->
        if (attempt + 1 >= maxAttempts) androidx.work.ListenableWorker.Result.success()
        else androidx.work.ListenableWorker.Result.retry()
}

/** Sends everything in the outbox; retried by WorkManager (exponential backoff) while the server is unreachable. */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val session: SessionManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = session.runOutbox().toWork(runAttemptCount, MAX_OUTBOX_ATTEMPTS)

    private companion object {
        // Offline messages are not lost when we give up: the outbox is also drained on connectivity changes, on the next
        // sync and when the app opens.
        const val MAX_OUTBOX_ATTEMPTS = 12
    }
}

/** Expedited sync after a push wake-up: pull, ack, store, notify, send the outbox, top up keys. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val session: SessionManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = session.runSync().toWork(runAttemptCount, 3)

    /** Needed for expedited work on Android 11 and older, which runs as a short foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo = syncForegroundInfo(applicationContext)
}

/** Removes the disappearing messages that are due, and arranges for the next removal. */
@HiltWorker
class ExpiryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val session: SessionManager,
    private val expiry: ExpiryManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        session.awaitRestored()
        return try {
            expiry.purgeAndSchedule()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** Every 15 minutes, the safety net behind push. */
@HiltWorker
class PeriodicSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val session: SessionManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        session.runSync()
        return Result.success() // the next period tries again
    }
}

internal fun syncForegroundInfo(context: Context): ForegroundInfo {
    val notification: Notification = NotificationCompat.Builder(context, Notifier.CHANNEL_SYNC)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.notification_sync_title))
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .setSilent(true)
        .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(Notifier.ID_SYNC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(Notifier.ID_SYNC, notification)
    }
}
