package se.swahnchat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.swahnchat.app.data.db.AppDatabase
import se.swahnchat.app.data.db.RoomLocalStore
import se.swahnchat.app.data.db.RoomProtocolStorage
import se.swahnchat.core.client.InMemoryLocalStore
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.crypto.InMemoryProtocolStorage
import se.swahnchat.core.crypto.ProtocolStorage

/** The reference implementations pass their own contract (so the contract itself is right). */
class InMemoryLocalStoreContractTest : LocalStoreContract() {
    override fun newStore(): LocalStore = InMemoryLocalStore()
    override val rollsBack: Boolean = false
}

class InMemoryProtocolStorageContractTest : ProtocolStorageContract() {
    override fun newStorage(): ProtocolStorage = InMemoryProtocolStorage()
}

/**
 * The Room implementations on a real SQLite database (Robolectric's native SQLite, WAL like in the app).
 *
 * SQLCipher cannot run here: its native library is built for Android and cannot be loaded on the build machine's JVM.
 * What is exercised is everything above the open helper (DAOs, SQL, transactions, threading); the encryption layer is
 * SQLCipher's `SupportOpenHelperFactory`, which Room uses through the same `SupportSQLiteOpenHelper` interface as the
 * framework helper used here.
 */
@RunWith(RobolectricTestRunner::class)
class RoomLocalStoreContractTest : LocalStoreContract() {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = RoomTestDb.file(ApplicationProvider.getApplicationContext<Context>(), "store-contract.db")
    }

    @After fun close() = db.close()

    override fun newStore(): LocalStore = RoomLocalStore(db)
}

@RunWith(RobolectricTestRunner::class)
class RoomProtocolStorageContractTest : ProtocolStorageContract() {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = RoomTestDb.file(ApplicationProvider.getApplicationContext<Context>(), "protocol-contract.db")
    }

    @After fun close() = db.close()

    override fun newStorage(): ProtocolStorage = RoomProtocolStorage(db)
}

object RoomTestDb {
    /** A file database (write-ahead logging, several connections) like the production one, minus SQLCipher. */
    fun file(context: Context, name: String): AppDatabase {
        context.deleteDatabase(name)
        // through AppDatabase.build, so the production callbacks (synchronous=FULL) and migrations are part of what is tested
        return AppDatabase.build(context, factory = null, name = name) { it.allowMainThreadQueries() }
    }
}
