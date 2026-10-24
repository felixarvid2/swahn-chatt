package se.familychat.app.platform.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.familychat.app.MainActivity
import se.familychat.app.R
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.MessageEntity
import se.familychat.app.data.db.toModel
import se.familychat.app.di.ApplicationScope
import se.familychat.app.platform.ActiveConversation
import se.familychat.app.platform.AppForeground
import se.familychat.core.client.EngineEvent
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.LocalStore
import se.familychat.core.client.StoredMessage
import se.familychat.core.model.ConversationType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local notifications. Text and sender come from the decrypted messages in the database, never from a push. A notification
 * is shown for a conversation with unread incoming messages unless that conversation is open in the foreground.
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: FamilyChatEngine,
    private val store: LocalStore,
    private val db: AppDatabase,
    private val foreground: AppForeground,
    private val active: ActiveConversation,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val pending = LinkedHashSet<String>()
    private val flushRequests = Channel<Unit>(Channel.CONFLATED)
    private val lastNotifiedSeq = HashMap<String, Long>()

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_messages_description)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, context.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.channel_sync_description)
            },
        )
    }

    /** Call once from Application.onCreate. */
    fun start() {
        createChannels()
        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    is EngineEvent.MessageReceived -> if (!event.fromSelfOtherDevice) {
                        synchronized(pending) { pending += event.conversationId }
                        flushRequests.trySend(Unit)
                    }
                    is EngineEvent.ConversationRemoved -> cancel(event.conversationId)
                    is EngineEvent.ConversationChanged -> {
                        // read on another device or in the app: the unread count dropped to zero
                        if (store.unreadCount(event.conversationId) == 0) cancel(event.conversationId)
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            for (r in flushRequests) {
                delay(DEBOUNCE_MS) // a sync stores several messages in a burst: one notification update for them
                try {
                    flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // never let a notification problem break the collector
                }
            }
        }
    }

    private suspend fun flush() {
        notifyNow(synchronized(pending) { pending.toList().also { pending.clear() } })
    }

    /** Posts (or updates, or removes) the notification of each conversation according to its unread messages. */
    internal suspend fun notifyNow(ids: Collection<String>) {
        for (id in ids) {
            if (foreground.isForeground.value && active.current.value == id) continue // the user is looking at it
            val unread = db.uiDao().unreadMessages(id, MAX_LINES).map { it.toModel() }
            if (unread.isEmpty()) {
                cancel(id)
                continue
            }
            post(id, unread)
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // checked with areNotificationsEnabled()
    private suspend fun post(conversationId: String, unread: List<StoredMessage>) {
        if (!manager.areNotificationsEnabled()) return
        val conversation = store.getConversation(conversationId) ?: return
        val me = engine.account.value ?: return
        val group = conversation.type == ConversationType.GROUP
        val title = if (group) conversation.name.orEmpty().ifEmpty { context.getString(R.string.group_default_name) }
        else conversation.members.firstOrNull { it.userId != me.userId }?.let { store.getUser(it.userId)?.displayName }
            ?: context.getString(R.string.app_name)

        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.you)).setKey(me.userId).build())
        if (group) style.setConversationTitle(title).setGroupConversation(true)
        val people = HashMap<String, Person>()
        for (m in unread) {
            val sender = people.getOrPut(m.senderUserId) {
                Person.Builder().setKey(m.senderUserId)
                    .setName(store.getUser(m.senderUserId)?.displayName ?: context.getString(R.string.unknown_user))
                    .build()
            }
            style.addMessage(NotificationCompat.MessagingStyle.Message(previewText(m), m.serverTs ?: m.clientTs, sender))
        }

        val newest = unread.maxOf { it.seq ?: 0L }
        val alert = newest > (lastNotifiedSeq[conversationId] ?: 0L)
        lastNotifiedSeq[conversationId] = maxOf(newest, lastNotifiedSeq[conversationId] ?: 0L)

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(openIntent(conversationId))
            .addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.action_mark_read), markReadIntent(conversationId))
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build(),
            )
            .build()
        manager.notify(idFor(conversationId), notification)
        manager.notify(
            ID_SUMMARY,
            NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    /** What the notification shows for one message. */
    fun previewText(m: StoredMessage): String = when {
        m.decryptError != null -> context.getString(R.string.notification_undecryptable)
        !m.text.isNullOrBlank() -> m.text!!.trim().take(MAX_TEXT)
        m.attachments.isNotEmpty() -> when (m.attachments.first().kind) {
            "image" -> context.getString(R.string.attachment_photo)
            "video" -> context.getString(R.string.attachment_video)
            "voice" -> context.getString(R.string.attachment_voice)
            else -> context.getString(R.string.attachment_file_named, m.attachments.first().name ?: "")
        }
        else -> context.getString(R.string.notification_new_message)
    }

    fun cancel(conversationId: String) {
        manager.cancel(idFor(conversationId))
        lastNotifiedSeq.remove(conversationId)
    }

    fun cancelAll() {
        manager.cancelAll()
        lastNotifiedSeq.clear()
    }

    private fun idFor(conversationId: String) = ID_BASE + (conversationId.hashCode() and 0x0FFFFFFF)

    private fun openIntent(conversationId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, deepLink(conversationId), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, idFor(conversationId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun markReadIntent(conversationId: String): PendingIntent {
        val intent = Intent(context, MarkReadReceiver::class.java)
            .setAction(MarkReadReceiver.ACTION)
            .putExtra(MarkReadReceiver.EXTRA_CONVERSATION, conversationId)
        return PendingIntent.getBroadcast(context, idFor(conversationId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SYNC = "sync"
        const val ID_SYNC = 2
        private const val ID_SUMMARY = 1
        private const val ID_BASE = 1000
        private const val GROUP_KEY = "se.familychat.app.MESSAGES"
        private const val MAX_LINES = 8
        private const val MAX_TEXT = 400
        private const val DEBOUNCE_MS = 400L

        fun deepLink(conversationId: String): Uri = "familychat://conversation/$conversationId".toUri()
    }
}
