@file:Suppress("MaxLineLength")

package se.swahnchat.app.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import se.swahnchat.app.data.UiError
import se.swahnchat.core.model.Device
import se.swahnchat.core.network.ApiException
import se.swahnchat.core.network.RealtimeState
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagnosticsReportTest {
    private val report = DiagnosticsReport(
        appVersion = "0.5.0", buildType = "release", androidSdk = 34, serverHost = "hemma.example.net", reachability = Reachability.REACHABLE, latencyMs = 38,
        clockOffsetMs = -1500, realtime = RealtimeState.CONNECTED, lastSyncAt = 1_700_000_000_000, distributorInstalled = true, distributorLabel = "ntfy",
        endpointRegistered = true, lastPushAt = 1_700_000_100_000, lastPushStatus = 201, notificationsAllowed = true, batteryUnrestricted = false,
        outboxSize = 3, databaseBytes = 4096 * 10, now = 1_700_000_200_000,
    )

    @Test fun `the summary has every line the screen has`() {
        val t = report.toText(ZoneOffset.UTC)
        for (part in listOf(
            "App: 0.5.0 (release), Android API 34", "Server: hemma.example.net", "Reachable: yes (38 ms)", "Clock offset to server: -1500 ms",
            "Realtime connection: CONNECTED", "Last sync: 2023-11-14", "Push distributor installed: yes (ntfy)",
            "Push endpoint registered with the server: yes", "answer 201", "Notifications allowed: yes", "Battery optimisation off for the app: no",
            "Exact alarms: not used", "Messages waiting in the outbox: 3", "Local database: 40 KiB",
        )) assertTrue(part in t, "missing: $part in\n$t")
    }

    @Test fun `an address that is an IP number is not written into the summary`() {
        for (host in listOf("192.168.1.23", "100.64.0.7", "[2001:db8::1]", "2001:db8::1234")) {
            val t = report.copy(serverHost = host).toText()
            assertFalse(host.trim('[', ']') in t, host)
            assertTrue("Server: IP address" in t)
        }
        assertEquals("hemma.tail1234.ts.net", DiagnosticsReport.publicHost("hemma.tail1234.ts.net"))
    }

    @Test fun `the summary carries nothing a report must not carry`() {
        val t = report.copy(serverHost = "family.example.org").toText()
        for (forbidden in listOf("http", "://", "token", "password", "endpoint=", "Bearer", "@")) assertFalse(forbidden in t, forbidden)
        // the one place the word endpoint appears says yes or no, nothing else
        assertTrue(Regex("endpoint registered with the server: (yes|no)").containsMatchIn(t))
    }

    @Test fun `not yet known values are said so`() {
        val t = DiagnosticsReport(appVersion = "1", buildType = "debug", androidSdk = 26).toText()
        assertTrue("Reachable: not checked" in t)
        assertTrue("Last sync: never" in t)
        assertTrue("Clock offset to server: unknown" in t)
        assertTrue("Server: unknown" in t)
    }

    @Test fun `the export is the summary followed by the log`() {
        val log = EventLog(null)
        log.record(PushReceived())
        log.record(OutboxPass(1, 0, 0, false))
        val text = exportText(report, log)
        assertTrue(text.startsWith("Swahn Chat diagnostics"))
        assertTrue("Log (newest last, 2 entries)" in text)
        assertTrue("push_received worker=true" in text)
        assertTrue(text.indexOf("Local database") < text.indexOf("push_received"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PushTesterTest {
    private fun device(at: Long?, status: Int?) = Device("d", "u", 1, "P", lastPushAt = at, lastPushStatus = status)

    private class FakeApi(val steps: ArrayDeque<Device>, val onSend: () -> Unit = {}) : PushTestApi {
        var sent = 0
        override suspend fun currentDevice(): Device = if (steps.size > 1) steps.removeFirst() else steps.first()
        override suspend fun sendTestPush() { sent++; onSend() }
    }

    @Test fun `a push that the push server took is delivered`() = runTest {
        val api = FakeApi(ArrayDeque(listOf(device(null, null), device(null, null), device(50, 201))))
        val log = EventLog(null)
        val r = PushTester(api, log, pollDelayMs = 10, attempts = 5).run()
        assertEquals(PushTestResult.Delivered(201, 50), r)
        assertEquals(1, api.sent)
        assertTrue("push_test status=201 result=ok" in log.entries().single().line)
    }

    @Test fun `an older outcome is not taken for this one`() = runTest {
        val api = FakeApi(ArrayDeque(listOf(device(10, 404))))
        val r = PushTester(api, EventLog(null), pollDelayMs = 10, attempts = 3).run()
        assertEquals(PushTestResult.Waiting, r)
    }

    @Test fun `a refusal and silence from the push server are told apart`() = runTest {
        assertEquals(PushTestResult.Refused(404), PushTester(FakeApi(ArrayDeque(listOf(device(1, null), device(2, 404)))), EventLog(null), 5, 3).run())
        assertEquals(PushTestResult.NoAnswer, PushTester(FakeApi(ArrayDeque(listOf(device(1, null), device(2, 0)))), EventLog(null), 5, 3).run())
    }

    @Test fun `the errors of the server become results`() = runTest {
        fun failing(e: Exception) = object : PushTestApi {
            override suspend fun currentDevice() = device(null, null)
            override suspend fun sendTestPush() { throw e }
        }
        assertEquals(PushTestResult.NotConfigured, PushTester(failing(ApiException.Conflict("push_not_configured", "")), EventLog(null), 1, 1).run())
        assertEquals(PushTestResult.RateLimited, PushTester(failing(ApiException.RateLimited("slow", 5)), EventLog(null), 1, 1).run())
        assertEquals(PushTestResult.Failed(UiError.NETWORK), PushTester(failing(java.io.IOException("x")), EventLog(null), 1, 1).run())
        assertIs<PushTestResult.Failed>(PushTester(failing(IllegalStateException()), EventLog(null), 1, 1).run())
    }
}
