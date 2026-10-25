package se.swahnchat.app.data.security

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import se.swahnchat.app.data.db.AppDatabase

/** Opens the SQLCipher-encrypted [AppDatabase] with the passphrase from the [PassphraseVault]. */
object DatabaseProvider {
    private const val TAG = "DatabaseProvider"

    @Volatile private var loaded = false

    private fun loadNative() {
        if (!loaded) {
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }

    /**
     * The passphrase for opening the database. If the Keystore key was lost for good (restored device image, cleared
     * credential storage: [PassphraseVault.UnreadableException]) the database cannot be opened any more: start with an
     * empty one; the user logs in again, which creates a new device.
     *
     * Any other failure ([PassphraseVault.TemporarilyUnavailableException]: the Keystore did not answer) is passed on and
     * the database is left alone; the app shows an error screen and the next attempt retries.
     */
    internal fun passphrase(vault: PassphraseVault, wipeDatabase: () -> Unit): ByteArray = try {
        vault.getOrCreate()
    } catch (e: PassphraseVault.UnreadableException) {
        wipeDatabase()
        vault.reset()
        vault.getOrCreate()
    }

    /**
     * Nothing happens here until the first query (on a background thread): then the native library is loaded, the
     * passphrase is read and SQLCipher opens the file. A fresh copy of the passphrase goes to SQLCipher each time because
     * it may wipe the array it was given.
     */
    fun create(
        context: Context,
        vault: PassphraseVault = PassphraseVault.forContext(context),
        name: String = AppDatabase.FILE_NAME, // only the instrumented tests use another file
    ): AppDatabase {
        val app = context.applicationContext
        val factory = LazyOpenHelperFactory {
            loadNative()
            SupportOpenHelperFactory(
                passphrase(vault) {
                    Log.w(TAG, "Database key unreadable, resetting the local database")
                    app.deleteDatabase(name)
                },
            )
        }
        return AppDatabase.build(app, factory, name)
    }
}
