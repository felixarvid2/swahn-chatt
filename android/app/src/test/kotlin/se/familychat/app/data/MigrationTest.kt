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
