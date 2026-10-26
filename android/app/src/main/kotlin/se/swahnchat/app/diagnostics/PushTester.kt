package se.swahnchat.app.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import se.swahnchat.app.data.UiError
import se.swahnchat.app.data.toUiError
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.model.Device
import javax.inject.Inject
import javax.inject.Singleton

/** What "Skicka testnotis" found out. */
sealed interface PushTestResult {
    /** The push server took it (a 2xx answer). */
    data class Delivered(val status: Int, val at: Long) : PushTestResult

    /** The push server refused it (any other answer than 2xx). */
    data class Refused(val status: Int) : PushTestResult

    /** The push server did not answer at all (network error or timeout on the server's side). */
    data object NoAnswer : PushTestResult

    /** The server queued it but has not reported an outcome yet. */
    data object Waiting : PushTestResult

    /** No endpoint is registered for this device, or the server cannot push. */
    data object NotConfigured : PushTestResult
    data object RateLimited : PushTestResult
    data class Failed(val error: UiError) : PushTestResult
}

/** The two calls the test needs; the engine implements them, a test fakes them. */
interface PushTestApi {
    suspend fun currentDevice(): Device
    suspend fun sendTestPush()
}

/**
 * `POST /devices/me/push/test`, then reads the outcome from `GET /devices/me` (`last_push_at`, `last_push_status`) until it changed
 * or the patience ran out. The server allows three tests per ten minutes.
 */
class PushTester(
    private val api: PushTestApi,
    private val log: EventLog,
    private val pollDelayMs: Long = 1_000,
    private val attempts: Int = 8,
) {
    @Singleton
    class EngineApi @Inject constructor(private val engine: SwahnChatEngine) : PushTestApi {
        override suspend fun currentDevice(): Device = engine.currentDevice()
        override suspend fun sendTestPush() = engine.sendTestPush()
    }

    @Singleton
    class Factory @Inject constructor(private val api: EngineApi, private val log: EventLog) {
        fun create() = PushTester(api, log)
    }

    suspend fun run(): PushTestResult {
        val result = try {
            val before = api.currentDevice().lastPushAt
            api.sendTestPush()
            waitForOutcome(before)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (e.toUiError()) {
                UiError.PUSH_NOT_CONFIGURED -> PushTestResult.NotConfigured
                UiError.RATE_LIMITED -> PushTestResult.RateLimited
                else -> PushTestResult.Failed(e.toUiError())
            }
        }
        log.record(
            when (result) {
                is PushTestResult.Delivered -> PushTest(result.status)
                is PushTestResult.Refused -> PushTest(result.status, ErrorClass.REFUSED_BY_SERVER)
                PushTestResult.NoAnswer -> PushTest(0, ErrorClass.OFFLINE)
                PushTestResult.Waiting -> PushTest(0, ErrorClass.TIMEOUT)
                PushTestResult.NotConfigured -> PushTest(0, ErrorClass.INVALID_STATE)
                PushTestResult.RateLimited -> PushTest(0, ErrorClass.RATE_LIMITED)
                is PushTestResult.Failed -> PushTest(0, ErrorClass.UNKNOWN)
            },
        )
        return result
    }

    private suspend fun waitForOutcome(before: Long?): PushTestResult {
        repeat(attempts) {
            delay(pollDelayMs)
            val d = api.currentDevice()
            val at = d.lastPushAt
            val status = d.lastPushStatus
            if (at != null && at != before && status != null) {
                return when (status) {
                    0 -> PushTestResult.NoAnswer
                    in 200..299 -> PushTestResult.Delivered(status, at)
                    else -> PushTestResult.Refused(status)
                }
            }
        }
        return PushTestResult.Waiting
    }
}
