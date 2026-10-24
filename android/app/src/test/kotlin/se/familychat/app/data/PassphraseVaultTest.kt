package se.familychat.app.data

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.familychat.app.data.security.PassphraseVault
import se.familychat.app.data.security.SecretWrapper
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
}
