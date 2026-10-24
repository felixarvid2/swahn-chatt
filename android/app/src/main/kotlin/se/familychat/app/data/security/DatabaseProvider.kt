package se.familychat.app.data.security

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import se.familychat.app.data.db.AppDatabase

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
     * The passphrase for opening the database. If the Keystore key was lost (restored device image, cleared credential
     * storage) the database cannot be opened any more: start with an empty one; the user logs in again, which creates a
     * new device.
     */
    private fun passphrase(context: Context, vault: PassphraseVault): ByteArray = try {
        vault.getOrCreate()
    } catch (e: PassphraseVault.UnreadableException) {
        Log.w(TAG, "Database key unreadable, resetting the local database", e)
        context.deleteDatabase(AppDatabase.FILE_NAME)
        vault.reset()
        vault.getOrCreate()
    }

    /**
     * Nothing happens here until the first query (on a background thread): then the native library is loaded, the
     * passphrase is read and SQLCipher opens the file. A fresh copy of the passphrase goes to SQLCipher each time because
     * it may wipe the array it was given.
     */
    fun create(context: Context, vault: PassphraseVault = PassphraseVault.forContext(context)): AppDatabase {
        val app = context.applicationContext
        val factory = LazyOpenHelperFactory {
            loadNative()
            SupportOpenHelperFactory(passphrase(app, vault))
        }
        return AppDatabase.build(app, factory)
    }
}
