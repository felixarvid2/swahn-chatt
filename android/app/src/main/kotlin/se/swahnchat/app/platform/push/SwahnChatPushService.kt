package se.swahnchat.app.platform.push

import dagger.hilt.android.AndroidEntryPoint
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import se.swahnchat.app.platform.work.WorkScheduler
import javax.inject.Inject

/**
 * Receives the distributor's callbacks. A push never carries message content (the server only sends "something new"), so
 * [onMessage] ignores the payload and just starts a sync; the notification is built locally from the decrypted message.
 */
@AndroidEntryPoint
class SwahnChatPushService : PushService() {
    @Inject lateinit var push: PushManager
    @Inject lateinit var work: WorkScheduler
    @Inject lateinit var log: se.swahnchat.app.diagnostics.EventLog

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) = push.onNewEndpoint(endpoint.url)

    override fun onMessage(message: PushMessage, instance: String) {
        log.record(se.swahnchat.app.diagnostics.PushReceived()) // the payload is ignored and never recorded
        work.enqueueSync()
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = push.onRegistrationFailed(reason)

    override fun onUnregistered(instance: String) = push.onUnregistered()
}
