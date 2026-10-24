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
