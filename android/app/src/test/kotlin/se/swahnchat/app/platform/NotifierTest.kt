package se.swahnchat.app.platform

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.ConversationSettingsStore
import se.swahnchat.app.data.MuteDuration
import se.swahnchat.app.data.NotificationPrivacy
import se.swahnchat.app.data.RoomTestDb
import se.swahnchat.app.data.db.AppDatabase
import se.swahnchat.app.data.db.RoomLocalStore
import se.swahnchat.app.platform.notify.MarkReadReceiver
import se.swahnchat.app.platform.notify.Notifier
import se.swahnchat.app.platform.notify.ReplyReceiver
import se.swahnchat.core.client.Account
import se.swahnchat.core.client.DecryptError
import se.swahnchat.core.client.EngineConfig
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.LocalAttachment
import se.swahnchat.core.client.DownloadState
import se.swahnchat.core.client.MessageStatus
import se.swahnchat.core.client.StoredMessage
import se.swahnchat.core.crypto.InMemoryProtocolStorage
import se.swahnchat.core.model.Conversation
import se.swahnchat.core.model.ConversationMember
import se.swahnchat.core.model.User
import se.swahnchat.core.network.InMemoryTokenProvider
import se.swahnchat.core.network.Tokens
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Notifications are built from the decrypted messages in the database, with the sender's name; they are suppressed only
 * for the conversation that is open in the foreground.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "sv")
class NotifierTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: RoomLocalStore
    private lateinit var foreground: AppForeground
    private lateinit var active: ActiveConversation
    private lateinit var notifier: Notifier
    private lateinit var prefs: AppPrefs
    private lateinit var manager: NotificationManager
    private val scope = CoroutineScope(Job())

    @Before fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext()
        db = RoomTestDb.file(context, "notifier.db")
        store = RoomLocalStore(db)
        val tmp = File(context.cacheDir, "notifier").apply { mkdirs() }
        val engine = SwahnChatEngine(
            store, InMemoryProtocolStorage(), InMemoryTokenProvider(Tokens("a", "r")),
            EngineConfig(deviceName = "test", attachmentDir = tmp, tempDir = tmp, startWorkers = false), scope,
        )
        store.saveAccount(Account("https://chat.example/api/v1/", "me", "dev", 1, "anna", "Anna", false, "Pixel"))
        engine.restoreSession()
        store.upsertUsers(listOf(User("me", "anna", "Anna"), User("bob", "bob", "Bertil"), User("cia", "cia", "Cecilia")))
        store.upsertConversation(conversation("direct", "direct", null, listOf("me", "bob")))
        store.upsertConversation(conversation("group", "group", "Familjen", listOf("me", "bob", "cia")))
        foreground = AppForeground()
        active = ActiveConversation()
        context.getSharedPreferences("swahnchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPrefs(context)
        notifier = Notifier(context, engine, store, db, foreground, active, prefs, scope)
        notifier.createChannels()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    @After fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
        db.close()
    }

    private fun conversation(id: String, type: String, name: String?, members: List<String>) = Conversation(
        id = id, type = type, name = name, createdBy = "me", createdAt = 1, updatedAt = 1,
        members = members.map { ConversationMember(it, "member", 1) },
    )

    private fun incoming(id: String, conv: String, from: String, seq: Long, text: String?, attachments: List<LocalAttachment> = emptyList(), error: DecryptError? = null) =
        StoredMessage(
            id = id, conversationId = conv, senderUserId = from, senderDeviceId = "d-$from", outgoing = false, status = MessageStatus.RECEIVED,
            seq = seq, clientTs = seq * 1000, serverTs = seq * 1000, text = text, replyTo = null, attachments = attachments, decryptError = error,
        )

    private fun titleOf(n: Notification): String? = shadowOf(n).contentTitle?.toString()

    private fun conversationNotifications() = shadowOf(manager).allNotifications.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    @Test fun `an unread message becomes a messaging style notification with sender and decrypted text`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hej Anna, ses vi kl 16?"))
        store.upsertMessage(incoming("m2", "direct", "bob", 2, "Jag tar med kaffe"))
        notifier.notifyNow(listOf("direct"))
        val n = conversationNotifications().single()
        assertEquals("Bertil", titleOf(n))
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!
        assertEquals(listOf("Hej Anna, ses vi kl 16?", "Jag tar med kaffe"), style.messages.map { it.text.toString() })
        assertEquals(listOf("Bertil", "Bertil"), style.messages.map { it.person!!.name.toString() })
        assertEquals(false, style.isGroupConversation)
        assertEquals(NotificationCompat.CATEGORY_MESSAGE, n.category)
        assertEquals(Notifier.CHANNEL_MESSAGES, n.channelId)
    }

    @Test fun `group notifications carry the group title and each sender`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "group", "bob", 1, "Vem grillar?"))
        store.upsertMessage(incoming("m2", "group", "cia", 2, "Jag!"))
        notifier.notifyNow(listOf("group"))
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(conversationNotifications().single())!!
        assertEquals("Familjen", style.conversationTitle.toString())
        assertTrue(style.isGroupConversation)
        assertEquals(listOf("Bertil", "Cecilia"), style.messages.map { it.person!!.name.toString() })
    }

    @Test fun `the notification offers an inline reply and mark as read and opens the conversation by deep link`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hej"))
        notifier.notifyNow(listOf("direct"))
        val n = conversationNotifications().single()
        assertEquals(2, n.actions.size)
        val reply = NotificationCompat.getAction(n, 0)!!
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
        assertEquals(listOf(ReplyReceiver.KEY_TEXT), reply.remoteInputs!!.map { it.resultKey })
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, NotificationCompat.getAction(n, 1)!!.semanticAction)
        assertTrue(NotificationCompat.getAction(n, 1)!!.remoteInputs.isNullOrEmpty())
        assertEquals("swahnchat://conversation/direct", shadowOf(n.contentIntent).savedIntent.data.toString())
    }

    @Test fun `only the reply intent is mutable and it is an explicit intent to a receiver that is not exported`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hej"))
        notifier.notifyNow(listOf("direct"))
        val n = conversationNotifications().single()
        fun flagsOf(pi: android.app.PendingIntent?) = shadowOf(pi!!).flags
        val mutable = android.app.PendingIntent.FLAG_MUTABLE
        val immutable = android.app.PendingIntent.FLAG_IMMUTABLE
        val replyPi = NotificationCompat.getAction(n, 0)!!.actionIntent
        assertTrue(flagsOf(replyPi) and mutable != 0, "RemoteInput needs a mutable PendingIntent")
        assertEquals(0, flagsOf(replyPi) and immutable)
        val replyIntent = shadowOf(replyPi!!).savedIntent
        assertEquals(ReplyReceiver::class.java.name, replyIntent.component?.className, "explicit component")
        assertTrue(flagsOf(NotificationCompat.getAction(n, 1)!!.actionIntent) and immutable != 0)
        assertTrue(flagsOf(n.contentIntent) and immutable != 0)
        // the receivers are private to the app
        val pm = context.packageManager
        for (cls in listOf(ReplyReceiver::class.java, MarkReadReceiver::class.java)) {
            val info = pm.getReceiverInfo(android.content.ComponentName(context, cls), 0)
            assertEquals(false, info.exported, cls.simpleName)
        }
    }

    @Test fun `privacy name only shows who wrote but not what`() = runBlocking<Unit> {
        prefs.setNotificationPrivacy(NotificationPrivacy.NAME_ONLY)
        store.upsertMessage(incoming("m1", "group", "bob", 1, "Hemlig text"))
        notifier.notifyNow(listOf("group"))
        val n = conversationNotifications().single()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!
        assertEquals("Familjen", style.conversationTitle.toString())
        assertEquals(listOf("Bertil"), style.messages.map { it.person!!.name.toString() })
        assertEquals(listOf("Nytt meddelande"), style.messages.map { it.text.toString() })
        val pub = NotificationCompat.getPublicVersion(n)!!
        assertEquals("Familjen", shadowOf(pub).contentTitle.toString())
        assertEquals("Nytt meddelande", shadowOf(pub).contentText.toString())
        assertEquals(2, n.actions.size, "a name is shown, so replying is fine")
    }

    @Test fun `privacy none shows neither name nor text and offers no reply`() = runBlocking<Unit> {
        prefs.setNotificationPrivacy(NotificationPrivacy.HIDDEN)
        store.upsertMessage(incoming("m1", "group", "bob", 1, "Hemlig text"))
        store.upsertMessage(incoming("m2", "group", "cia", 2, "Mer text"))
        notifier.notifyNow(listOf("group"))
        val n = conversationNotifications().single()
        assertEquals("Swahn Chat", titleOf(n))
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!
        assertEquals(listOf("2 nya meddelanden"), style.messages.map { it.text.toString() })
        assertEquals(null, style.conversationTitle)
        val pub = NotificationCompat.getPublicVersion(n)!!
        assertEquals("Swahn Chat", shadowOf(pub).contentTitle.toString())
        val everything = listOf(titleOf(n), shadowOf(n).contentText?.toString(), shadowOf(pub).contentText?.toString()) +
            style.messages.flatMap { listOf(it.text.toString(), it.person?.name?.toString()) } + n.extras.keySet().mapNotNull { n.extras.get(it)?.toString() }
        for (secret in listOf("Hemlig", "Mer text", "Bertil", "Cecilia", "Familjen")) {
            assertTrue(everything.none { it?.contains(secret) == true }, "$secret must not appear")
        }
        assertEquals(1, n.actions.size, "no reply when nobody is named")
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, NotificationCompat.getAction(n, 0)!!.semanticAction)
    }

    @Test fun `the full version has a public version without the text`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hemlig text"))
        notifier.notifyNow(listOf("direct"))
        val n = conversationNotifications().single()
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val pub = NotificationCompat.getPublicVersion(n)!!
        assertEquals("Bertil", shadowOf(pub).contentTitle.toString())
        assertTrue(shadowOf(pub).contentText.toString().none { it == 'H' }, "no message text on the lock screen")
    }

    @Test fun `no reply while the app lock is on`() = runBlocking<Unit> {
        prefs.setAppLockEnabled(true)
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hej"))
        notifier.notifyNow(listOf("direct"))
        assertEquals(1, conversationNotifications().single().actions.size)
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            Thread.sleep(25)
        }
    }

    private fun replyIntent(text: String) = Intent(ReplyReceiver.ACTION).putExtra(ReplyReceiver.EXTRA_CONVERSATION, "direct").also {
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(ReplyReceiver.KEY_TEXT).build()), it, Bundle().apply { putCharSequence(ReplyReceiver.KEY_TEXT, text) },
        )
    }

    private fun outgoingCount() = runBlocking { store.getMessages("direct", 100).count { it.outgoing } }

    @Test fun `an old reply action does nothing once the app lock is on, and the notification comes back without the action`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hej"))
        notifier.notifyNow(listOf("direct"))
        assertEquals(2, conversationNotifications().single().actions.size)
        prefs.setAppLockEnabled(true) // after the notification was posted
        val receiver = ReplyReceiver().also { it.notifier = notifier; it.scope = scope }
        receiver.reply(replyIntent("Svar utan upplåsning"))
        waitFor("the notification is posted again") { conversationNotifications().singleOrNull()?.actions?.size == 1 }
        assertEquals(0, outgoingCount(), "nothing was sent")
        assertEquals(1, store.unreadCount("direct"), "and nothing was marked read")
    }

    @Test fun `an old reply action does nothing once the notifications name nobody`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hej"))
        notifier.notifyNow(listOf("direct"))
        prefs.setNotificationPrivacy(NotificationPrivacy.HIDDEN)
        ReplyReceiver().also { it.notifier = notifier; it.scope = scope }.reply(replyIntent("Svar"))
        waitFor("the notification is posted again") {
            conversationNotifications().singleOrNull()?.let { titleOf(it) == context.getString(se.swahnchat.app.R.string.app_name) } == true
        }
        assertEquals(1, conversationNotifications().single().actions.size)
        assertEquals(0, outgoingCount())
    }

    @Test fun `turning the app lock on or the details off changes the notifications that are already up`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hemlig text"))
        notifier.start()
        Thread.sleep(300) // the collectors are subscribed before the settings change
        notifier.notifyNow(listOf("direct"))
        assertEquals(2, conversationNotifications().single().actions.size)
        prefs.setAppLockEnabled(true)
        waitFor("the reply action is gone") { conversationNotifications().singleOrNull()?.actions?.size == 1 }
        prefs.setAppLockEnabled(false)
        waitFor("the reply action is back") { conversationNotifications().singleOrNull()?.actions?.size == 2 }
        prefs.setNotificationPrivacy(NotificationPrivacy.HIDDEN)
        waitFor("the text is gone") {
            conversationNotifications().singleOrNull()?.let { n ->
                val texts = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!.messages.map { it.text.toString() }
                texts.none { it.contains("Hemlig") } && n.actions.size == 1
            } == true
        }
        // a conversation that was read in the meantime has no notification to bring back
        store.setMyReadSeq("direct", 1)
        prefs.setNotificationPrivacy(NotificationPrivacy.FULL)
        waitFor("the read conversation's notification is removed") { conversationNotifications().isEmpty() }
    }

    @Test fun `a muted conversation is not notified but keeps its unread count, an expired mute does not count`() = runBlocking<Unit> {
        val settings = ConversationSettingsStore(db)
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hej"))
        settings.mute("direct", MuteDuration.HOUR_1, System.currentTimeMillis())
        notifier.notifyNow(listOf("direct"))
        assertTrue(conversationNotifications().isEmpty())
        assertEquals(1, store.unreadCount("direct"))
        settings.mute("direct", MuteDuration.HOUR_1, System.currentTimeMillis() - 2 * 3_600_000L) // ended an hour ago
        notifier.notifyNow(listOf("direct"))
        assertEquals(1, conversationNotifications().size)
        settings.mute("direct", MuteDuration.ALWAYS, System.currentTimeMillis())
        notifier.notifyNow(listOf("direct"))
        assertTrue(conversationNotifications().isEmpty(), "muting removes a notification that is already shown")
    }

    @Test fun `a mention notifies even in a muted conversation, saying who mentioned you, and the other messages stay silent`() = runBlocking<Unit> {
        ConversationSettingsStore(db).mute("group", MuteDuration.ALWAYS, System.currentTimeMillis())
        store.upsertMessage(incoming("m1", "group", "cia", 1, "Vem tar kaffe?"))
        notifier.notifyNow(listOf("group"))
        assertTrue(conversationNotifications().isEmpty(), "muted, nothing mentions me")
        store.upsertMessage(incoming("m2", "group", "bob", 2, "@Anna kan du ta kaffe?").copy(mentions = listOf("me")))
        notifier.notifyNow(listOf("group"))
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(conversationNotifications().single())!!
        assertEquals(listOf("Bertil nämnde dig: @Anna kan du ta kaffe?"), style.messages.map { it.text.toString() }, "only the mention, not the muted chatter")
        assertEquals(2, store.unreadCount("group"))
    }

    @Test fun `a mention in a conversation that is not muted reads who mentioned you, within what the privacy setting shows`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "group", "cia", 1, "Hej alla"))
        store.upsertMessage(incoming("m2", "group", "bob", 2, "Hemligt @Anna").copy(mentions = listOf("me")))
        notifier.notifyNow(listOf("group"))
        fun texts() = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(conversationNotifications().single())!!
            .messages.map { it.text.toString() }
        assertEquals(listOf("Hej alla", "Bertil nämnde dig: Hemligt @Anna"), texts())
        prefs.setNotificationPrivacy(NotificationPrivacy.NAME_ONLY)
        notifier.notifyNow(listOf("group"))
        assertEquals(listOf("Nytt meddelande", "Bertil nämnde dig"), texts(), "the name, not the text")
        prefs.setNotificationPrivacy(NotificationPrivacy.HIDDEN)
        notifier.notifyNow(listOf("group"))
        val n = conversationNotifications().single()
        val everything = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!.messages.map { it.text.toString() } + titleOf(n).orEmpty()
        assertTrue(everything.none { it.contains("Bertil") || it.contains("Hemligt") || it.contains("nämnde") }, "nothing about the mention leaks: $everything")
    }

    @Test fun `after an inline reply the notification becomes a short confirmation`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hej"))
        notifier.notifyNow(listOf("direct"))
        store.setMyReadSeq("direct", 1)
        notifier.showReplied("direct", "Ses kl 16")
        val n = conversationNotifications().single()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!
        assertEquals(listOf("Ses kl 16"), style.messages.map { it.text.toString() })
        assertTrue(n.actions.isNullOrEmpty())
    }

    @Test fun `attachments and undecryptable messages get a generic line and never leak payload`() = runBlocking<Unit> {
        val photo = LocalAttachment("a1", "k", "s", 10, "image/jpeg", "x.jpg", "image", 1, 1, null, null, null, DownloadState.NONE)
        store.upsertMessage(incoming("m1", "direct", "bob", 1, null, listOf(photo)))
        store.upsertMessage(incoming("m2", "direct", "bob", 2, null, error = DecryptError.CORRUPT))
        notifier.notifyNow(listOf("direct"))
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(conversationNotifications().single())!!
        assertEquals(listOf("Foto"), style.messages.take(1).map { it.text.toString() })
        assertTrue(style.messages[1].text.toString().contains("avkryptera"))
    }

    @Test fun `the open conversation in the foreground is not notified but others and the background are`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hej"))
        store.upsertMessage(incoming("m2", "group", "cia", 1, "Hallå"))
        foreground.set(true)
        active.enter("direct")
        notifier.notifyNow(listOf("direct", "group"))
        assertEquals(1, conversationNotifications().size, "only the conversation that is not open")
        assertTrue(titleOf(conversationNotifications().single())!!.startsWith("Familjen"))
        manager.cancelAll()
        foreground.set(false) // the app went to the background while the conversation was 'open'
        notifier.notifyNow(listOf("direct"))
        assertEquals(listOf("Bertil"), conversationNotifications().map { titleOf(it) })
    }

    @Test fun `once everything is read the notification goes away`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hej"))
        notifier.notifyNow(listOf("direct"))
        assertEquals(1, conversationNotifications().size)
        store.setMyReadSeq("direct", 1)
        notifier.notifyNow(listOf("direct"))
        assertTrue(conversationNotifications().isEmpty())
    }

    @Test fun `own messages and deleted ones are never notified`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "me", 1, "mitt").copy(outgoing = true, status = MessageStatus.SENT))
        store.upsertMessage(incoming("m2", "direct", "bob", 2, null).copy(deleted = true))
        notifier.notifyNow(listOf("direct"))
        assertTrue(conversationNotifications().isEmpty())
        assertNotNull(manager)
    }
}
