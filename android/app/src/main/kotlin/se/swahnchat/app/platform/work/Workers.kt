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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import se.swahnchat.app.platform.notify.Notifier
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.CallState

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

/**
 * Keeps the process alive while a call rings and the app is in the background: expedited work that suspends until the state
 * leaves "incoming" (answered, declined, missed, hung up) or [RING_LIMIT_MS] pass. It does nothing else (no sync of its own;
 * while a call is active in the background the app keeps the realtime connection up, see `realtimeWanted`), so the caller's
 * hang-up reaches the manager. When the expedited quota is spent it runs as ordinary work, which the system may delay or stop.
 */
@HiltWorker
class CallRingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val calls: CallManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        withTimeoutOrNull(RING_LIMIT_MS) { calls.state.first { it !is CallState.Incoming } }
        return Result.success()
    }

    /** Needed for expedited work on Android 11 and older, which runs as a short foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo = syncForegroundInfo(applicationContext)

    companion object {
        /** A little over the ring time (45 s) of the call manager. */
        const val RING_LIMIT_MS = 50_000L
    }
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
