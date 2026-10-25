package se.swahnchat.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Tells when connectivity returns, so queued messages are sent without waiting for WorkManager's backoff. */
@Singleton
class ConnectivityMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /**
     * True if the active network is metered (mobile data, or a Wi-Fi marked as metered), or if that cannot be told: automatic
     * downloads that are limited to unmetered networks then wait. Read fresh each time (cheap), the answer changes with the network.
     */
    fun isMetered(): Boolean = try {
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
    } catch (e: SecurityException) {
        true // no ACCESS_NETWORK_STATE
    }

    /** [onAvailable] runs (on a binder thread) each time a usable network appears. */
    fun start(onAvailable: () -> Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        _online.value = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _online.value = true
                    onAvailable()
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            })
        } catch (e: SecurityException) {
            // no ACCESS_NETWORK_STATE: WorkManager's network constraint still delivers the outbox
        }
    }
}
