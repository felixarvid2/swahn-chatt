package se.familychat.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.familychat.app.data.RoomTestDb
import se.familychat.app.data.SearchRepository
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.RoomLocalStore
import se.familychat.app.data.db.RoomProtocolStorage
import se.familychat.app.data.db.RoomTokenProvider
import se.familychat.app.media.AttachmentPreparer
import se.familychat.app.platform.work.ExpiryClock
import se.familychat.app.platform.work.ExpiryManager
import se.familychat.app.platform.work.WorkScheduler
import se.familychat.core.client.Account
import se.familychat.core.client.DownloadState
import se.familychat.core.client.EngineConfig
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.LocalAttachment
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.StoredMessage
import se.familychat.core.model.Conversation
import se.familychat.core.model.ConversationMember
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Disappearing messages on the app's real stores: a message with a timer starts counting when it is first displayed, is removed
 * when its time has come (text, search index row, downloaded file, the copy of a file you sent), stays removed, and the
 * WorkManager job follows the next due time.
 */
@RunWith(RobolectricTestRunner::class)
class ExpiryManagerTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var engine: FamilyChatEngine
    private lateinit var store: RoomLocalStore
    private lateinit var expiry: ExpiryManager
    private lateinit var scope: CoroutineScope
    private lateinit var preparer: AttachmentPreparer
    private val now = AtomicLong(1_000_000L)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        db = RoomTestDb.file(context, "expiry.db")
        store = RoomLocalStore(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = File(context.filesDir, "expiry-test").apply { deleteRecursively(); mkdirs() }
        engine = FamilyChatEngine(
            store, RoomProtocolStorage(db), RoomTokenProvider(db),
            EngineConfig("test", File(dir, "att"), File(dir, "tmp"), allowInsecureLan = true, startWorkers = false, clock = { now.get() }), scope,
        )
        preparer = AttachmentPreparer(context)
        expiry = ExpiryManager(engine, WorkScheduler(context), preparer, scope, object : ExpiryClock() { override fun now() = now.get() })
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun infos() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkScheduler.EXPIRY).get()

    private fun msg(
        id: String, text: String?, outgoing: Boolean = false, expiresIn: Long? = 300, attachments: List<LocalAttachment> = emptyList(), seq: Long = 1,
    ) =
        StoredMessage(
            id = id, conversationId = "c1", senderUserId = if (outgoing) "me" else "bob", senderDeviceId = "d", outgoing = outgoing,
            status = if (outgoing) MessageStatus.SENT else MessageStatus.RECEIVED, seq = seq, clientTs = seq, serverTs = seq, text = text,
            replyTo = null, attachments = attachments, expiresIn = expiresIn,
        )

    @Test fun `a message is removed when its time has come - text, search row, files - and stays removed`() = runBlocking<Unit> {
        store.saveAccount(Account("https://x.example/", "me", "dev", 1, "anna", "Anna", false, "Pixel"))
        db.tokenDao().put(se.familychat.app.data.db.TokenEntity(1, "access", "refresh", 1, 2))
        assertNotNull(engine.restoreSession())
        store.upsertConversation(Conversation("c1", "direct", null, createdBy = "me", members = listOf(ConversationMember("me"), ConversationMember("bob"))))
        val attDir = File(context.filesDir, "expiry-test/att").apply { mkdirs() }
        val downloaded = File(attDir, "att-1").apply { writeText("decrypted bytes") }
        val sent = File(preparer.outgoingDir, "img-sent.jpg").apply { writeText("my copy") }
        fun att(id: String, f: File) =
            LocalAttachment(id, "k", "s", f.length(), "image/jpeg", "f.jpg", "image", 1, 1, null, null, f.absolutePath, DownloadState.DONE)
        store.upsertMessage(msg("m1", "hemligt ord", seq = 1))
        store.upsertMessage(msg("m2", "stannar kvar", expiresIn = null, seq = 2))
        store.upsertMessage(msg("m3", "bild", attachments = listOf(att("a1", downloaded)), seq = 3))
        store.upsertMessage(msg("m4", "min bild", outgoing = true, attachments = listOf(att("a2", sent)), seq = 4))
        store.upsertMessage(msg("m5", "visas aldrig", seq = 5)) // never displayed: its clock never starts
        val search = SearchRepository(db)
        assertEquals(listOf("m1"), search.search("hemligt").map { it.messageId })

        // the clocks start when the messages are shown
        val due = engine.markDisplayed(listOf("m1", "m3", "m4", "m2"))
        assertEquals(now.get() + 300_000, due)
        expiry.scheduleNext()
        val job = infos().single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(300_000L, job.initialDelayMillis)

        // not yet
        now.addAndGet(299_999)
        expiry.purgeAndSchedule()
        assertNotNull(store.getMessage("m1"))
        assertTrue(downloaded.exists() && sent.exists())

        // now
        now.addAndGet(1)
        expiry.purgeAndSchedule()
        for (gone in listOf("m1", "m3", "m4")) assertNull(store.getMessage(gone), gone)
        assertNotNull(store.getMessage("m2")); assertNotNull(store.getMessage("m5"))
        assertEquals(emptyList(), search.search("hemligt").map { it.messageId }, "the search index row is gone with the message")
        assertEquals(emptyList(), search.search("bild").map { it.messageId })
        assertFalse(downloaded.exists(), "the decrypted attachment file")
        assertFalse(sent.exists(), "the copy of the file you sent")
        assertTrue(store.isLocallyDeleted("m1"), "remembered, so a replayed event cannot bring it back")
        assertNull(store.nextExpiry())
        assertTrue(infos().none { it.state == WorkInfo.State.ENQUEUED }, "no job is left for a time that no message has")
    }
}
