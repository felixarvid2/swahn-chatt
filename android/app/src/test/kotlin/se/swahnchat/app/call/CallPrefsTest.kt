package se.swahnchat.app.call

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.CallState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "relay calls via the server" setting: off by default, kept, and read by the call manager when a media session is created. */
@RunWith(RobolectricTestRunner::class)
class CallPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(): AppPrefs {
        context.getSharedPreferences("swahnchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        return AppPrefs(context)
    }

    @Test fun `relaying calls is off by default and survives a restart`() {
        val p = prefs()
        assertFalse(p.callsRelayOnly.value)
        p.setCallsRelayOnly(true)
        assertTrue(p.callsRelayOnly.value)
        assertTrue(AppPrefs(context).callsRelayOnly.value, "kept in the preferences file")
        p.setCallsRelayOnly(false)
        assertFalse(AppPrefs(context).callsRelayOnly.value)
    }

    @Test fun `the switch is read for every call, as CallModule wires it`() = runBlocking<Unit> {
        val p = prefs()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val signalling = TestSignalling()
        val media = TestMedia()
        // exactly the wiring of CallModule: relayOnly = { prefs.callsRelayOnly.value }
        val calls = CallManager(signalling, media, scope, relayOnly = { p.callsRelayOnly.value }).also { it.start() }
        calls.startCall(DIRECT, video = false)
        withTimeout(5_000) { while (media.last == null) delay(10) }
        assertEquals(false, media.last!!.relayOnly)
        calls.hangup()
        withTimeout(5_000) { while (calls.state.value is CallState.Ended) delay(50) }
        p.setCallsRelayOnly(true)
        media.last = null
        calls.startCall(DIRECT, video = false)
        withTimeout(5_000) { while (media.last == null) delay(10) }
        assertEquals(true, media.last!!.relayOnly)
        calls.hangup()
        scope.coroutineContext[Job]?.cancel()
    }
}
