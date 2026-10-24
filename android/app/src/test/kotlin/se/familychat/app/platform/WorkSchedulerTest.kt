package se.familychat.app.platform

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
import se.familychat.app.platform.work.WorkScheduler
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
}
