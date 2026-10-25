package se.swahnchat.app.platform.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import se.swahnchat.app.MainActivity
import se.swahnchat.app.R
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.NotificationPrivacy
import se.swahnchat.app.data.isMuted
import se.swahnchat.app.data.db.AppDatabase
import se.swahnchat.app.data.db.MessageEntity
import se.swahnchat.app.data.db.toModel
import se.swahnchat.app.di.ApplicationScope
import se.swahnchat.app.platform.ActiveConversation
import se.swahnchat.app.platform.AppForeground
import se.swahnchat.core.client.EngineEvent
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.client.StoredMessage
import se.swahnchat.core.model.ConversationType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local notifications. Text and sender come from the decrypted messages in the database, never from a push. A notification
 * is shown for a conversation with unread incoming messages unless that conversation is open in the foreground or is muted.
 * What it reveals follows [NotificationPrivacy] (the same for the lock-screen version).
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: SwahnChatEngine,
    private val store: LocalStore,
    private val db: AppDatabase,
    private val foreground: AppForeground,
    private val active: ActiveConversation,
    private val prefs: AppPrefs,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val pending = LinkedHashSet<String>()
    private val flushRequests = Channel<Unit>(Channel.CONFLATED)
    private val lastNotifiedSeq = HashMap<String, Long>()

    /** Conversations whose notification currently shows a sent inline reply (it must not be cancelled as "read"). */
    private val replied = java.util.Collections.synchronizedSet(HashSet<String>())

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
                        if (store.unreadCount(event.conversationId) == 0 && event.conversationId !in replied) cancel(event.conversationId)
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            // A setting that changes what a notification shows or offers (the reply action, names, text) must reach the ones that
            // are already posted, not only the next: repost them (quietly) or take them down.
            combine(prefs.notificationPrivacy, prefs.appLockEnabled) { privacy, locked -> privacy to locked }.drop(1).collect {
                try {
                    refreshPosted()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // never let a notification problem break the collector
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

    /** Posts again, without a new alert, every conversation notification that is up; those with nothing unread are removed. */
    internal suspend fun refreshPosted() {
        val up = context.getSystemService(NotificationManager::class.java).activeNotifications.mapTo(HashSet()) { it.id }
        notifyNow(store.getConversations().map { it.id }.filter { idFor(it) in up }, quiet = true)
    }

    /** Posts (or updates, or removes) the notification of each conversation according to its unread messages. */
    internal suspend fun notifyNow(ids: Collection<String>, quiet: Boolean = false) {
        for (id in ids) {
            if (foreground.isForeground.value && active.current.value == id) continue // the user is looking at it
            val muted = db.storeDao().settings(id)?.let { isMuted(it.mutedUntil, System.currentTimeMillis()) } == true
            // A muted conversation is silent, except for messages that mention this account. They use the same channel as every
            // message, so the system's do-not-disturb settings still decide whether they make a sound.
            val me = engine.account.value?.userId
            val unread = if (muted) {
                if (me == null) emptyList() else db.storeDao().unreadMentions(id, me, MAX_LINES).map { it.toModel() }
            } else {
                db.uiDao().unreadMessages(id, MAX_LINES).map { it.toModel() }
            }
            if (unread.isEmpty()) {
                cancel(id)
                continue
            }
            post(id, unread, quiet)
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // checked with areNotificationsEnabled()
    private suspend fun post(conversationId: String, unread: List<StoredMessage>, quiet: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        val conversation = store.getConversation(conversationId) ?: return
        val me = engine.account.value ?: return
        val group = conversation.type == ConversationType.GROUP
        val title = if (group) conversation.name.orEmpty().ifEmpty { context.getString(R.string.group_default_name) }
        else conversation.members.firstOrNull { it.userId != me.userId }?.let { store.getUser(it.userId)?.displayName }
            ?: context.getString(R.string.app_name)

        val privacy = prefs.notificationPrivacy.value
        val appName = context.getString(R.string.app_name)
        val shownTitle = if (privacy == NotificationPrivacy.HIDDEN) appName else title
        val countText = context.resources.getQuantityString(R.plurals.notification_new_messages, unread.size, unread.size)

        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.you)).setKey(me.userId).build())
        if (privacy == NotificationPrivacy.HIDDEN) {
            style.addMessage(NotificationCompat.MessagingStyle.Message(countText, unread.maxOf { it.serverTs ?: it.clientTs }, Person.Builder().setName(appName).build()))
        } else {
            if (group) style.setConversationTitle(title).setGroupConversation(true)
            val people = HashMap<String, Person>()
            for (m in unread) {
                val sender = people.getOrPut(m.senderUserId) {
                    Person.Builder().setKey(m.senderUserId)
                        .setName(store.getUser(m.senderUserId)?.displayName ?: context.getString(R.string.unknown_user))
                        .build()
                }
                val text = lineFor(m, me.userId, privacy)
                style.addMessage(NotificationCompat.MessagingStyle.Message(text, m.serverTs ?: m.clientTs, sender))
            }
        }

        val newest = unread.maxOf { it.seq ?: 0L }
        val alert = !quiet && newest > (lastNotifiedSeq[conversationId] ?: 0L)
        lastNotifiedSeq[conversationId] = maxOf(newest, lastNotifiedSeq[conversationId] ?: 0L)
        replied.remove(conversationId)

        // What the lock screen shows when the phone hides sensitive notification content: never the text; the name only
        // if the setting allows names.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(shownTitle)
            .setContentText(countText)
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(shownTitle)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(openIntent(conversationId))
        if (replyAllowed()) builder.addAction(replyAction(conversationId, shownTitle))
        builder.addAction(
            NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.action_mark_read), markReadIntent(conversationId))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build(),
        )
        val notification = builder.build()
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

    /**
     * One line of a notification. A message that mentions this account reads "Anna nämnde dig: ..." where the privacy setting
     * shows names and text, "Anna nämnde dig" where it shows names only, and is as generic as any other message otherwise.
     */
    private suspend fun lineFor(m: StoredMessage, myUserId: String, privacy: NotificationPrivacy): String {
        val mentionsMe = m.mentions.contains(myUserId)
        val name = store.getUser(m.senderUserId)?.displayName
        return when {
            mentionsMe && privacy == NotificationPrivacy.FULL ->
                name?.let { context.getString(R.string.notification_mentioned_text, it, previewText(m)) } ?: previewText(m)
            mentionsMe && name != null -> context.getString(R.string.notification_mentioned, name)
            mentionsMe -> context.getString(R.string.notification_mentioned_unknown)
            privacy == NotificationPrivacy.FULL -> previewText(m)
            else -> context.getString(R.string.notification_new_message)
        }
    }

    /**
     * Inline reply is offered unless the notification names nobody ([NotificationPrivacy.HIDDEN]: answering a message you
     * cannot attribute is how replies end up in the wrong conversation) or the app lock is on (a reply would send as the
     * user without the unlock).
     */
    internal fun replyAllowed(): Boolean = prefs.notificationPrivacy.value != NotificationPrivacy.HIDDEN && !prefs.appLockEnabled.value

    private fun replyAction(conversationId: String, title: String): NotificationCompat.Action {
        val input = RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel(context.getString(R.string.action_reply)).build()
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.action_reply), replyIntent(conversationId))
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .build()
    }

    /**
     * After an inline reply was handed to the outbox: replaces the notification (this also ends the reply field's progress
     * indicator) with a short confirmation that removes itself.
     */
    @android.annotation.SuppressLint("MissingPermission") // checked with areNotificationsEnabled()
    internal fun showReplied(conversationId: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        replied += conversationId
        val shown = if (prefs.notificationPrivacy.value == NotificationPrivacy.FULL) text.take(MAX_TEXT) else context.getString(R.string.reply_sent)
        val me = Person.Builder().setName(context.getString(R.string.you)).build()
        val style = NotificationCompat.MessagingStyle(me).addMessage(NotificationCompat.MessagingStyle.Message(shown, System.currentTimeMillis(), null as Person?))
        manager.notify(
            idFor(conversationId),
            NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setGroup(GROUP_KEY)
                .setTimeoutAfter(REPLIED_TIMEOUT_MS)
                .setContentIntent(openIntent(conversationId))
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
        replied.remove(conversationId)
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

    /**
     * The one mutable PendingIntent of the app: the system must be able to add the typed text (RemoteInput) to it. Hence
     * an explicit intent for our own non-exported receiver, so no other app can redirect or fill it.
     */
    private fun replyIntent(conversationId: String): PendingIntent {
        val intent = Intent(context, ReplyReceiver::class.java)
            .setAction(ReplyReceiver.ACTION)
            .putExtra(ReplyReceiver.EXTRA_CONVERSATION, conversationId)
        val mutable = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, idFor(conversationId), intent, mutable or PendingIntent.FLAG_UPDATE_CURRENT)
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
        private const val GROUP_KEY = "se.swahnchat.app.MESSAGES"
        private const val MAX_LINES = 8
        private const val MAX_TEXT = 400
        private const val DEBOUNCE_MS = 400L
        private const val REPLIED_TIMEOUT_MS = 8_000L

        fun deepLink(conversationId: String): Uri = "swahnchat://conversation/$conversationId".toUri()
    }
}
