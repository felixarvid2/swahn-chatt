package se.familychat.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.familychat.app.data.db.AppDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Room migration test setup. The exported schemas in `app/schemas/` are test assets (see build.gradle.kts), so every
 * version ever shipped can be created here and migrated. When you raise `AppDatabase.VERSION`:
 *  1. add the `Migration` to `AppDatabase.MIGRATIONS`,
 *  2. commit the newly exported `schemas/.../<version>.json`,
 *  3. add a test below that creates the previous version, inserts rows, and runs `runMigrationsAndValidate`.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun `version 1 is created from the exported schema and validates`() {
        helper.createDatabase("migration-v1", 1).use { db ->
            db.execSQL("INSERT INTO meta (`key`, value) VALUES ('k', 'v')")
            db.execSQL("INSERT INTO sync_state (id, cursor) VALUES (1, 42)")
        }
        helper.runMigrationsAndValidate("migration-v1", AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS).use { db ->
            db.query("SELECT cursor FROM sync_state WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(42L, c.getLong(0))
            }
        }
    }

    /** What the phone that has the first release installed holds: an account, a conversation, messages, pins, an outbox row. */
    private fun fillVersion1(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO account (id, serverUrl, userId, deviceId, deviceNumber, username, displayName, isAdmin, deviceName) VALUES (1, 'https://x', 'me', 'dev', 1, 'anna', 'Anna', 0, 'Pixel')")
        db.execSQL("INSERT INTO conversations (id, type, name, avatarAttachmentId, createdBy, createdAt, updatedAt, lastMessageAt, lastMessageSeq, myReadSeq) VALUES ('c1', 'direct', NULL, NULL, 'me', 1, 2, 3, 2, 1)")
        for (i in 1..2) {
            db.execSQL(
                "INSERT INTO messages (id, conversationId, senderUserId, senderDeviceId, outgoing, status, seq, clientTs, serverTs, text, replyTo, attachments, edited, editedAt, deleted, decryptError, failureReason, deliveredTo, readBy, systemType, systemUserId, systemDeviceId, systemDeviceName, pendingEnvelopeType, pendingEnvelopeBody) " +
                    "VALUES ('m$i', 'c1', 'bob', 'd', 0, 'RECEIVED', $i, $i, $i, 'hej $i', NULL, '[]', 0, NULL, 0, NULL, NULL, '', '', NULL, NULL, NULL, NULL, NULL, NULL)",
            )
        }
        db.execSQL("INSERT INTO signal_identities (deviceId, userId, identityKey, verified, approved) VALUES ('d1', 'bob', x'0102', 1, 1)")
        db.execSQL("INSERT INTO sync_state (id, cursor) VALUES (1, 77)")
    }

    @Test fun `version 1 to 2 keeps every row and adds an empty drafts table that works`() {
        helper.createDatabase("migration-v1-v2", 1).use { fillVersion1(it) }
        // 2 is validated against the exported schema (2.json): same tables, columns and keys as Room expects
        helper.runMigrationsAndValidate("migration-v1-v2", 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }
            assertEquals(1L, count("account"))
            assertEquals(1L, count("conversations"))
            assertEquals(2L, count("messages"))
            assertEquals(1L, count("signal_identities"))
            assertEquals(0L, count("drafts"), "no draft exists before the user types one")
            db.query("SELECT text FROM messages WHERE id = 'm2'").use { c -> assertTrue(c.moveToFirst()); assertEquals("hej 2", c.getString(0)) }
            db.query("SELECT cursor FROM sync_state WHERE id = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals(77L, c.getLong(0)) }
            db.execSQL("INSERT INTO drafts (conversationId, text, updatedAt) VALUES ('c1', 'Hej', 5)")
            assertEquals(1L, count("drafts"))
        }
    }

    @Test fun `the app opens a version 1 file through the real builder and migrates it`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        helper.createDatabase("familychat-upgrade.db", 1).use { fillVersion1(it) }
        // Room's own builder, as the app uses it (AppDatabase.build registers MIGRATIONS): no destructive fallback, so a missing migration would throw
        val db = AppDatabase.build(context, factory = null, name = "familychat-upgrade.db") { it.allowMainThreadQueries() }
        try {
            kotlinx.coroutines.runBlocking {
                assertEquals("hej 1", db.storeDao().message("m1")?.text)
                assertEquals(null, db.storeDao().draft("c1"))
                db.storeDao().putDraft(se.familychat.app.data.db.DraftEntity("c1", "Utkast", 9))
                assertEquals("Utkast", db.storeDao().draft("c1"))
            }
        } finally {
            db.close()
            context.deleteDatabase("familychat-upgrade.db")
        }
    }

    private fun matches(db: androidx.sqlite.db.SupportSQLiteDatabase, word: String): List<String> =
        db.query("SELECT m.id FROM messages m JOIN message_fts ON message_fts.docid = m.rowid WHERE message_fts MATCH '\"$word*\"' ORDER BY m.id").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    @Test fun `version 2 to 3 keeps every row, indexes the existing messages and starts with no conversation settings`() {
        helper.createDatabase("migration-v2-v3", 2).use { db ->
            fillVersion1(db)
            db.execSQL("INSERT INTO drafts (conversationId, text, updatedAt) VALUES ('c1', 'Utkast', 5)")
            // a message with Swedish letters, a deleted one (no text) and one without text, as an old phone has them
            db.execSQL(
                "INSERT INTO messages (id, conversationId, senderUserId, senderDeviceId, outgoing, status, seq, clientTs, serverTs, text, replyTo, attachments, edited, editedAt, deleted, decryptError, failureReason, deliveredTo, readBy, systemType, systemUserId, systemDeviceId, systemDeviceName, pendingEnvelopeType, pendingEnvelopeBody) " +
                    "VALUES ('m3', 'c1', 'bob', 'd', 0, 'RECEIVED', 3, 3, 3, 'Åsa bakar bullar', NULL, '[]', 0, NULL, 0, NULL, NULL, '', '', NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO messages (id, conversationId, senderUserId, senderDeviceId, outgoing, status, seq, clientTs, serverTs, text, replyTo, attachments, edited, editedAt, deleted, decryptError, failureReason, deliveredTo, readBy, systemType, systemUserId, systemDeviceId, systemDeviceName, pendingEnvelopeType, pendingEnvelopeBody) " +
                    "VALUES ('m4', 'c1', 'bob', 'd', 0, 'RECEIVED', 4, 4, 4, NULL, NULL, '[]', 0, NULL, 1, NULL, NULL, '', '', NULL, NULL, NULL, NULL, NULL, NULL)",
            )
        }
        // validated against 3.json: the search table (tokenizer, content table) and the new settings table are what Room expects
        helper.runMigrationsAndValidate("migration-v2-v3", 3, true, AppDatabase.MIGRATION_2_3).use { db ->
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }
            assertEquals(1L, count("account"))
            assertEquals(1L, count("conversations"))
            assertEquals(4L, count("messages"))
            assertEquals(1L, count("signal_identities"))
            assertEquals(1L, count("drafts"))
            assertEquals(0L, count("conversation_settings"))
            db.query("SELECT cursor FROM sync_state WHERE id = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals(77L, c.getLong(0)) }
            // the backfill: what was written before the migration can be found
            assertEquals(listOf("m1", "m2"), matches(db, "hej"))
            assertEquals(listOf("m3"), matches(db, "åsa"))
            assertEquals(emptyList(), matches(db, "asa"))
            // the triggers keep it current from now on: insert, edit, delete
            db.execSQL(
                "INSERT INTO messages (id, conversationId, senderUserId, senderDeviceId, outgoing, status, seq, clientTs, serverTs, text, replyTo, attachments, edited, editedAt, deleted, decryptError, failureReason, deliveredTo, readBy, systemType, systemUserId, systemDeviceId, systemDeviceName, pendingEnvelopeType, pendingEnvelopeBody) " +
                    "VALUES ('m5', 'c1', 'bob', 'd', 0, 'RECEIVED', 5, 5, 5, 'ny post', NULL, '[]', 0, NULL, 0, NULL, NULL, '', '', NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            assertEquals(listOf("m5"), matches(db, "post"))
            db.execSQL("UPDATE messages SET text = 'ändrad' WHERE id = 'm5'")
            assertEquals(emptyList(), matches(db, "post"))
            assertEquals(listOf("m5"), matches(db, "ändr"))
            db.execSQL("DELETE FROM messages WHERE id = 'm5'")
            assertEquals(emptyList(), matches(db, "ändr"))
        }
    }

    @Test fun `all the way from version 1 to the current one`() {
        helper.createDatabase("migration-v1-vN", 1).use { fillVersion1(it) }
        helper.runMigrationsAndValidate("migration-v1-vN", AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS).use { db ->
            assertEquals(listOf("m1", "m2"), matches(db, "hej"))
            db.query("SELECT COUNT(*) FROM messages").use { it.moveToFirst(); assertEquals(2L, it.getLong(0)) }
            // the tables of versions 2 to 5 are all there, empty, and usable
            for (t in listOf("drafts", "conversation_settings", "message_receipts", "local_deletions", "reactions", "conversation_timers")) {
                db.query("SELECT COUNT(*) FROM $t").use { it.moveToFirst(); assertEquals(0L, it.getLong(0), t) }
            }
            db.execSQL("INSERT INTO message_receipts (messageId, userId, conversationId, deliveredAt, readAt) VALUES ('m1', 'bob', 'c1', 5, NULL)")
            db.execSQL("INSERT INTO local_deletions (messageId, conversationId, deletedAt) VALUES ('m2', 'c1', 6)")
            db.query("SELECT COUNT(*) FROM local_deletions").use { it.moveToFirst(); assertEquals(1L, it.getLong(0)) }
            // version 5: the old messages have no timer and are neither forwarded nor imported
            db.query("SELECT forwarded, imported, expiresIn, expireAt, systemValue FROM messages WHERE id = 'm1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0)); assertEquals(0, c.getInt(1))
                assertTrue(c.isNull(2) && c.isNull(3) && c.isNull(4))
            }
            db.execSQL("INSERT INTO reactions (messageId, userId, conversationId, emoji, seq, at) VALUES ('m1', 'bob', 'c1', 'x', 3, 4)")
            db.execSQL("INSERT INTO conversation_timers (conversationId, seconds, setBy, seq, at) VALUES ('c1', 3600, 'bob', 3, 4)")
        }
    }

    /** Version 4 as the phone of an updated user holds it: version 3 data, receipt times, a tombstone, an unsent message and a notice. */
    @Suppress("MaxLineLength") // SQL fixtures
    private fun fillVersion4(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        fillVersion3(db)
        db.execSQL("INSERT INTO message_receipts (messageId, userId, conversationId, deliveredAt, readAt) VALUES ('o1', 'bob', 'c1', 5, 8)")
        db.execSQL("INSERT INTO local_deletions (messageId, conversationId, deletedAt) VALUES ('gone', 'c1', 6)")
        db.execSQL("INSERT INTO outbox (messageId, conversationId, kind, targetMessageId, text, replyTo, attachments, clientTs, envelopes, attempts, lastError, createdAt) VALUES ('p1', 'c1', 'message', NULL, 'väntar', NULL, '[]', 11, NULL, 2, 'network', 12)")
        db.execSQL(
            "INSERT INTO messages (id, conversationId, senderUserId, senderDeviceId, outgoing, status, seq, clientTs, serverTs, text, replyTo, attachments, edited, editedAt, deleted, decryptError, failureReason, deliveredTo, readBy, systemType, systemUserId, systemDeviceId, systemDeviceName, pendingEnvelopeType, pendingEnvelopeBody) " +
                "VALUES ('sys1', 'c1', '', NULL, 0, 'RECEIVED', 3, 7, NULL, NULL, NULL, '[]', 0, NULL, 0, NULL, NULL, '', '', 'member_added', 'bob', NULL, NULL, NULL, NULL)",
        )
    }

    @Test fun `version 4 to 5 keeps every row and old messages have no timer`() {
        helper.createDatabase("migration-v4-v5", 4).use { fillVersion4(it) }
        // validated against 5.json; the whole chain is passed, as the app registers it
        helper.runMigrationsAndValidate("migration-v4-v5", 5, true, *AppDatabase.MIGRATIONS).use { db ->
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }
            assertEquals(1L, count("account"))
            assertEquals(1L, count("conversations"))
            assertEquals(4L, count("messages"))
            assertEquals(1L, count("drafts"))
            assertEquals(1L, count("conversation_settings"))
            assertEquals(1L, count("signal_identities"))
            assertEquals(1L, count("message_receipts"))
            assertEquals(1L, count("local_deletions"))
            assertEquals(1L, count("outbox"))
            assertEquals(0L, count("reactions"))
            assertEquals(0L, count("conversation_timers"))
            // nothing was rewritten: the defaults mean "as before"
            db.query("SELECT forwarded, imported, expiresIn, expireAt, systemValue, text, status, readBy FROM messages WHERE id = 'o1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0)); assertEquals(0, c.getInt(1))
                assertTrue(c.isNull(2) && c.isNull(3) && c.isNull(4))
                assertEquals("skickat", c.getString(5)); assertEquals("READ", c.getString(6)); assertEquals("bob", c.getString(7))
            }
            db.query("SELECT forwarded, expiresIn, \"remove\", text, attempts FROM outbox WHERE messageId = 'p1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0)); assertTrue(c.isNull(1)); assertEquals(0, c.getInt(2))
                assertEquals("väntar", c.getString(3)); assertEquals(2, c.getInt(4))
            }
            db.query("SELECT cursor FROM sync_state WHERE id = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals(77L, c.getLong(0)) }
            assertEquals(listOf("m1", "m2"), matches(db, "hej")) // the search index survived the ALTERs
            // the new columns and tables are usable, and the index still follows edits
            db.execSQL("UPDATE messages SET expiresIn = 3600, expireAt = 99, forwarded = 1, text = 'ändrad hej' WHERE id = 'm1'")
            assertEquals(listOf("m1", "m2"), matches(db, "hej"))
            assertEquals(listOf("m1"), matches(db, "ändr"))
            db.execSQL("INSERT INTO reactions (messageId, userId, conversationId, emoji, seq, at) VALUES ('m1', 'bob', 'c1', 'x', 3, 4)")
            db.execSQL("INSERT INTO conversation_timers (conversationId, seconds, setBy, seq, at) VALUES ('c1', 3600, 'bob', 3, 4)")
            assertEquals(1L, count("reactions"))
            assertEquals(1L, count("conversation_timers"))
        }
    }

    @Test fun `the app opens a version 4 file through the real builder and the new features work on the migrated rows`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        helper.createDatabase("familychat-upgrade4.db", 4).use { fillVersion4(it) }
        val db = AppDatabase.build(context, factory = null, name = "familychat-upgrade4.db") { it.allowMainThreadQueries() }
        try {
            kotlinx.coroutines.runBlocking {
                val store = se.familychat.app.data.db.RoomLocalStore(db)
                val old = store.getMessage("m1")!!
                assertEquals(null, old.expiresIn); assertEquals(null, old.expireAt); assertEquals(false, old.forwarded); assertEquals(false, old.imported)
                assertEquals("väntar", store.getOutboxEntry("p1")!!.text)
                assertEquals(false, store.getOutboxEntry("p1")!!.forwarded)
                assertTrue(store.getMessage("sys1")!!.isSystem)
                // reactions, timer and expiry on top of the migrated data
                store.putReaction(se.familychat.core.client.Reaction("m1", "c1", "bob", "x", 4, 5))
                assertEquals(listOf("bob"), store.getReactions("m1").map { it.userId })
                store.putTimer(se.familychat.core.client.ConversationTimer("c1", 3600, "bob", 4, 5))
                assertEquals(3600L, store.getTimer("c1")!!.seconds)
                assertEquals(null, store.nextExpiry(), "nothing that existed before the update expires")
                store.updateMessage("m2") { it.copy(expiresIn = 300, expireAt = 1000) }
                assertEquals(1000L, store.nextExpiry())
                assertEquals(listOf("m2"), store.expiredMessages(1000).map { it.id })
                assertEquals(emptyList(), store.expiredMessages(999).map { it.id })
                store.deleteMessageLocally("m2", "c1", 1001)
                assertEquals(emptyList(), SearchRepository(db).search("hej 2").map { it.messageId }.filter { it == "m2" })
                store.removeConversation("c1")
                assertEquals(emptyList(), store.getReactions("m1"))
                assertEquals(null, store.getTimer("c1"))
            }
        } finally {
            db.close()
            context.deleteDatabase("familychat-upgrade4.db")
        }
    }

    /** Version 3 as an updated phone holds it: version 1 data plus a draft, a pin, and an own message with receipts in the old columns. */
    @Suppress("MaxLineLength") // SQL fixtures
    private fun fillVersion3(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        fillVersion1(db)
        db.execSQL("INSERT INTO drafts (conversationId, text, updatedAt) VALUES ('c1', 'Utkast', 5)")
        db.execSQL("INSERT INTO conversation_settings (conversationId, pinnedAt, mutedUntil, archived) VALUES ('c1', 9, 0, 0)")
        db.execSQL(
            "INSERT INTO messages (id, conversationId, senderUserId, senderDeviceId, outgoing, status, seq, clientTs, serverTs, text, replyTo, attachments, edited, editedAt, deleted, decryptError, failureReason, deliveredTo, readBy, systemType, systemUserId, systemDeviceId, systemDeviceName, pendingEnvelopeType, pendingEnvelopeBody) " +
                "VALUES ('o1', 'c1', 'me', 'dev', 1, 'READ', 3, 3, 3, 'skickat', NULL, '[]', 0, NULL, 0, NULL, NULL, 'bob', 'bob', NULL, NULL, NULL, NULL, NULL, NULL)",
        )
    }

    @Test fun `version 3 to 4 keeps every row, messages keep their old receipt status and the new tables start empty`() {
        helper.createDatabase("migration-v3-v4", 3).use { fillVersion3(it) }
        // validated against 4.json: columns, primary keys and indices of message_receipts and local_deletions are what Room expects
        helper.runMigrationsAndValidate("migration-v3-v4", 4, true, AppDatabase.MIGRATION_3_4).use { db ->
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }
            assertEquals(1L, count("account"))
            assertEquals(1L, count("conversations"))
            assertEquals(3L, count("messages"))
            assertEquals(1L, count("drafts"))
            assertEquals(1L, count("conversation_settings"))
            assertEquals(1L, count("signal_identities"))
            assertEquals(0L, count("message_receipts"), "times are only known from receipts received after the update")
            assertEquals(0L, count("local_deletions"))
            db.query("SELECT status, deliveredTo, readBy FROM messages WHERE id = 'o1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("READ", c.getString(0)); assertEquals("bob", c.getString(1)); assertEquals("bob", c.getString(2))
            }
            db.query("SELECT cursor FROM sync_state WHERE id = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals(77L, c.getLong(0)) }
            assertEquals(listOf("m1", "m2"), matches(db, "hej")) // the search index survived
            db.execSQL("INSERT INTO message_receipts (messageId, userId, conversationId, deliveredAt, readAt) VALUES ('o1', 'bob', 'c1', 5, 8)")
            db.execSQL("INSERT INTO local_deletions (messageId, conversationId, deletedAt) VALUES ('m1', 'c1', 6)")
            assertEquals(1L, count("message_receipts"))
            assertEquals(1L, count("local_deletions"))
        }
    }

    @Test fun `the app opens a version 3 file through the real builder and uses receipts and local deletions`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        helper.createDatabase("familychat-upgrade3.db", 3).use { fillVersion3(it) }
        val db = AppDatabase.build(context, factory = null, name = "familychat-upgrade3.db") { it.allowMainThreadQueries() }
        try {
            kotlinx.coroutines.runBlocking {
                val store = se.familychat.app.data.db.RoomLocalStore(db)
                assertEquals(setOf("bob"), store.getMessage("o1")!!.readBy)
                assertTrue(store.getReceipts("o1").isEmpty())
                store.recordReceipt("c1", "o1", "bob", deliveredAt = 5, readAt = null)
                store.recordReceipt("c1", "o1", "bob", deliveredAt = 99, readAt = 8)
                assertEquals(listOf(se.familychat.core.client.ReceiptTimes("o1", "bob", 5, 8)), store.getReceipts("o1"))
                assertTrue(store.deleteMessageLocally("m1", "c1", 10) != null)
                assertEquals(null, store.getMessage("m1"))
                assertTrue(store.isLocallyDeleted("m1"))
                assertEquals(listOf("m2"), SearchRepository(db).search("hej").map { it.messageId })
            }
        } finally {
            db.close()
            context.deleteDatabase("familychat-upgrade3.db")
        }
    }

    @Test fun `the app opens a version 2 file through the real builder, searches it and keeps the index current`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        helper.createDatabase("familychat-upgrade2.db", 2).use { fillVersion1(it) }
        val db = AppDatabase.build(context, factory = null, name = "familychat-upgrade2.db") { it.allowMainThreadQueries() }
        try {
            kotlinx.coroutines.runBlocking {
                val search = SearchRepository(db)
                assertEquals(listOf("m2", "m1"), search.search("hej").map { it.messageId })
                val store = se.familychat.app.data.db.RoomLocalStore(db)
                store.updateMessage("m1") { it.copy(text = "tjena", edited = true) }
                assertEquals(listOf("m2"), search.search("hej").map { it.messageId })
                assertEquals(listOf("m1"), search.search("tjena").map { it.messageId })
                store.updateMessage("m1") { it.copy(text = null, deleted = true) }
                assertEquals(emptyList(), search.search("tjena").map { it.messageId })
                assertEquals(null, ConversationSettingsStore(db).settings("c1"))
                assertTrue(ConversationSettingsStore(db).pin("c1", 1))
            }
        } finally {
            db.close()
            context.deleteDatabase("familychat-upgrade2.db")
        }
    }

    @Test fun `every version from 1 to the current one has a migration`() {
        val covered = AppDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()
        var v = 1
        while (v < AppDatabase.VERSION) {
            assertTrue(covered.any { it.first == v }, "no migration starting at version $v")
            v = covered.filter { it.first == v }.maxOf { it.second }
        }
        assertEquals(AppDatabase.VERSION, maxOf(v, 1))
    }
}
