package se.swahnchat.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Why no position could be obtained; the screen turns each into its own message. */
sealed class LocationUnavailableException(message: String) : Exception(message) {
    /** The permission was not granted (or the user allowed "approximate" only and asked for an exact position). */
    class PermissionMissing : LocationUnavailableException("Location permission missing")

    /** Every location provider that could answer is switched off in the system settings. */
    class ProvidersOff : LocationUnavailableException("Location is switched off")

    /** No fix within [LocationSource.TIMEOUT_MS]. */
    class Timeout : LocationUnavailableException("No position within the time limit")
}

/** One current position, once. The platform LocationManager only: no Google Play services, nothing is fetched from the internet. */
interface LocationSource {
    /**
     * Asks for ONE fix: [precise] uses GPS (and the network as a fallback), otherwise only the network/approximate provider.
     * Cancelling the caller cancels the request.
     *
     * @throws LocationUnavailableException when there is no permission, nothing is switched on, or [TIMEOUT_MS] ran out
     */
    suspend fun currentFix(precise: Boolean): LocationFix

    companion object {
        const val TIMEOUT_MS = 30_000L
    }
}

/**
 * The real source. API 30 and newer: `LocationManager.getCurrentLocation`; below: `requestSingleUpdate` (deprecated since 30, the
 * only single-shot call on API 26-29). The request is dropped when the coroutine is cancelled or the time runs out.
 */
@Singleton
class PlatformLocationSource @Inject constructor(@ApplicationContext private val context: Context) : LocationSource {
    private val manager get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The providers to try for the permission the app holds, best first. */
    internal fun providersFor(precise: Boolean, fine: Boolean, coarse: Boolean): List<String> = buildList {
        if (precise && fine) add(LocationManager.GPS_PROVIDER)
        if (coarse || fine) add(LocationManager.NETWORK_PROVIDER)
    }

    override suspend fun currentFix(precise: Boolean): LocationFix {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) throw LocationUnavailableException.PermissionMissing()
        if (precise && !fine) throw LocationUnavailableException.PermissionMissing()
        val enabled = providersFor(precise, fine, coarse).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (enabled.isEmpty()) throw LocationUnavailableException.ProvidersOff()
        // the first provider to deliver wins; the order is only the order of asking
        val fix = withTimeoutOrNull(LocationSource.TIMEOUT_MS) { request(enabled.first()) }
            ?: if (enabled.size > 1) withTimeoutOrNull(LocationSource.TIMEOUT_MS / 3) { request(enabled[1]) } else null
        return fix ?: throw LocationUnavailableException.Timeout()
    }

    @SuppressLint("MissingPermission") // checked in currentFix
    @Suppress("DEPRECATION")
    private suspend fun request(provider: String): LocationFix? = suspendCancellableCoroutine { cont ->
        // Native callbacks never throw: anything that goes wrong becomes "no fix".
        fun deliver(location: Location?) {
            if (!cont.isActive) return
            val fix = try {
                location?.takeIf { it.hasAccuracy() }?.let { LocationFix(it.latitude, it.longitude, it.accuracy.toDouble()) }
            } catch (e: Exception) {
                null
            }
            cont.resume(fix)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, Executor { it.run() }) { deliver(it) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = deliver(location)

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = deliver(null)
                }
                cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        } catch (e: RuntimeException) {
            if (cont.isActive) cont.resume(null)
        }
    }
}
