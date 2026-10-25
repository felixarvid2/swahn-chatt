package se.swahnchat.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.swahnchat.app.platform.work.WorkScheduler
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WorkSchedulerTest {
    private lateinit var context: Context
    private lateinit var scheduler: WorkScheduler

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build())
        scheduler = WorkScheduler(context)
    }

    private fun infos(unique: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(unique).get()

    @Test fun `the outbox worker needs a network and backs off exponentially`() {
        scheduler.enqueueOutbox()
        val info = infos(WorkScheduler.OUTBOX).single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        // enqueueing again while one is queued keeps the single request
        scheduler.enqueueOutbox()
        assertEquals(1, infos(WorkScheduler.OUTBOX).size)
    }

    @Test fun `the periodic safety net runs every 15 minutes with a network constraint`() {
        scheduler.schedulePeriodicSync()
        val info = infos(WorkScheduler.PERIODIC).single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.tags.any { it.endsWith("PeriodicSyncWorker") })
        scheduler.schedulePeriodicSync()
        assertEquals(1, infos(WorkScheduler.PERIODIC).size)
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, info.state)
    }

    @Test fun `a push enqueues an expedited sync and a second push queues another run`() {
        scheduler.enqueueSync()
        scheduler.enqueueSync()
        val list = infos(WorkScheduler.SYNC)
        assertTrue(list.isNotEmpty())
        assertTrue(list.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
        assertTrue(list.all { it.constraints.requiresBatteryNotLow().not() })
    }

    @Test fun `cancelAll removes the queued work`() {
        scheduler.enqueueOutbox(); scheduler.enqueueSync(); scheduler.schedulePeriodicSync()
        scheduler.cancelAll()
        for (name in listOf(WorkScheduler.OUTBOX, WorkScheduler.SYNC, WorkScheduler.PERIODIC)) {
            assertTrue(infos(name).all { it.state == androidx.work.WorkInfo.State.CANCELLED }, name)
        }
    }

    @Test fun `the expiry job waits until the next message is due, needs no network, and a later schedule replaces it`() {
        val now = 1_000_000L
        scheduler.scheduleExpiry(atMillis = now + 3_600_000, now = now)
        val first = infos(WorkScheduler.EXPIRY).single()
        assertEquals(NetworkType.NOT_REQUIRED, first.constraints.requiredNetworkType)
        assertTrue(first.tags.any { it.endsWith("ExpiryWorker") })
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, first.state)
        assertEquals(3_600_000L, first.initialDelayMillis)
        scheduler.scheduleExpiry(atMillis = now + 60_000, now = now)
        val all = infos(WorkScheduler.EXPIRY)
        assertEquals(1, all.count { it.state == androidx.work.WorkInfo.State.ENQUEUED })
        assertTrue(all.single { it.state == androidx.work.WorkInfo.State.ENQUEUED }.id != first.id, "replaced")
        assertEquals(60_000L, all.single { it.state == androidx.work.WorkInfo.State.ENQUEUED }.initialDelayMillis)
        scheduler.cancelExpiry()
        assertTrue(infos(WorkScheduler.EXPIRY).all { it.state == androidx.work.WorkInfo.State.CANCELLED })
    }

    @Test fun `cancelAll takes the expiry job along`() {
        scheduler.scheduleExpiry(System.currentTimeMillis() + 10_000)
        scheduler.cancelAll()
        assertTrue(infos(WorkScheduler.EXPIRY).all { it.state == androidx.work.WorkInfo.State.CANCELLED })
    }
}
