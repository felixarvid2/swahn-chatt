package se.swahnchat.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.swahnchat.app.di.ApplicationScope
import se.swahnchat.core.client.call.CallManager
import javax.inject.Inject

/**
 * The Decline action of the ringing notification. Not exported: only our own [android.app.PendingIntent] reaches it. Rejecting
 * suspends (it sends the signal), so the receiver stays alive with `goAsync()` on the application scope until it is done.
 */
@AndroidEntryPoint
class CallDeclineReceiver : BroadcastReceiver() {
    @Inject lateinit var calls: CallManager
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val callId = intent.getStringExtra(CallActivity.EXTRA_CALL_ID)
        val pending = goAsync()
        declineAsync(calls, scope, callId, pending::finish)
    }

    companion object {
        const val ACTION = "se.swahnchat.app.CALL_DECLINE"
    }
}

/** Rejects the ringing call [callId] (any ringing call when null) on [scope] and calls [finish] when done, whatever happens. */
internal fun declineAsync(calls: CallManager, scope: CoroutineScope, callId: String?, finish: () -> Unit) {
    scope.launch {
        try {
            val state = calls.state.value
            if (state is se.swahnchat.core.client.call.CallState.Incoming && (callId == null || state.callId == callId)) calls.reject()
        } finally {
            finish()
        }
    }
}
