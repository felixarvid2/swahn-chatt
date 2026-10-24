package se.familychat.app.platform

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
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
import se.familychat.app.data.RoomTestDb
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.RoomLocalStore
import se.familychat.app.platform.notify.Notifier
import se.familychat.core.client.Account
import se.familychat.core.client.DecryptError
import se.familychat.core.client.EngineConfig
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.LocalAttachment
import se.familychat.core.client.DownloadState
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.StoredMessage
import se.familychat.core.crypto.InMemoryProtocolStorage
import se.familychat.core.model.Conversation
import se.familychat.core.model.ConversationMember
import se.familychat.core.model.User
import se.familychat.core.network.InMemoryTokenProvider
import se.familychat.core.network.Tokens
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
    private lateinit var manager: NotificationManager
    private val scope = CoroutineScope(Job())

    @Before fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext()
        db = RoomTestDb.file(context, "notifier.db")
        store = RoomLocalStore(db)
        val tmp = File(context.cacheDir, "notifier").apply { mkdirs() }
        val engine = FamilyChatEngine(
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
        notifier = Notifier(context, engine, store, db, foreground, active, scope)
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

    @Test fun `the notification offers mark as read without a reply action and opens the conversation by deep link`() = runBlocking<Unit> {
        store.upsertMessage(incoming("m1", "direct", "bob", 1, "Hej"))
        notifier.notifyNow(listOf("direct"))
        val n = conversationNotifications().single()
        assertEquals(1, n.actions.size)
        assertTrue(n.actions[0].remoteInputs.isNullOrEmpty(), "no inline reply")
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, NotificationCompat.getAction(n, 0)!!.semanticAction)
        assertEquals("familychat://conversation/direct", shadowOf(n.contentIntent).savedIntent.data.toString())
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
