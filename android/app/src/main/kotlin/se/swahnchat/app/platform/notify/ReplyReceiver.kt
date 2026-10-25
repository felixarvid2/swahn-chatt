package se.swahnchat.app.platform.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.swahnchat.app.data.SessionManager
import se.swahnchat.app.di.ApplicationScope
import se.swahnchat.app.platform.work.WorkScheduler
import se.swahnchat.core.client.SwahnChatEngine
import javax.inject.Inject

/**
 * The inline reply of a message notification. The text goes through the normal send path (`engine.sendText`: encrypted and
 * queued in the outbox, then `WorkScheduler.enqueueOutbox`), the conversation is marked read and the notification
 * is replaced by a short confirmation. Not exported: only our own (mutable, explicit) PendingIntent reaches it.
 */
@AndroidEntryPoint
class ReplyReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: SwahnChatEngine
    @Inject lateinit var session: SessionManager
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var work: WorkScheduler
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) = reply(intent)

    /** The work of [onReceive] once Hilt has injected the fields (called directly by the tests). */
    internal fun reply(intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra(EXTRA_CONVERSATION) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (!notifier.replyAllowed()) {
            // The notification can outlive the setting that took the reply away (app lock on, or "no information"): a reply that
            // arrives now is dropped, not sent as the user. The notification is posted again, without the reply action.
            val blocked = goAsync()
            scope.launch {
                try {
                    runCatching { notifier.notifyNow(listOf(id), quiet = true) }
                } finally {
                    blocked?.finish()
                }
            }
            return
        }
        val pending = goAsync()
        scope.launch {
            try {
                session.awaitRestored()
                engine.sendText(id, text)
                work.enqueueOutbox()
                runCatching { engine.markRead(id) }
                notifier.showReplied(id, text)
            } catch (e: Exception) {
                // not sent: show the notification again so that the user can reply again (the unread messages are still unread)
                runCatching { notifier.notifyNow(listOf(id)) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "se.swahnchat.app.REPLY"
        const val EXTRA_CONVERSATION = "conversation_id"
        const val KEY_TEXT = "reply_text"
    }
}
