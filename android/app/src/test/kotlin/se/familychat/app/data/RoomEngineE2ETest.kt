package se.familychat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.RoomLocalStore
import se.familychat.app.data.db.RoomProtocolStorage
import se.familychat.app.data.db.RoomTokenProvider
import se.familychat.core.client.DecryptError
import se.familychat.core.client.EngineConfig
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.InMemoryLocalStore
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.OutgoingAttachment
import se.familychat.core.crypto.InMemoryProtocolStorage
import se.familychat.core.network.InMemoryTokenProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real engine with real libsignal against a real backend, with Alice's phone running on the app's Room stores
 * (`RoomLocalStore`, `RoomProtocolStorage`, `RoomTokenProvider`; plain SQLite instead of SQLCipher on the build machine) and
 * Bob's on the in-memory ones. Skipped unless `FAMILYCHAT_TEST_URL` and an administrator invitation
 * (`FAMILYCHAT_TEST_APP_ADMIN_INVITE`, else `FAMILYCHAT_TEST_ADMIN_INVITE`) are set; `android/scripts/run-e2e.sh` sets them.
 *
 * This is the test that exercises `SyncProcessor`'s atomic "decrypt and store" step with the real native library calling
 * back into Room on the transaction's thread.
 */
@RunWith(RobolectricTestRunner::class)
class RoomEngineE2ETest {
    private val url = System.getenv("FAMILYCHAT_TEST_URL").orEmpty()
    private val adminInvite = (System.getenv("FAMILYCHAT_TEST_APP_ADMIN_INVITE") ?: System.getenv("FAMILYCHAT_TEST_ADMIN_INVITE")).orEmpty()
    private val run = UUID.randomUUID().toString().take(8)

    private class MemoryPhone(label: String, scope: CoroutineScope) {
        val store = InMemoryLocalStore()
        val dir: File = Files.createTempDirectory("fc-room-e2e-$label").toFile().also { it.deleteOnExit() }
        val engine = FamilyChatEngine(
            store, InMemoryProtocolStorage(), InMemoryTokenProvider(),
            EngineConfig("$label phone", File(dir, "att"), File(dir, "tmp"), allowInsecureLan = true, startWorkers = false), scope,
        )
        val userId get() = engine.account.value!!.userId
    }

    @Test fun `two users exchange messages while one phone runs on Room`() = runBlocking<Unit> {
        Assume.assumeTrue("FAMILYCHAT_TEST_URL / FAMILYCHAT_TEST_ADMIN_INVITE not set: skipping", url.isNotBlank() && adminInvite.isNotBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db: AppDatabase = RoomTestDb.file(context, "e2e.db")
        val aliceDir = File(context.cacheDir, "alice-e2e").apply { deleteRecursively(); mkdirs() }
        fun aliceEngine() = FamilyChatEngine(
            RoomLocalStore(db), RoomProtocolStorage(db), RoomTokenProvider(db),
            EngineConfig("alice room phone", File(aliceDir, "att"), File(aliceDir, "tmp"), allowInsecureLan = true, startWorkers = false), scope,
        )
        try {
            val admin = MemoryPhone("admin", scope)
            admin.engine.redeemInvite(url, adminInvite, "admin_$run", "admin-password-1", "Admin")
            var alice = aliceEngine()
            alice.redeemInvite(url, admin.engine.createInvitation(note = "alice").code, "alice_$run", "alice-password-1", "Alice")
            val bob = MemoryPhone("bob", scope)
            bob.engine.redeemInvite(url, admin.engine.createInvitation(note = "bob").code, "bob_$run", "bob-password-1", "Bob")
            alice.refreshDirectory(); bob.engine.refreshDirectory()
            val aliceId = alice.account.value!!.userId
            val aliceStore = RoomLocalStore(db)

            val conv = alice.createDirect(bob.userId).id
            bob.engine.refreshDirectory()

            // Alice -> Bob, then Bob -> Alice: Alice decrypts into Room
            val first = alice.sendText(conv, "Hej Bob, hemligt ${UUID.randomUUID()}")
            alice.processOutbox()
            bob.engine.sync()
            assertEquals(aliceStore.getMessage(first)!!.text, bob.store.getMessage(first)!!.text)
            val reply = bob.engine.sendText(conv, "Hej Alice", replyTo = first)
            bob.engine.processOutbox()
            alice.sync()
            val stored = aliceStore.getMessage(reply)!!
            assertEquals("Hej Alice", stored.text); assertEquals(first, stored.replyTo); assertNull(stored.decryptError)
            assertEquals(1, aliceStore.unreadCount(conv))
            alice.markRead(conv)
            assertEquals(0, aliceStore.unreadCount(conv))

            // a burst: 60 messages in one sync page, each decrypted and stored in its own transaction
            val burst = (1..60).map { bob.engine.sendText(conv, "burst $it") }
            bob.engine.processOutbox()
            alice.sync()
            val texts = aliceStore.getMessages(conv, 1000).filter { it.id in burst }.map { it.text }
            assertEquals((1..60).map { "burst $it" }, texts, "all 60 decrypted, in server order")
            assertTrue(aliceStore.getMessages(conv, 1000).none { it.decryptError != null })

            // the phone restarts: a new engine over the same database keeps the sessions, tokens and cursor
            alice = aliceEngine()
            assertNotNull(alice.restoreSession())
            val afterRestart = bob.engine.sendText(conv, "efter omstart")
            bob.engine.processOutbox()
            alice.sync()
            assertEquals("efter omstart", aliceStore.getMessage(afterRestart)!!.text)
            val fromRestarted = alice.sendText(conv, "Alice efter omstart")
            alice.processOutbox()
            bob.engine.sync()
            assertEquals("Alice efter omstart", bob.store.getMessage(fromRestarted)!!.text)

            // edit and delete travel as encrypted messages too
            alice.editMessage(first, "redigerat")
            alice.processOutbox()
            bob.engine.sync()
            assertEquals("redigerat", bob.store.getMessage(first)!!.text)
            assertTrue(bob.store.getMessage(first)!!.edited)
            val bobsLast = bob.engine.sendText(conv, "ta bort mig")
            bob.engine.processOutbox(); alice.sync()
            bob.engine.deleteMessage(bobsLast)
            bob.engine.processOutbox(); alice.sync()
            assertTrue(aliceStore.getMessage(bobsLast)!!.deleted)
            assertNull(aliceStore.getMessage(bobsLast)!!.text)

            // receipts: Bob read everything, Alice's first message shows as read
            bob.engine.markRead(conv)
            alice.sync()
            assertEquals(MessageStatus.READ, aliceStore.getMessage(first)!!.status)

            // attachments are encrypted with Tink and stored by Room as JSON
            val payload = Random(3).nextBytes(70_000)
            val file = File(aliceDir, "photo.bin").also { it.writeBytes(payload) }
            val withAttachment = alice.sendAttachments(conv, listOf(OutgoingAttachment(file, "application/octet-stream", "file", "photo.bin")), caption = "bilaga")
            alice.processOutbox()
            bob.engine.sync()
            assertContentEquals(payload, bob.engine.downloadAttachment(withAttachment, bob.store.getMessage(withAttachment)!!.attachments.single().id).readBytes())
            val back = File(bob.dir, "back.bin").also { it.writeBytes(payload.reversedArray()) }
            val fromBob = bob.engine.sendAttachments(conv, listOf(OutgoingAttachment(back, "application/octet-stream", "file", "back.bin")))
            bob.engine.processOutbox()
            alice.sync()
            val attachment = aliceStore.getMessage(fromBob)!!.attachments.single()
            assertContentEquals(payload.reversedArray(), alice.downloadAttachment(fromBob, attachment.id).readBytes())
            assertEquals(attachment.id, aliceStore.getMessage(fromBob)!!.attachments.single().id)
            assertEquals(se.familychat.core.client.DownloadState.DONE, aliceStore.getMessage(fromBob)!!.attachments.single().downloadState)

            // safety numbers computed from the Room-stored identities agree on both phones
            val bobDevice = bob.engine.account.value!!.deviceId
            val aliceDevice = alice.account.value!!.deviceId
            assertEquals(
                alice.safetyNumber(bob.userId, bobDevice)!!.digits,
                bob.engine.safetyNumber(aliceId, aliceDevice)!!.digits,
            )

            // logout wipes every table, including keys and tokens
            alice.logout()
            assertNull(aliceStore.getAccount())
            assertNull(RoomProtocolStorage(db).getLocalIdentity())
            assertNull(RoomTokenProvider(db).getTokens())
            assertTrue(aliceStore.getConversations().isEmpty())
            assertEquals(0, aliceStore.getSyncCursor())
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
