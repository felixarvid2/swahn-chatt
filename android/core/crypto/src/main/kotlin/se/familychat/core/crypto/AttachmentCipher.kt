package se.familychat.core.crypto

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** Result of [AttachmentCipher.encrypt]. [sha256Hex] and [ciphertextSize] describe the bytes written (the upload). */
class EncryptedAttachment(val key: ByteArray, val sha256Hex: String, val ciphertextSize: Long, val plaintextSize: Long)

/**
 * Attachment encryption with Tink's streaming AEAD `AES256_GCM_HKDF_4KB` (AES-256-GCM, HKDF-SHA256, 4 KiB segments)
 * under a fresh random 32-byte key per file. Segments are authenticated individually and the stream end is
 * authenticated too, so tampering, reordering and truncation are all detected on read. The key never reaches the
 * server: it travels in the Signal-encrypted message `Content`.
 *
 * Associated data is the constant [ASSOCIATED_DATA] (the attachment purpose), binding ciphertext to its use.
 */
object AttachmentCipher {
    const val KEY_SIZE = 32
    private const val SEGMENT_SIZE = 4096
    val ASSOCIATED_DATA: ByteArray = "familychat/attachment/v1".toByteArray(Charsets.UTF_8)

    private val random = SecureRandom()

    fun generateKey(): ByteArray = ByteArray(KEY_SIZE).also { random.nextBytes(it) }

    private fun streaming(key: ByteArray): AesGcmHkdfStreaming {
        require(key.size == KEY_SIZE) { "Attachment key must be $KEY_SIZE bytes" }
        return AesGcmHkdfStreaming(key, "HmacSha256", KEY_SIZE, SEGMENT_SIZE, 0)
    }

    /**
     * Encrypts [input] to [output] with a new random key and closes neither stream. Returns the key and the
     * SHA-256 (hex) of the ciphertext, which is what the server stores and checks via `X-Content-SHA256`.
     */
    fun encrypt(input: InputStream, output: OutputStream, key: ByteArray = generateKey()): EncryptedAttachment {
        val digest = MessageDigest.getInstance("SHA-256")
        val counting = CountingOutputStream(DigestOutputStream(output, digest))
        var plain = 0L
        streaming(key).newEncryptingStream(counting, ASSOCIATED_DATA).use { enc ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                enc.write(buf, 0, n)
                plain += n
            }
        } // closing the encrypting stream writes the final segment; it also closes [counting], flushing [output]
        return EncryptedAttachment(key, hex(digest.digest()), counting.count, plain)
    }

    /** File-to-file convenience for [encrypt]. */
    fun encryptFile(source: File, target: File, key: ByteArray = generateKey()): EncryptedAttachment =
        source.inputStream().buffered().use { i -> target.outputStream().buffered().use { o -> encrypt(i, o, key) } }

    /**
     * Decrypts [input] into [output] and returns the number of plaintext bytes. Bytes are written to [output] as
     * segments verify, so on failure [output] holds a partial (verified-prefix) plaintext that the caller must discard.
     *
     * @throws AttachmentCryptoException wrong key, tampered, reordered or truncated ciphertext
     */
    fun decrypt(input: InputStream, output: OutputStream, key: ByteArray): Long {
        var total = 0L
        try {
            streaming(key).newDecryptingStream(input, ASSOCIATED_DATA).use { dec ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = dec.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    total += n
                }
            }
        } catch (e: IOException) {
            throw AttachmentCryptoException("Attachment could not be decrypted: ${e.message}", e)
        } catch (e: java.security.GeneralSecurityException) {
            throw AttachmentCryptoException("Attachment could not be decrypted: ${e.message}", e)
        }
        return total
    }

    /** Decrypts [source] into a new file [target]; deletes [target] on failure. */
    fun decryptFile(source: File, target: File, key: ByteArray): Long {
        try {
            return source.inputStream().buffered().use { i -> target.outputStream().buffered().use { o -> decrypt(i, o, key) } }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** Lower-case hex SHA-256 of a stream (read fully, not closed). */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return hex(digest.digest())
    }

    fun sha256Hex(file: File): String = file.inputStream().buffered().use { sha256Hex(it) }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
