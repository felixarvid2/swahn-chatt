package se.swahnchat.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.swahnchat.app.appContext
import se.swahnchat.app.data.db.AppDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The migration chain 1 -> 2 -> 3 -> 4 -> 5 on a device, on real SQLCipher files: phones in the field are on version 1 or 2 and their
 * data must survive, including the full text index. The schemas come from the exported `app/schemas/ JSON files` (test APK assets).
 */
@Suppress("MaxLineLength") // SQL fixtures
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {
    private val passphrase = ByteArray(32) { (it * 7 + 3).toByte() }

    /** SQLCipher clears the passphrase array it is given, so every helper gets its own copy. */
    private val cipherFactory = object : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration) =
            SupportOpenHelperFactory(passphrase.copyOf()).create(configuration)
    }

    @get:Rule
    val helper: MigrationTestHelper by lazy {
        System.loadLibrary("sqlcipher")
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), cipherFactory)
    }

    @Before fun cleanUp() {
        listOf("mig-v1-v3", "mig-v2-v3", "mig-v3-v4", "mig-v4-v5", "mig-app-v1", "mig-app-v3", "mig-app-v4").forEach { appContext.deleteDatabase(it) }
    }

    private val messageColumns = "id, conversationId, senderUserId, senderDeviceId, outgoing, status, seq, clientTs, serverTs, text, replyTo, " +
        "attachments, edited, editedAt, deleted, decryptError, failureReason, deliveredTo, readBy, systemType, systemUserId, " +
        "systemDeviceId, systemDeviceName, pendingEnvelopeType, pendingEnvelopeBody"

    private fun insertMessage(db: SupportSQLiteDatabase, id: String, seq: Int, text: String?, deleted: Int = 0) {
        val t = if (text == null) "NULL" else "'$text'"
        db.execSQL("INSERT INTO messages ($messageColumns) VALUES ('$id', 'c1', 'bob', 'd', 0, 'RECEIVED', $seq, $seq, $seq, $t, NULL, '[]', 0, NULL, $deleted, NULL, NULL, '', '', NULL, NULL, NULL, NULL, NULL, NULL)")
    }

    /** What a phone with the first release holds. */
    private fun fillVersion1(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO account (id, serverUrl, userId, deviceId, deviceNumber, username, displayName, isAdmin, deviceName) VALUES (1, 'https://x', 'me', 'dev', 1, 'anna', 'Anna', 0, 'Pixel')")
        db.execSQL("INSERT INTO conversations (id, type, name, avatarAttachmentId, createdBy, createdAt, updatedAt, lastMessageAt, lastMessageSeq, myReadSeq) VALUES ('c1', 'direct', NULL, NULL, 'me', 1, 2, 3, 2, 1)")
        insertMessage(db, "m1", 1, "hej 1")
        insertMessage(db, "m2", 2, "hej 2")
        insertMessage(db, "m3", 3, "Åsa bakar bullar")
        insertMessage(db, "m4", 4, null, deleted = 1)
        db.execSQL("INSERT INTO signal_identities (deviceId, userId, identityKey, verified, approved) VALUES ('d1', 'bob', x'0102', 1, 1)")
        db.execSQL("INSERT INTO sync_state (id, cursor) VALUES (1, 77)")
    }

    private fun SupportSQLiteDatabase.count(table: String) = query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    private fun SupportSQLiteDatabase.matches(word: String): List<String> =
        query("SELECT m.id FROM messages m JOIN message_fts ON message_fts.docid = m.rowid WHERE message_fts MATCH '\"$word*\"' ORDER BY m.id").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    @Test fun version1ToCurrentKeepsDataAndBuildsTheSearchIndex() {
        helper.createDatabase("mig-v1-v3", 1).use { fillVersion1(it) }
        helper.runMigrationsAndValidate("mig-v1-v3", AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS).use { db ->
            assertEquals(1L, db.count("account"))
            assertEquals(1L, db.count("conversations"))
            assertEquals(4L, db.count("messages"))
            assertEquals(1L, db.count("signal_identities"))
            assertEquals(0L, db.count("drafts"))
            assertEquals(0L, db.count("conversation_settings"))
            assertEquals(0L, db.count("message_receipts"))
            assertEquals(0L, db.count("local_deletions"))
            assertEquals(0L, db.count("reactions"))
            assertEquals(0L, db.count("conversation_timers"))
            db.query("SELECT forwarded, imported, expiresIn, expireAt FROM messages WHERE id = 'm1'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)); assertEquals(0, c.getInt(1)); assertTrue(c.isNull(2) && c.isNull(3))
            }
            db.query("SELECT cursor FROM sync_state WHERE id = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals(77L, c.getLong(0)) }
            // FTS4 with the unicode61 tokenizer works in SQLCipher: backfill, diacritics kept apart, prefix search
            assertEquals(listOf("m1", "m2"), db.matches("hej"))
            assertEquals(listOf("m3"), db.matches("åsa"))
            assertEquals(emptyList(), db.matches("asa"))
            // and the sync triggers keep it current
            insertMessage(db, "m5", 5, "ny post")
            assertEquals(listOf("m5"), db.matches("post"))
            db.execSQL("UPDATE messages SET text = 'ändrad' WHERE id = 'm5'")
            assertEquals(emptyList(), db.matches("post"))
            assertEquals(listOf("m5"), db.matches("ändr"))
            db.execSQL("DELETE FROM messages WHERE id = 'm5'")
            assertEquals(emptyList(), db.matches("ändr"))
        }
    }

    @Test fun version2ToCurrentKeepsTheDraftAndIndexesExistingMessages() {
        helper.createDatabase("mig-v2-v3", 2).use { db ->
            fillVersion1(db)
            db.execSQL("INSERT INTO drafts (conversationId, text, updatedAt) VALUES ('c1', 'Utkast', 5)")
        }
        helper.runMigrationsAndValidate("mig-v2-v3", AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS).use { db ->
            assertEquals(1L, db.count("drafts"))
            assertEquals(4L, db.count("messages"))
            assertEquals(listOf("m1", "m2"), db.matches("hej"))
        }
    }

    /** Version 3 as an updated phone holds it: version 1 data, a draft, a pin and an own message with receipts in the old columns. */
    private fun fillVersion3(db: SupportSQLiteDatabase) {
        fillVersion1(db)
        db.execSQL("INSERT INTO drafts (conversationId, text, updatedAt) VALUES ('c1', 'Utkast', 5)")
        db.execSQL("INSERT INTO conversation_settings (conversationId, pinnedAt, mutedUntil, archived) VALUES ('c1', 9, 0, 0)")
        db.execSQL("INSERT INTO messages ($messageColumns) VALUES ('o1', 'c1', 'me', 'dev', 1, 'READ', 5, 5, 5, 'skickat', NULL, '[]', 0, NULL, 0, NULL, NULL, 'bob', 'bob', NULL, NULL, NULL, NULL, NULL, NULL)")
    }

    @Test fun version3To4KeepsDataAndAddsReceiptAndTombstoneTables() {
        helper.createDatabase("mig-v3-v4", 3).use { fillVersion3(it) }
        helper.runMigrationsAndValidate("mig-v3-v4", 4, true, AppDatabase.MIGRATION_3_4).use { db ->
            assertEquals(5L, db.count("messages"))
            assertEquals(1L, db.count("drafts"))
            assertEquals(1L, db.count("conversation_settings"))
            assertEquals(1L, db.count("signal_identities"))
            assertEquals(0L, db.count("message_receipts"))
            assertEquals(0L, db.count("local_deletions"))
            db.query("SELECT status, deliveredTo, readBy FROM messages WHERE id = 'o1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("READ", c.getString(0)); assertEquals("bob", c.getString(1)); assertEquals("bob", c.getString(2))
            }
            assertEquals(listOf("m1", "m2"), db.matches("hej"))
            db.execSQL("INSERT INTO message_receipts (messageId, userId, conversationId, deliveredAt, readAt) VALUES ('o1', 'bob', 'c1', 5, 8)")
            db.execSQL("INSERT INTO local_deletions (messageId, conversationId, deletedAt) VALUES ('m1', 'c1', 6)")
            assertEquals(1L, db.count("message_receipts"))
            assertEquals(1L, db.count("local_deletions"))
        }
    }

    /** Version 4 as an updated phone holds it: version 3 data, receipt times, a tombstone, an unsent message. */
    private fun fillVersion4(db: SupportSQLiteDatabase) {
        fillVersion3(db)
        db.execSQL("INSERT INTO message_receipts (messageId, userId, conversationId, deliveredAt, readAt) VALUES ('o1', 'bob', 'c1', 5, 8)")
        db.execSQL("INSERT INTO local_deletions (messageId, conversationId, deletedAt) VALUES ('gone', 'c1', 6)")
        db.execSQL("INSERT INTO outbox (messageId, conversationId, kind, targetMessageId, text, replyTo, attachments, clientTs, envelopes, attempts, lastError, createdAt) VALUES ('p1', 'c1', 'message', NULL, 'väntar', NULL, '[]', 11, NULL, 2, 'network', 12)")
    }

    @Test fun version4To5KeepsDataAndAddsReactionsTimersAndTheNewColumns() {
        helper.createDatabase("mig-v4-v5", 4).use { fillVersion4(it) }
        helper.runMigrationsAndValidate("mig-v4-v5", 5, true, *AppDatabase.MIGRATIONS).use { db ->
            assertEquals(5L, db.count("messages"))
            assertEquals(1L, db.count("message_receipts"))
            assertEquals(1L, db.count("local_deletions"))
            assertEquals(1L, db.count("outbox"))
            assertEquals(0L, db.count("reactions"))
            assertEquals(0L, db.count("conversation_timers"))
            db.query("SELECT forwarded, imported, expiresIn, expireAt, status, readBy FROM messages WHERE id = 'o1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0)); assertEquals(0, c.getInt(1)); assertTrue(c.isNull(2) && c.isNull(3))
                assertEquals("READ", c.getString(4)); assertEquals("bob", c.getString(5))
            }
            db.query("SELECT forwarded, expiresIn, \"remove\", text FROM outbox WHERE messageId = 'p1'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)); assertTrue(c.isNull(1)); assertEquals(0, c.getInt(2)); assertEquals("väntar", c.getString(3))
            }
            assertEquals(listOf("m1", "m2"), db.matches("hej"))
            db.execSQL("UPDATE messages SET expiresIn = 3600, expireAt = 99, text = 'ändrad hej' WHERE id = 'm1'")
            assertEquals(listOf("m1"), db.matches("ändr"))
            db.execSQL("INSERT INTO reactions (messageId, userId, conversationId, emoji, seq, at) VALUES ('m1', 'bob', 'c1', 'x', 3, 4)")
            db.execSQL("INSERT INTO conversation_timers (conversationId, seconds, setBy, seq, at) VALUES ('c1', 3600, 'bob', 3, 4)")
            assertEquals(1L, db.count("reactions"))
            assertEquals(1L, db.count("conversation_timers"))
        }
    }

    @Test fun theAppsOwnBuilderOpensAVersion4FileOnSqlcipherAndReactionsTimersAndExpiryWork() {
        helper.createDatabase("mig-app-v4", 4).use { fillVersion4(it) }
        val db = AppDatabase.build(appContext, cipherFactory, "mig-app-v4")
        try {
            runBlocking {
                val store = se.swahnchat.app.data.db.RoomLocalStore(db)
                assertEquals(null, store.getMessage("m1")!!.expiresIn)
                assertEquals(null, store.nextExpiry())
                store.putReaction(se.swahnchat.core.client.Reaction("m1", "c1", "bob", "x", 4, 5))
                assertEquals(1, store.getReactions("m1").size)
                store.putTimer(se.swahnchat.core.client.ConversationTimer("c1", 3600, "bob", 4, 5))
                assertEquals(3600L, store.getTimer("c1")!!.seconds)
                store.updateMessage("m2") { it.copy(expiresIn = 300, expireAt = 1000) }
                assertEquals(listOf("m2"), store.expiredMessages(1000).map { it.id })
                store.deleteMessageLocally("m2", "c1", 1001)
                assertEquals(listOf("m1"), SearchRepository(db).search("hej").map { it.messageId })
            }
        } finally {
            db.close()
            appContext.deleteDatabase("mig-app-v4")
        }
    }

    @Test fun theAppsOwnBuilderOpensAVersion3FileOnSqlcipherAndTheNewTablesWork() {
        helper.createDatabase("mig-app-v3", 3).use { fillVersion3(it) }
        val db = AppDatabase.build(appContext, cipherFactory, "mig-app-v3")
        try {
            runBlocking {
                val dao = db.storeDao()
                assertEquals("skickat", dao.message("o1")?.text)
                dao.putReceipt(se.swahnchat.app.data.db.MessageReceiptEntity("o1", "bob", "c1", 5, null))
                assertEquals(5L, dao.receipts("o1").single().deliveredAt)
                dao.putLocalDeletion(se.swahnchat.app.data.db.LocalDeletionEntity("m1", "c1", 6))
                dao.deleteMessage("m1")
                assertEquals(1, dao.localDeletionCount("m1"))
                assertEquals(null, dao.message("m1"))
                assertEquals(listOf("m2"), SearchRepository(db).search("hej").map { it.messageId })
            }
        } finally {
            db.close()
            appContext.deleteDatabase("mig-app-v3")
        }
    }

    @Test fun theAppsOwnBuilderOpensAVersion1FileOnSqlcipherAndSearchWorks() {
        helper.createDatabase("mig-app-v1", 1).use { fillVersion1(it) }
        // Room's builder as the app uses it (all migrations registered, no destructive fallback) on the same SQLCipher file
        val db = AppDatabase.build(appContext, cipherFactory, "mig-app-v1")
        try {
            runBlocking {
                assertEquals("hej 1", db.storeDao().message("m1")?.text)
                assertEquals(listOf("m2", "m1"), SearchRepository(db).search("hej").map { it.messageId })
                db.storeDao().putDraft(se.swahnchat.app.data.db.DraftEntity("c1", "Utkast", 9))
                assertEquals("Utkast", db.storeDao().draft("c1"))
            }
        } finally {
            db.close()
            appContext.deleteDatabase("mig-app-v1")
        }
    }
}
