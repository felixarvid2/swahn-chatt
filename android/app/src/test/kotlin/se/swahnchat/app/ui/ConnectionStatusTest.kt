package se.swahnchat.app.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import se.swahnchat.app.data.CONNECTING_SHOWN_AFTER_MS
import se.swahnchat.app.data.OFFLINE_SHOWN_AFTER_MS
import se.swahnchat.app.data.connectionUi
import se.swahnchat.app.data.settled
import se.swahnchat.app.ui.model.ConnectionUi
import se.swahnchat.core.network.RealtimeState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStatusTest {
    @Test fun `an open socket hides the banner whatever the phone says`() {
        assertEquals(ConnectionUi.HIDDEN, connectionUi(online = true, realtime = RealtimeState.CONNECTED))
        assertEquals(ConnectionUi.HIDDEN, connectionUi(online = false, realtime = RealtimeState.CONNECTED))
    }

    @Test fun `no network is offline, a network without a socket is connecting`() {
        assertEquals(ConnectionUi.OFFLINE, connectionUi(online = false, realtime = RealtimeState.DISCONNECTED))
        assertEquals(ConnectionUi.OFFLINE, connectionUi(online = false, realtime = RealtimeState.CONNECTING))
        assertEquals(ConnectionUi.CONNECTING, connectionUi(online = true, realtime = RealtimeState.DISCONNECTED))
        assertEquals(ConnectionUi.CONNECTING, connectionUi(online = true, realtime = RealtimeState.CONNECTING))
    }

    @Test fun `the background and a logout show nothing`() {
        for (online in listOf(true, false)) {
            assertEquals(ConnectionUi.HIDDEN, connectionUi(online, RealtimeState.STOPPED))
            assertEquals(ConnectionUi.HIDDEN, connectionUi(online, RealtimeState.LOGGED_OUT))
        }
    }

    @Test fun `a short reconnect never flashes a banner, a long one shows it, recovery hides it at once`() = runTest {
        val source = MutableSharedFlow<ConnectionUi>()
        val seen = ArrayList<ConnectionUi>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { source.settled().collect { seen += it } }

        source.emit(ConnectionUi.CONNECTING)
        advanceTimeBy(CONNECTING_SHOWN_AFTER_MS - 100)
        source.emit(ConnectionUi.HIDDEN)
        advanceTimeBy(5_000)
        assertEquals(listOf(ConnectionUi.HIDDEN), seen, "connected again within the delay: no banner")

        seen.clear()
        source.emit(ConnectionUi.CONNECTING)
        advanceTimeBy(CONNECTING_SHOWN_AFTER_MS + 1)
        assertEquals(listOf(ConnectionUi.CONNECTING), seen)
        source.emit(ConnectionUi.OFFLINE)
        advanceTimeBy(OFFLINE_SHOWN_AFTER_MS + 1)
        assertEquals(listOf(ConnectionUi.CONNECTING, ConnectionUi.OFFLINE), seen)
        source.emit(ConnectionUi.HIDDEN)
        assertEquals(ConnectionUi.HIDDEN, seen.last(), "hiding is immediate")
        job.cancel()
    }
}
