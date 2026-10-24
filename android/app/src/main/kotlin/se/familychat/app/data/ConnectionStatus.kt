package se.familychat.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import se.familychat.app.platform.ConnectivityMonitor
import se.familychat.app.ui.model.ConnectionUi
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.network.RealtimeState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the connection banner says. An open socket always wins (it proves we are online even when the phone's connectivity
 * callback lags behind or the network is a VPN); without one, no network means [ConnectionUi.OFFLINE] and a network that
 * does not lead to the server means [ConnectionUi.CONNECTING]. `STOPPED` (app in the background) and `LOGGED_OUT` show nothing.
 */
fun connectionUi(online: Boolean, realtime: RealtimeState): ConnectionUi = when {
    realtime == RealtimeState.CONNECTED || realtime == RealtimeState.STOPPED || realtime == RealtimeState.LOGGED_OUT -> ConnectionUi.HIDDEN
    !online -> ConnectionUi.OFFLINE
    else -> ConnectionUi.CONNECTING
}

/** Delay before a banner is shown, so that the usual short reconnects (and the first connect at start-up) cause no flicker. */
const val CONNECTING_SHOWN_AFTER_MS = 1_500L
const val OFFLINE_SHOWN_AFTER_MS = 600L

/** Shows a state only once it has lasted for its delay; going back to [ConnectionUi.HIDDEN] is immediate. */
fun Flow<ConnectionUi>.settled(): Flow<ConnectionUi> = distinctUntilChanged().transformLatest { state ->
    when (state) {
        ConnectionUi.HIDDEN -> Unit
        ConnectionUi.CONNECTING -> delay(CONNECTING_SHOWN_AFTER_MS)
        ConnectionUi.OFFLINE -> delay(OFFLINE_SHOWN_AFTER_MS)
    }
    emit(state)
}

@Singleton
class ConnectionStatus @Inject constructor(engine: FamilyChatEngine, connectivity: ConnectivityMonitor) {
    val state: Flow<ConnectionUi> = combine(connectivity.online, engine.realtimeState, ::connectionUi).settled()
}
