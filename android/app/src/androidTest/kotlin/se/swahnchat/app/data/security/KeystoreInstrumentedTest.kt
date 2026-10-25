package se.swahnchat.app.data.security

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.swahnchat.app.appContext
import se.swahnchat.app.deleteKeystoreAlias
import se.swahnchat.app.keystoreHasAlias
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real AndroidKeyStore behind [KeystoreSecretWrapper] and [PassphraseVault]; every test uses its own key alias. */
@RunWith(AndroidJUnit4::class)
class KeystoreInstrumentedTest {
    private val alias = "swahnchat_test_" + UUID.randomUUID()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(appContext.cacheDir, "vault-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After fun tearDown() {
        deleteKeystoreAlias(alias)
        dir.deleteRecursively()
    }

    private fun wrapper() = KeystoreSecretWrapper(appContext, alias)
    private fun vault(w: SecretWrapper = wrapper()) = PassphraseVault(File(dir, PassphraseVault.FILE_NAME), w, retryDelayMs = 0)

    @Test fun wrapAndUnwrapRoundTrip() {
        val w = wrapper()
        val secret = ByteArray(32) { it.toByte() }
        val blob = w.wrap(secret)
        assertTrue(blob.size > secret.size, "the blob carries IV and tag")
        assertContentEquals(secret, w.unwrap(blob))
        // a second wrapper object (a later process) finds the same key by its alias
        assertContentEquals(secret, wrapper().unwrap(blob))
        // fresh random IV each time
        assertNotEquals(blob.toList(), w.wrap(secret).toList())
    }

    @Test fun theKeyIsNonExportableAndAesGcm() {
        wrapper().wrap(ByteArray(32))
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(alias, null) as SecretKey
        assertNull(key.encoded, "key material must not be readable")
        assertEquals("AES", key.algorithm)
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes.toList())
        assertFalse(info.isUserAuthenticationRequired, "background workers must be able to open the database")
    }

    @Test fun aDeletedKeyIsReportedAsLostAndNeverRecreatedByUnwrap() {
        val w = wrapper()
        val blob = w.wrap(ByteArray(32) { 7 })
        deleteKeystoreAlias(alias)
        assertFailsWith<KeyLostException> { w.unwrap(blob) }
        assertFalse(keystoreHasAlias(alias), "reading must not create a replacement key")
    }

    @Test fun aBlobFromAnotherKeyFailsAuthentication() {
        val blob = wrapper().wrap(ByteArray(32) { 1 })
        deleteKeystoreAlias(alias)
        val other = wrapper()
        other.wrap(ByteArray(32)) // creates a new key under the same alias
        assertFailsWith<javax.crypto.AEADBadTagException> { other.unwrap(blob) }
    }

    @Test fun vaultCreatesOnceAndReadsTheSamePassphraseBack() {
        val first = vault().getOrCreate()
        assertEquals(PassphraseVault.LENGTH, first.size)
        val file = File(dir, PassphraseVault.FILE_NAME)
        assertTrue(file.isFile)
        assertFalse(file.readBytes().toList().windowed(first.size).any { it == first.toList() }, "stored wrapped, not in the clear")
        assertContentEquals(first, vault().getOrCreate())
    }

    @Test fun lostKeyIsDetectedByTheVaultAndOnlyThenTheDatabaseIsWiped() {
        val v = vault()
        val original = v.getOrCreate()
        var wipes = 0

        // healthy key: nothing is wiped
        assertContentEquals(original, DatabaseProvider.passphrase(v) { wipes++ })
        assertEquals(0, wipes)

        // key gone: the vault says so, the provider wipes exactly once and continues with a new passphrase
        deleteKeystoreAlias(alias)
        assertFailsWith<PassphraseVault.UnreadableException> { v.getOrCreate() }
        val fresh = DatabaseProvider.passphrase(v) { wipes++ }
        assertEquals(1, wipes)
        assertEquals(PassphraseVault.LENGTH, fresh.size)
        assertNotEquals(original.toList(), fresh.toList())
        assertTrue(keystoreHasAlias(alias), "a new key was generated for the new passphrase")
        assertContentEquals(fresh, DatabaseProvider.passphrase(v) { wipes++ })
        assertEquals(1, wipes)
    }

    @Test fun aDamagedBlobCountsAsLost() {
        val v = vault()
        v.getOrCreate()
        val file = File(dir, PassphraseVault.FILE_NAME)
        val blob = file.readBytes()
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        file.writeBytes(blob)
        assertFailsWith<PassphraseVault.UnreadableException> { v.getOrCreate() }
    }

    @Test fun aKeystoreThatFailsTemporarilyNeverWipes() {
        val v = vault()
        v.getOrCreate()
        val flaky = object : SecretWrapper {
            override fun wrap(plain: ByteArray) = throw GeneralSecurityException("keystore daemon busy")
            override fun unwrap(blob: ByteArray): ByteArray = throw GeneralSecurityException("keystore daemon busy")
        }
        var wipes = 0
        assertFailsWith<PassphraseVault.TemporarilyUnavailableException> {
            DatabaseProvider.passphrase(vault(flaky)) { wipes++ }
        }
        assertEquals(0, wipes)
        assertTrue(File(dir, PassphraseVault.FILE_NAME).isFile)
    }
}
