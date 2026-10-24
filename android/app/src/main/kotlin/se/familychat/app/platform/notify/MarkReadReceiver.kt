package se.familychat.app.platform.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.familychat.app.data.SessionManager
import se.familychat.app.di.ApplicationScope
import se.familychat.core.client.FamilyChatEngine
import javax.inject.Inject

/** The "mark as read" action of a message notification (the reply field is [ReplyReceiver]). */
@AndroidEntryPoint
class MarkReadReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: FamilyChatEngine
    @Inject lateinit var session: SessionManager
    @Inject lateinit var notifier: Notifier
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra(EXTRA_CONVERSATION) ?: return
        notifier.cancel(id)
        val pending = goAsync()
        scope.launch {
            try {
                session.awaitRestored()
                engine.markRead(id)
            } catch (e: Exception) {
                // the unread count stays; the next sync or opening the conversation marks it
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "se.familychat.app.MARK_READ"
        const val EXTRA_CONVERSATION = "conversation_id"
    }
}
