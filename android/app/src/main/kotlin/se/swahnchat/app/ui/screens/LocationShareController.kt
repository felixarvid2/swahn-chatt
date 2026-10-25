package se.swahnchat.app.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import se.swahnchat.app.R
import se.swahnchat.app.location.LocationSource
import se.swahnchat.app.location.LocationUnavailableException
import se.swahnchat.app.location.buildLocation
import se.swahnchat.app.ui.components.LocationShareUi

/**
 * The steps of "Position": choose how exact, get ONE fix (30 s at most), show it with its accuracy, and only then let the person
 * send it. Nothing is sent from here; the preview's "Skicka" hands the confirmed position to the caller. Failures end the flow with
 * a clear message ([notice] gets a string resource): no permission, location switched off, or no fix in time.
 */
class LocationShareController(
    private val source: LocationSource,
    private val scope: CoroutineScope,
    private val notice: (Int) -> Unit,
) {
    private val _state = MutableStateFlow<LocationShareUi?>(null)
    val state: StateFlow<LocationShareUi?> = _state.asStateFlow()
    private var job: Job? = null

    /** The attach menu's "Position": ask how exact. */
    fun open() {
        _state.value = LocationShareUi.Choose
    }

    /** Closes the flow at any step; a running request is cancelled. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = null
    }

    /** The permission was refused (or only "approximate" was allowed for an exact position). */
    fun permissionDenied() {
        cancel()
        notice(R.string.location_error_permission)
    }

    /** The permission is in place: get the fix. */
    fun fetch(precise: Boolean) {
        job?.cancel()
        _state.value = LocationShareUi.Fetching(precise)
        job = scope.launch {
            try {
                val fix = withTimeoutOrNull(LocationSource.TIMEOUT_MS) { source.currentFix(precise) }
                    ?: throw LocationUnavailableException.Timeout()
                val spec = buildLocation(fix)
                if (spec == null) {
                    _state.value = null
                    notice(R.string.location_error_unusable)
                } else {
                    _state.value = LocationShareUi.Preview(spec)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LocationUnavailableException) {
                _state.value = null
                notice(
                    when (e) {
                        is LocationUnavailableException.PermissionMissing -> R.string.location_error_permission
                        is LocationUnavailableException.ProvidersOff -> R.string.location_error_off
                        is LocationUnavailableException.Timeout -> R.string.location_error_timeout
                    },
                )
            } catch (e: RuntimeException) {
                _state.value = null
                notice(R.string.location_error_unusable)
            }
        }
    }

    /** The preview was confirmed: ends the flow. */
    fun close() {
        _state.value = null
    }
}
