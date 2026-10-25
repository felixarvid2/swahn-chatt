package se.swahnchat.app.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import se.swahnchat.app.R
import se.swahnchat.app.ui.components.LocationShareUi
import se.swahnchat.app.ui.screens.LocationShareController
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocationShareControllerTest {
    private class FakeSource(var result: suspend (Boolean) -> LocationFix) : LocationSource {
        val asked = ArrayList<Boolean>()
        var cancelled = false
        override suspend fun currentFix(precise: Boolean): LocationFix {
            asked += precise
            try {
                return result(precise)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    private fun TestScope.controller(source: LocationSource, notices: MutableList<Int>) =
        LocationShareController(source, this, { notices += it })

    @Test fun `choose, fetch one fix, preview with the accuracy, nothing is sent from here`() = runTest {
        val source = FakeSource { LocationFix(59.3293, 18.0686, 7.3) }
        val notices = ArrayList<Int>()
        val c = controller(source, notices)
        assertNull(c.state.value)
        c.open()
        assertEquals(LocationShareUi.Choose, c.state.value)
        c.fetch(precise = true)
        assertEquals(LocationShareUi.Fetching(true), c.state.value)
        advanceUntilIdle()
        val preview = assertIs<LocationShareUi.Preview>(c.state.value)
        assertEquals(59.3293, preview.location.lat); assertEquals(8.0, preview.location.accuracyM)
        assertEquals(listOf(true), source.asked, "exactly one request")
        assertTrue(notices.isEmpty())
        c.close()
        assertNull(c.state.value)
    }

    @Test fun `an approximate request asks for the approximate position only`() = runTest {
        val source = FakeSource { LocationFix(1.0, 2.0, 1500.0) }
        val c = controller(source, ArrayList())
        c.open(); c.fetch(precise = false); advanceUntilIdle()
        assertEquals(listOf(false), source.asked)
    }

    @Test fun `no fix within 30 seconds ends with the timeout message`() = runTest {
        val source = FakeSource { awaitCancellation() }
        val notices = ArrayList<Int>()
        val c = controller(source, notices)
        c.open(); c.fetch(true)
        advanceTimeBy(LocationSource.TIMEOUT_MS - 1)
        assertIs<LocationShareUi.Fetching>(c.state.value)
        advanceTimeBy(2)
        advanceUntilIdle()
        assertNull(c.state.value)
        assertEquals(listOf(R.string.location_error_timeout), notices)
        assertTrue(source.cancelled, "the request is dropped")
    }

    @Test fun `switched-off location, missing permission and a refused permission each have their own message`() = runTest {
        val notices = ArrayList<Int>()
        var failure: LocationUnavailableException = LocationUnavailableException.ProvidersOff()
        val c = controller(FakeSource { throw failure }, notices)
        c.open(); c.fetch(false); advanceUntilIdle()
        failure = LocationUnavailableException.PermissionMissing()
        c.open(); c.fetch(true); advanceUntilIdle()
        c.open(); c.permissionDenied()
        assertEquals(
            listOf(R.string.location_error_off, R.string.location_error_permission, R.string.location_error_permission), notices,
        )
        assertNull(c.state.value)
    }

    @Test fun `a fix that is out of range is not offered for sending`() = runTest {
        val notices = ArrayList<Int>()
        val c = controller(FakeSource { LocationFix(200.0, 0.0, 5.0) }, notices)
        c.open(); c.fetch(true); advanceUntilIdle()
        assertNull(c.state.value)
        assertEquals(listOf(R.string.location_error_unusable), notices)
    }

    @Test fun `cancelling while waiting drops the request and closes the dialog`() = runTest {
        val source = FakeSource { awaitCancellation() }
        val notices = ArrayList<Int>()
        val c = controller(source, notices)
        c.open(); c.fetch(true)
        advanceTimeBy(1000)
        c.cancel()
        advanceUntilIdle()
        assertNull(c.state.value)
        assertTrue(source.cancelled)
        assertTrue(notices.isEmpty(), "cancelling is not an error")
    }

    @Test fun `a second request replaces a running one`() = runTest {
        var n = 0
        val source = FakeSource { if (n++ == 0) awaitCancellation() else LocationFix(1.0, 2.0, 3.0) }
        val c = controller(source, ArrayList())
        c.open(); c.fetch(true)
        advanceTimeBy(100)
        c.fetch(false)
        advanceUntilIdle()
        assertTrue(source.cancelled)
        assertIs<LocationShareUi.Preview>(c.state.value)
    }
}
