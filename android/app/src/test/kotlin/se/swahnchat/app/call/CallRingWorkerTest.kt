package se.swahnchat.app.call

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.swahnchat.app.platform.work.CallRingWorker
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.CallState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The ring worker lives exactly as long as the phone rings: it ends when the state leaves "incoming", whichever way. */
@RunWith(RobolectricTestRunner::class)
class CallRingWorkerTest {
    private lateinit var context: Context
    private lateinit var signalling: TestSignalling
    private lateinit var calls: CallManager
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        signalling = TestSignalling()
        calls = CallManager(signalling, TestMedia(), scope)
        calls.start()
    }

    @After fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun worker(): CallRingWorker = TestListenableWorkerBuilder<CallRingWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                CallRingWorker(appContext, workerParameters, calls)
        })
        .build()

    private suspend fun ringing(id: String) {
        signalling.offer(id)
        withTimeout(5_000) { while (calls.state.value !is CallState.Incoming) delay(10) }
    }

    @Test fun `it keeps running while the phone rings and finishes when the caller hangs up`() = runBlocking<Unit> {
        ringing("c1")
        val result = async(Dispatchers.Default) { worker().doWork() }
        delay(300)
        assertFalse(result.isCompleted, "the worker must stay alive while the call is ringing")
        signalling.hangup("c1")
        assertEquals(ListenableWorker.Result.success(), withTimeout(5_000) { result.await() })
    }

    @Test fun `it finishes when the call is answered`() = runBlocking<Unit> {
        ringing("c2")
        val result = async(Dispatchers.Default) { worker().doWork() }
        delay(200)
        calls.accept()
        assertEquals(ListenableWorker.Result.success(), withTimeout(5_000) { result.await() })
    }

    @Test fun `it finishes at once when nothing rings`() = runBlocking<Unit> {
        assertEquals(ListenableWorker.Result.success(), withTimeout(5_000) { worker().doWork() })
    }

    @Test fun `the limit is just over the ring time and the foreground info of old Android versions is the sync notification`() = runBlocking<Unit> {
        assertTrue(CallRingWorker.RING_LIMIT_MS in 46_000..60_000)
        val info = worker().getForegroundInfo()
        assertEquals(NotificationCompat.PRIORITY_MIN, info.notification.priority)
    }
}
