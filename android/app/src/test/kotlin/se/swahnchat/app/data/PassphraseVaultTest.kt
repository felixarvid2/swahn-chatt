package se.swahnchat.app.data

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.swahnchat.app.data.security.DatabaseProvider
import se.swahnchat.app.data.security.KeyLostException
import se.swahnchat.app.data.security.PassphraseVault
import se.swahnchat.app.data.security.SecretWrapper
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The vault logic with a software AES-GCM key standing in for the Android Keystore (which does not exist on the build
 * machine). The wrapper's own contract is the same: authenticated encryption that fails for foreign or damaged blobs.
 */
class PassphraseVaultTest {
    @get:Rule val tmp = TemporaryFolder()

    private class SoftwareWrapper(private val keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : SecretWrapper {
        override fun wrap(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            return byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(plain)
        }

        override fun unwrap(blob: ByteArray): ByteArray {
            if (blob.isEmpty()) throw GeneralSecurityException("empty")
            val n = blob[0].toInt()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, blob, 1, n))
            return c.doFinal(blob, 1 + n, blob.size - 1 - n)
        }
    }

    @Test fun `a 32 byte passphrase is created once and then returned unchanged`() {
        val file = tmp.newFolder("nobackup").resolve("db_key.bin")
        val wrapper = SoftwareWrapper()
        val vault = PassphraseVault(file, wrapper)
        assertFalse(vault.exists())
        val first = vault.getOrCreate()
        assertEquals(32, first.size)
        assertTrue(vault.exists())
        assertContentEquals(first, PassphraseVault(file, wrapper).getOrCreate())
    }

    @Test fun `the stored blob does not contain the passphrase`() {
        val file = tmp.newFile("db_key.bin").also { it.delete() }
        val pass = PassphraseVault(file, SoftwareWrapper()).getOrCreate()
        val blob = file.readBytes()
        assertNotEquals(pass.size, blob.size) // iv + tag add to it
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(String(pass, Charsets.ISO_8859_1)))
    }

    @Test fun `two installations get different passphrases`() {
        val a = PassphraseVault(tmp.newFolder("a").resolve("k"), SoftwareWrapper()).getOrCreate()
        val b = PassphraseVault(tmp.newFolder("b").resolve("k"), SoftwareWrapper()).getOrCreate()
        assertFalse(a.contentEquals(b))
    }

    @Test fun `a blob made by another key is reported as unreadable`() {
        val file = tmp.newFolder("c").resolve("k")
        PassphraseVault(file, SoftwareWrapper()).getOrCreate()
        assertFailsWith<PassphraseVault.UnreadableException> { PassphraseVault(file, SoftwareWrapper()).getOrCreate() }
    }

    @Test fun `a damaged blob is reported as unreadable and reset allows a fresh start`() {
        val wrapper = SoftwareWrapper()
        val file = tmp.newFolder("d").resolve("k")
        val vault = PassphraseVault(file, wrapper)
        val original = vault.getOrCreate()
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertFailsWith<PassphraseVault.UnreadableException> { vault.getOrCreate() }
        vault.reset()
        assertFalse(vault.exists())
        assertFalse(original.contentEquals(vault.getOrCreate()))
    }

    // --- F7: only a conclusive loss of the key may cost the database -----------------------------------------------

    /** Fails the first [failures] calls of wrap/unwrap with [error], then behaves like [delegate]. */
    private class FlakyWrapper(
        private val delegate: SecretWrapper,
        var failures: Int,
        private val error: () -> Exception,
    ) : SecretWrapper {
        var calls = 0
        private fun maybeFail() {
            calls++
            if (failures > 0) { failures--; throw error() }
        }

        override fun wrap(plain: ByteArray): ByteArray { maybeFail(); return delegate.wrap(plain) }
        override fun unwrap(blob: ByteArray): ByteArray { maybeFail(); return delegate.unwrap(blob) }
    }

    private fun vault(file: java.io.File, wrapper: SecretWrapper) = PassphraseVault(file, wrapper, retryDelayMs = 0)

    @Test fun `a runtime failure of the key store is retried and then succeeds`() {
        val file = tmp.newFolder("e").resolve("k")
        val soft = SoftwareWrapper()
        val original = vault(file, soft).getOrCreate()
        for (error in listOf<() -> Exception>({ IllegalStateException("keystore daemon died") }, { java.security.ProviderException("busy") }, { GeneralSecurityException("not now") }, { RuntimeException("x") })) {
            val flaky = FlakyWrapper(soft, failures = 2, error = error)
            assertContentEquals(original, vault(file, flaky).getOrCreate())
            assertEquals(3, flaky.calls)
        }
    }

    @Test fun `a persistent runtime failure is temporary unavailability and never unreadable`() {
        val file = tmp.newFolder("f").resolve("k")
        val soft = SoftwareWrapper()
        val original = vault(file, soft).getOrCreate()
        val blob = file.readBytes()
        val flaky = FlakyWrapper(soft, failures = 100) { IllegalStateException("keystore daemon died") }
        assertFailsWith<PassphraseVault.TemporarilyUnavailableException> { vault(file, flaky).getOrCreate() }
        assertTrue(file.isFile, "the stored key must stay in place")
        assertContentEquals(blob, file.readBytes())
        assertContentEquals(original, vault(file, soft).getOrCreate(), "and still works when the key store is back")
    }

    @Test fun `a missing or invalidated key is a conclusive loss`() {
        val file = tmp.newFolder("g").resolve("k")
        vault(file, SoftwareWrapper()).getOrCreate()
        val gone = FlakyWrapper(SoftwareWrapper(), failures = 100) { KeyLostException("alias missing") }
        val e = assertFailsWith<PassphraseVault.UnreadableException> { vault(file, gone).getOrCreate() }
        assertTrue(e.cause is KeyLostException)
        assertEquals(1, gone.calls, "a conclusive answer is not retried")
    }

    @Test fun `failing to create the key is not a lost database and stores nothing`() {
        val file = tmp.newFolder("h").resolve("k")
        val flaky = FlakyWrapper(SoftwareWrapper(), failures = 100) { GeneralSecurityException("cannot generate") }
        assertFailsWith<PassphraseVault.TemporarilyUnavailableException> { vault(file, flaky).getOrCreate() }
        assertFalse(file.exists())
    }

    @Test fun `the database is wiped only when the key is conclusively lost`() {
        val file = tmp.newFolder("i").resolve("k")
        val soft = SoftwareWrapper()
        vault(file, soft).getOrCreate()
        var wiped = 0
        // transient: passed on, nothing wiped, key file untouched
        val busy = FlakyWrapper(soft, failures = 100) { IllegalStateException("busy") }
        assertFailsWith<PassphraseVault.TemporarilyUnavailableException> { DatabaseProvider.passphrase(vault(file, busy)) { wiped++ } }
        assertEquals(0, wiped)
        assertTrue(file.isFile)
        // lost: wiped once, new key
        val fresh = DatabaseProvider.passphrase(vault(file, SoftwareWrapper())) { wiped++ }
        assertEquals(1, wiped)
        assertEquals(32, fresh.size)
    }
}
