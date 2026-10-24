package se.familychat.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AttachmentCipherTest {
    private fun encrypt(data: ByteArray, key: ByteArray = AttachmentCipher.generateKey()): Pair<EncryptedAttachment, ByteArray> {
        val out = ByteArrayOutputStream()
        val r = AttachmentCipher.encrypt(ByteArrayInputStream(data), out, key)
        return r to out.toByteArray()
    }

    private fun decrypt(ct: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        AttachmentCipher.decrypt(ByteArrayInputStream(ct), out, key)
        return out.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `round trip of sizes around segment boundaries`() {
        for (n in listOf(0, 1, 100, 4095, 4096, 4097, 8192, 100_000)) {
            val data = Random(n).nextBytes(n)
            val (r, ct) = encrypt(data)
            assertEquals(n.toLong(), r.plaintextSize)
            assertEquals(ct.size.toLong(), r.ciphertextSize)
            assertEquals(sha(ct), r.sha256Hex)
            assertContentEquals(data, decrypt(ct, r.key), "size $n")
        }
    }

    @Test
    fun `multi megabyte round trip via files`() {
        val data = Random(1).nextBytes(7 * 1024 * 1024 + 123)
        val src = File.createTempFile("att", ".src").apply { writeBytes(data); deleteOnExit() }
        val enc = File.createTempFile("att", ".enc").apply { deleteOnExit() }
        val dec = File.createTempFile("att", ".dec").apply { deleteOnExit() }
        val r = AttachmentCipher.encryptFile(src, enc)
        assertEquals(enc.length(), r.ciphertextSize)
        assertTrue(enc.length() > data.size)
        assertEquals(r.sha256Hex, AttachmentCipher.sha256Hex(enc))
        assertEquals(data.size.toLong(), AttachmentCipher.decryptFile(enc, dec, r.key))
        assertContentEquals(data, dec.readBytes())
    }

    @Test
    fun `ciphertext hides plaintext and each file gets a fresh key and nonce`() {
        val marker = "TOP-SECRET-ATTACHMENT-MARKER".toByteArray()
        val data = marker + ByteArray(10_000)
        val (r1, c1) = encrypt(data)
        val (r2, c2) = encrypt(data)
        assertFalse(String(c1, Charsets.ISO_8859_1).contains("TOP-SECRET"))
        assertNotEquals(r1.key.toList(), r2.key.toList())
        assertEquals(32, r1.key.size)
        assertNotEquals(c1.toList(), c2.toList())
    }

    @Test
    fun `tampered ciphertext fails`() {
        val data = Random(2).nextBytes(60_000)
        val (r, ct) = encrypt(data)
        for (pos in listOf(0, 5, 20, 100, 4096 + 7, ct.size / 2, ct.size - 1)) {
            val bad = ct.copyOf().also { it[pos] = (it[pos].toInt() xor 1).toByte() }
            assertFailsWith<AttachmentCryptoException>("flip at $pos") { decrypt(bad, r.key) }
        }
    }

    @Test
    fun `truncated or extended ciphertext fails`() {
        val data = Random(3).nextBytes(60_000)
        val (r, ct) = encrypt(data)
        assertFailsWith<AttachmentCryptoException> { decrypt(ct.copyOf(ct.size - 1), r.key) }
        assertFailsWith<AttachmentCryptoException> { decrypt(ct.copyOf(4096), r.key) } // exactly one segment
        assertFailsWith<AttachmentCryptoException> { decrypt(ct.copyOf(ct.size - 4096), r.key) } // whole segment dropped
        assertFailsWith<AttachmentCryptoException> { decrypt(ct.copyOf(10), r.key) }
        assertFailsWith<AttachmentCryptoException> { decrypt(ByteArray(0), r.key) }
        assertFailsWith<AttachmentCryptoException> { decrypt(ct + byteArrayOf(0), r.key) }
    }

    @Test
    fun `wrong key fails`() {
        val (r, ct) = encrypt(Random(4).nextBytes(10_000))
        assertFailsWith<AttachmentCryptoException> { decrypt(ct, AttachmentCipher.generateKey()) }
        assertFailsWith<IllegalArgumentException> { decrypt(ct, r.key.copyOf(16)) }
    }

    @Test
    fun `failed file decrypt removes the output file`() {
        val (_, ct) = encrypt(Random(5).nextBytes(20_000))
        val src = File.createTempFile("att", ".enc").apply { writeBytes(ct); deleteOnExit() }
        val dst = File.createTempFile("att", ".dec")
        assertFailsWith<AttachmentCryptoException> { AttachmentCipher.decryptFile(src, dst, AttachmentCipher.generateKey()) }
        assertFalse(dst.exists())
    }
}
