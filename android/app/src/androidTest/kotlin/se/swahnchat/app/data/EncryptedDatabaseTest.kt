package se.swahnchat.app.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.swahnchat.app.appContext
import se.swahnchat.app.data.db.AppDatabase
import se.swahnchat.app.data.db.DraftEntity
import se.swahnchat.app.data.security.DatabaseProvider
import se.swahnchat.app.data.security.KeystoreSecretWrapper
import se.swahnchat.app.data.security.PassphraseVault
import se.swahnchat.app.deleteKeystoreAlias
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The encrypted database as the app opens it: [DatabaseProvider] with a [PassphraseVault] on the real Android Keystore and
 * the real SQLCipher native library, on a file of its own (never the app's `swahnchat.db`).
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val id = UUID.randomUUID().toString()
    private val alias = "swahnchat_test_$id"
    private val name = "swahnchat-test-$id.db"
    private val secretText = "hemligt-utkast-$id"
    private lateinit var dir: File
    private val vault by lazy { PassphraseVault(File(dir, PassphraseVault.FILE_NAME), KeystoreSecretWrapper(appContext, alias)) }
    private val dbFile get() = appContext.getDatabasePath(name)

    @Before fun setUp() {
        dir = File(appContext.cacheDir, "dbtest-$id").apply { mkdirs() }
    }

    @After fun tearDown() {
        appContext.deleteDatabase(name)
        deleteKeystoreAlias(alias)
        dir.deleteRecursively()
    }

    private fun open() = DatabaseProvider.create(appContext, vault, name)

    private fun AppDatabase.draft(conversation: String) = runBlocking { storeDao().draft(conversation) }

    @Test fun writesReadsAndSurvivesReopeningAsAnEncryptedFile() {
        val db = open()
        runBlocking { db.storeDao().putDraft(DraftEntity("c1", secretText, 5)) }
        assertEquals(secretText, db.draft("c1"))
        // it really is SQLCipher, not the framework's SQLite with a pretty name
        db.openHelper.writableDatabase.query("PRAGMA cipher_version").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.getString(0).isNotBlank())
        }
        db.close()

        assertTrue(dbFile.isFile)
        val header = ByteArray(16).also { h -> dbFile.inputStream().use { it.read(h) } }
        assertNotEquals("SQLite format 3\u0000", String(header, Charsets.ISO_8859_1), "the file is a plain SQLite database")
        // neither the database nor its journal/WAL contains the text
        for (f in listOfNotNull(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-journal")).filter { it.isFile }) {
            assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains(secretText), "${f.name} holds plaintext")
        }

        val again = open()
        try {
            assertEquals(secretText, again.draft("c1"), "data is back after reopening with the vault's passphrase")
        } finally {
            again.close()
        }
    }

    @Test fun aWrongPassphraseCannotOpenIt() {
        open().apply { runBlocking { storeDao().putDraft(DraftEntity("c1", secretText, 1)) } }.close()
        System.loadLibrary("sqlcipher")
        val wrong = ByteArray(PassphraseVault.LENGTH) { 9 }
        assertNotEquals(wrong.toList(), vault.getOrCreate().toList())

        // Attempts run on a copy: the corruption handler of a failed open may delete the file it was pointed at
        val copyName = "copy-$name"
        dbFile.copyTo(appContext.getDatabasePath(copyName), overwrite = true)
        try {
            val intruder = AppDatabase.build(appContext, factoryFor(wrong), copyName)
            try {
                runCatching { intruder.draft("c1") }.onSuccess { fail("opened with the wrong passphrase: $it") }
            } finally {
                runCatching { intruder.close() }
            }
            // and no passphrase at all (plain SQLite) cannot read it either. Opened read-only with an error handler
            // that keeps the file: Room's default handler would delete the "corrupt" file and silently create an
            // empty database, which proves nothing.
            appContext.deleteDatabase(copyName)
            val copy = appContext.getDatabasePath(copyName)
            dbFile.copyTo(copy, overwrite = true)
            val plainRead = runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    copy.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                ) { /* keep the file */ }.use { db ->
                    db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { c -> c.moveToFirst(); c.getInt(0) }
                }
            }
            plainRead.onSuccess { fail("plain SQLite read the encrypted file ($it tables)") }
            assertEquals(dbFile.length(), copy.length(), "file must survive the failed open")
        } finally {
            appContext.deleteDatabase(copyName)
        }
        // the right one still works
        val ok = open()
        try {
            assertEquals(secretText, ok.draft("c1"))
        } finally {
            ok.close()
        }
    }

    @Test fun aLostKeystoreKeyStartsOverWithAnEmptyDatabase() {
        open().apply { runBlocking { storeDao().putDraft(DraftEntity("c1", secretText, 1)) } }.close()
        val before = dbFile.readBytes()
        deleteKeystoreAlias(alias)

        val fresh = open() // the production recovery path: wipe the unreadable file, new key, new passphrase
        try {
            assertNull(fresh.draft("c1"))
            runBlocking { fresh.storeDao().putDraft(DraftEntity("c2", "nytt", 2)) }
            assertEquals("nytt", fresh.draft("c2"))
        } finally {
            fresh.close()
        }
        assertNotEquals(before.toList(), dbFile.readBytes().toList())
    }

    /** SQLCipher clears the passphrase array it is given, so every helper gets its own copy. */
    private fun factoryFor(passphrase: ByteArray) = object : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration) =
            SupportOpenHelperFactory(passphrase.copyOf()).create(configuration)
    }
}
