package se.familychat.app.data

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.RoomLocalStore
import se.familychat.app.data.security.LazyOpenHelperFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class LazyOpenHelperTest {
    @Test fun `the real helper (and so the passphrase) is only created when the database is first used`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("lazy.db")
        val created = AtomicInteger()
        val factory = LazyOpenHelperFactory { created.incrementAndGet(); FrameworkSQLiteOpenHelperFactory() }
        val db = AppDatabase.build(context, factory, "lazy.db") { it.allowMainThreadQueries() }
        try {
            assertEquals(0, created.get(), "building the Room database must not open it")
            val store = RoomLocalStore(db)
            runBlocking {
                assertNull(store.getMeta("k")) // first use
                store.setMeta("k", "v")
                assertEquals("v", store.getMeta("k"))
            }
            assertEquals(1, created.get())
            // write-ahead logging, requested by Room before the helper existed, was applied to it
            assertEquals("wal", db.openHelper.writableDatabase.query("PRAGMA journal_mode").use { it.moveToFirst(); it.getString(0) })
            // and the production durability pragma ran on open; inside a transaction every statement uses the primary
            // connection (pragmas are per connection, and plain reads may be served by another one)
            val sdb = db.openHelper.writableDatabase
            sdb.beginTransaction()
            try {
                assertEquals(2, sdb.query("PRAGMA synchronous").use { it.moveToFirst(); it.getInt(0) }, "FULL = 2")
            } finally {
                sdb.endTransaction()
            }
        } finally {
            db.close()
        }
    }
}
