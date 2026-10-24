package se.familychat.app.platform.push

import dagger.hilt.android.AndroidEntryPoint
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import se.familychat.app.platform.work.WorkScheduler
import javax.inject.Inject

/**
 * Receives the distributor's callbacks. A push never carries message content (the server only sends "something new"), so
 * [onMessage] ignores the payload and just starts a sync; the notification is built locally from the decrypted message.
 */
@AndroidEntryPoint
class FamilyChatPushService : PushService() {
    @Inject lateinit var push: PushManager
    @Inject lateinit var work: WorkScheduler

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) = push.onNewEndpoint(endpoint.url)

    override fun onMessage(message: PushMessage, instance: String) = work.enqueueSync()

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = push.onRegistrationFailed(reason)

    override fun onUnregistered(instance: String) = push.onUnregistered()
}
