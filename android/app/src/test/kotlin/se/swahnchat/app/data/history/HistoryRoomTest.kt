package se.swahnchat.app.data.history

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.swahnchat.app.data.RoomTestDb
import se.swahnchat.app.data.SearchRepository
import se.swahnchat.app.data.db.AppDatabase
import se.swahnchat.app.data.db.DeviceEntity
import se.swahnchat.app.data.db.RoomLocalStore
import se.swahnchat.app.data.db.SignalCounterEntity
import se.swahnchat.app.data.db.SignalIdentityEntity
import se.swahnchat.app.data.db.SignalKyberPreKeyEntity
import se.swahnchat.app.data.db.SignalPreKeyEntity
import se.swahnchat.app.data.db.SignalRemoteIdentityEntity
import se.swahnchat.app.data.db.SignalSessionEntity
import se.swahnchat.app.data.db.SignalSignedPreKeyEntity
import se.swahnchat.app.data.db.TokenEntity
import se.swahnchat.core.client.Account
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export and import against the app's real Room stores (plain SQLite on the build machine): the identity, session, prekey
 * and token tables are filled with recognisable values and none of them may show up in an export; the search index follows an
 * import; a damaged file leaves the database untouched.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryRoomTest {
    private lateinit var db: AppDatabase
    private lateinit var other: AppDatabase
    private lateinit var dir: File
    private val identityKey = Random(11).nextBytes(64)
    private val sessionRecord = "SESSION-RECORD-BYTES-0123456789abcdef".toByteArray()
    private val preKey = "PREKEY-RECORD-BYTES-fedcba9876543210".toByteArray()
    private val signedPreKey = "SIGNED-PREKEY-RECORD-BYTES-aabbccddeeff".toByteArray()
    private val kyberKey = "KYBER-PREKEY-RECORD-BYTES-112233445566".toByteArray()
    private val remoteIdentity = Random(12).nextBytes(33)

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = RoomTestDb.file(context, "history-source.db")
        other = RoomTestDb.file(context, "history-target.db")
        dir = File(context.cacheDir, "history-room").apply { deleteRecursively(); mkdirs() }
    }

    @After fun close() {
        db.close(); other.close()
    }

    private fun fillSource(): Phone = runBlocking {
        val phone = Phone(File(dir, "source")).fill()
        // copy the fixture's content into Room, then add everything a phone holds that must never leave it
        val store = RoomLocalStore(db)
        store.saveAccount(phone.store.getAccount()!!)
        store.upsertUsers(phone.store.getUsers())
        for (c in phone.store.getConversations()) {
            store.upsertConversation(c)
            for (m in phone.store.getMessages(c.id, 100)) store.upsertMessage(m)
        }
        store.putReaction(se.swahnchat.core.client.Reaction("m1", "c1", "me", "👍", 7, 70))
        db.protocolDao().putIdentity(SignalIdentityEntity(1, identityKey, 4711))
        db.protocolDao().putSession(SignalSessionEntity("bob/${Secrets.DEVICE_ID}", sessionRecord))
        db.protocolDao().putPreKey(SignalPreKeyEntity(5, preKey))
        db.protocolDao().putSignedPreKey(SignalSignedPreKeyEntity(6, signedPreKey))
        db.protocolDao().putKyberPreKey(SignalKyberPreKeyEntity(7, kyberKey, lastResort = false))
        db.protocolDao().putCounter(SignalCounterEntity("prekey", 99))
        db.protocolDao().putRemoteIdentity(SignalRemoteIdentityEntity(Secrets.DEVICE_ID, "bob", remoteIdentity, verified = true, approved = true))
        db.tokenDao().put(TokenEntity(1, Secrets.ACCESS_TOKEN, Secrets.REFRESH_TOKEN, 1, 2))
        db.storeDao().putDevices(listOf(DeviceEntity(Secrets.DEVICE_ID, "bob", 1, "Bobs mobil", 1, 2, "REMOTE-IDENTITY-KEY-BASE64")))
        store.setMeta("keys_uploaded", "yes-SECRET-META")
        phone
    }

    @Test fun `an export from a phone full of keys, sessions and tokens contains none of them`() = runBlocking<Unit> {
        val phone = fillSource()
        val out = ByteArrayOutputStream()
        val result = HistoryExporter(RoomLocalStore(db)).export(out, PASS, "me", includeMedia = true, kdf = TEST_KDF)
        assertEquals(4, result.messages)
        val file = out.toByteArray()
        val entries = openArchive(file)
        val everything = entries.values.fold(ByteArray(0)) { a, b -> a + b }
        val forbidden = mapOf<String, ByteArray>(
            "identity key pair" to identityKey, "session record" to sessionRecord, "prekey" to preKey, "signed prekey" to signedPreKey,
            "kyber prekey" to kyberKey, "remote identity" to remoteIdentity,
            "access token" to Secrets.ACCESS_TOKEN.toByteArray(), "refresh token" to Secrets.REFRESH_TOKEN.toByteArray(),
            "attachment key" to Secrets.ATTACHMENT_KEY.toByteArray(), "attachment checksum" to Secrets.ATTACHMENT_SHA.toByteArray(),
            "server attachment id" to Secrets.SERVER_ATTACHMENT_ID.toByteArray(), "device id" to Secrets.DEVICE_ID.toByteArray(),
            "own device id" to "my-device".toByteArray(), "device identity key" to "REMOTE-IDENTITY-KEY-BASE64".toByteArray(),
            "meta" to "yes-SECRET-META".toByteArray(), "registration id" to "4711".toByteArray(),
        )
        for ((what, bytes) in forbidden) {
            assertFalse(contains(everything, bytes), "$what is in the export")
            // base64 and hex spellings of binary secrets count too
            assertFalse(contains(everything, java.util.Base64.getEncoder().encode(bytes)), "$what (base64) is in the export")
            assertFalse(contains(everything, bytes.joinToString("") { "%02x".format(it) }.toByteArray()), "$what (hex) is in the export")
            assertFalse(contains(file, bytes), "$what is in the file in the clear")
        }
        // and the archive only has what the format lists
        assertEquals(setOf("manifest.json", "conversations.jsonl", "messages.jsonl", "media/0"), entries.keys)
        phone.dir.deleteRecursively()
    }

    @Test fun `an import is searchable at once, marked, and does not touch the key or token tables`() = runBlocking<Unit> {
        val phone = fillSource()
        val file = Phone(File(dir, "source2")).fill().export(includeMedia = true)
        val target = RoomLocalStore(other)
        target.saveAccount(Account("https://x.example/", "me", "new-device", 2, "anna", "Anna", false, "Nytt"))
        for (c in phone.store.getConversations()) target.upsertConversation(c)
        other.protocolDao().putIdentity(SignalIdentityEntity(1, identityKey, 1))
        other.tokenDao().put(TokenEntity(1, "new-access", "new-refresh", 1, 2))
        val importer = HistoryImporter(target, File(dir, "target-media"), spoolDir = File(dir, "spool"))
        val result = importer.import(ByteArrayInputStream(file), file.size.toLong(), PASS, "me")
        assertEquals(4, result.imported)
        assertEquals(listOf("m1"), SearchRepository(other).search("Bob åäö").map { it.messageId })
        assertTrue(target.getMessage("m1")!!.imported)
        assertEquals(1L, other.openHelper.readableDatabase.query("SELECT COUNT(*) FROM signal_identity").use { it.moveToFirst(); it.getLong(0) })
        assertEquals(0L, other.openHelper.readableDatabase.query("SELECT COUNT(*) FROM signal_sessions").use { it.moveToFirst(); it.getLong(0) })
        assertEquals("new-access", other.tokenDao().get()!!.accessToken)
        assertEquals(0L, other.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox").use { it.moveToFirst(); it.getLong(0) })
        phone.dir.deleteRecursively()
    }

    @Test fun `a damaged file leaves the database exactly as it was`() = runBlocking<Unit> {
        val phone = fillSource()
        val good = Phone(File(dir, "source3")).fill().export(includeMedia = true)
        val bad = good.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }
        val target = RoomLocalStore(other)
        target.saveAccount(phone.store.getAccount()!!)
        for (c in phone.store.getConversations()) target.upsertConversation(c)
        val rowsBefore = other.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages").use { it.moveToFirst(); it.getLong(0) }
        assertFailsWith<HistoryException> {
            HistoryImporter(target, File(dir, "target-media2"), spoolDir = File(dir, "spool")).import(ByteArrayInputStream(bad), bad.size.toLong(), PASS, "me")
        }
        assertEquals(rowsBefore, other.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages").use { it.moveToFirst(); it.getLong(0) })
        assertEquals(0L, other.openHelper.readableDatabase.query("SELECT COUNT(*) FROM reactions").use { it.moveToFirst(); it.getLong(0) })
        assertNull(target.getMessage("m1"))
        assertTrue(File(dir, "target-media2").walkTopDown().none { it.isFile })
        phone.dir.deleteRecursively()
    }
}
