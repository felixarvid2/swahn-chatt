package se.swahnchat.app.data.security

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Defers creating the real open helper (which needs the SQLCipher passphrase from the Keystore, and loads the native
 * library) until the database is first used. Room builds its database object during dependency injection on the main
 * thread but only touches the helper's database on a background thread; this keeps the Keystore work (slow with StrongBox)
 * and the key derivation off the main thread.
 *
 * [setWriteAheadLoggingEnabled], which Room calls while building, is remembered and applied once the helper exists.
 */
class LazyOpenHelper(private val create: () -> SupportSQLiteOpenHelper, override val databaseName: String?) : SupportSQLiteOpenHelper {
    private var wal: Boolean? = null
    private val delegate: SupportSQLiteOpenHelper by lazy {
        create().also { helper -> wal?.let { helper.setWriteAheadLoggingEnabled(it) } }
    }
    private val created = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun real(): SupportSQLiteOpenHelper = delegate.also { created.set(true) }

    @Synchronized
    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        wal = enabled
        if (created.get()) delegate.setWriteAheadLoggingEnabled(enabled)
    }

    override val writableDatabase: SupportSQLiteDatabase get() = real().writableDatabase
    override val readableDatabase: SupportSQLiteDatabase get() = real().readableDatabase

    override fun close() {
        if (created.get()) delegate.close()
    }
}

/** A [SupportSQLiteOpenHelper.Factory] whose helpers are created lazily from [inner] (see [LazyOpenHelper]). */
class LazyOpenHelperFactory(private val inner: () -> SupportSQLiteOpenHelper.Factory) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        LazyOpenHelper({ inner().create(configuration) }, configuration.name)
}
