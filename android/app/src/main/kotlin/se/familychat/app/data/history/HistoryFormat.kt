package se.familychat.app.data.history

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.Normalizer

/** Everything that can go wrong with a history file, worded for the screen. */
sealed class HistoryException(message: String) : Exception(message) {
    /** Not a FamilyChat history file (wrong first bytes, or too short to be one). */
    class NotAHistoryFile : HistoryException("This is not a FamilyChat history file")

    /** A format version this app does not know ([version]). */
    class UnsupportedVersion(val version: Int) : HistoryException("History format version $version is not supported")

    /** The header names a key derivation or cipher this app does not accept, or parameters outside the allowed range. */
    class UnsupportedParameters(detail: String) : HistoryException("Unsupported file parameters: $detail")

    /** The first block does not authenticate: the passphrase is wrong, or the header or the start of the data was changed. */
    class WrongPassphraseOrDamaged : HistoryException("Wrong passphrase, or the file is damaged")

    /** Authentication failed after the start: the file was changed or cut off. */
    class Damaged : HistoryException("The file is damaged, incomplete or has been changed")

    /** Bigger than the limits (the file itself, or what it unpacks to). */
    class TooLarge : HistoryException("The file is larger than allowed")

    /** The file was exported from another account. */
    class WrongAccount : HistoryException("The file belongs to another account")

    /** The payload is not what version 1 describes. */
    class BadContent(detail: String) : HistoryException("The file content is not valid: $detail")

    class WeakPassphrase : HistoryException("The passphrase is too short")
}

/** Key derivation parameters as stored in the header. */
data class KdfParams(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        /** Argon2id, 64 MiB, 3 passes, 1 lane. */
        val DEFAULT = KdfParams(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)

        // What an importer is willing to run. The upper bounds keep a crafted header from exhausting the phone's memory or time.
        const val MAX_MEMORY_KIB = 128 * 1024 // twice what an export uses; a crafted header must not be able to ask a phone for more
        const val MAX_ITERATIONS = 6
        const val MAX_PARALLELISM = 8
    }
}

/**
 * The file format of an encrypted history export ("FCHX", version 1). Documented in `docs/ANDROID_SETUP.md`.
 *
 * ```
 * offset size  field
 *   0     4    magic "FCHX"
 *   4     1    format version = 1
 *   5     1    key derivation id = 1 (Argon2id, version 1.3)
 *   6     4    memory in KiB (big endian)      -- 65536 = 64 MiB
 *  10     2    iterations (big endian)         -- 3
 *  12     1    parallelism                     -- 1
 *  13     1    salt length                     -- 16
 *  14    16    salt (random per file)
 *  30     1    cipher id = 1 (Tink streaming AEAD AES256_GCM_HKDF_1MB)
 *  31     ..   ciphertext: Tink streaming AEAD of a ZIP archive, associated data = bytes 0..30 (the whole header)
 * ```
 * The key is the 32-byte Argon2id output for the passphrase (NFKC-normalised, UTF-8) and the salt; Tink derives the segment
 * keys from it with HKDF-SHA256 and a random per-file salt of its own. Any change to the header changes the associated data,
 * and most also the key, so a modified header never decrypts.
 */
object HistoryFormat {
    val MAGIC: ByteArray = "FCHX".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val KDF_ARGON2ID = 1
    const val CIPHER_AES256_GCM_HKDF_1MB = 1
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    const val SEGMENT_BYTES = 1 shl 20
    const val HEADER_BYTES = 31
    const val MIN_PASSPHRASE_LENGTH = 12

    class Header(val kdf: KdfParams, val salt: ByteArray) {
        fun encode(): ByteArray = ByteBuffer.allocate(HEADER_BYTES).apply {
            put(MAGIC)
            put(VERSION.toByte())
            put(KDF_ARGON2ID.toByte())
            putInt(kdf.memoryKiB)
            putShort(kdf.iterations.toShort())
            put(kdf.parallelism.toByte())
            put(SALT_BYTES.toByte())
            put(salt)
            put(CIPHER_AES256_GCM_HKDF_1MB.toByte())
        }.array()
    }

    fun newHeader(kdf: KdfParams = KdfParams.DEFAULT, random: SecureRandom = SecureRandom()): Header =
        Header(kdf, ByteArray(SALT_BYTES).also { random.nextBytes(it) })

    /** Reads and checks the header (exactly [HEADER_BYTES] bytes); returns it with the raw bytes (the associated data). */
    @Suppress("ThrowsCount") // one refusal per field
    fun readHeader(input: InputStream): Pair<Header, ByteArray> {
        val raw = ByteArray(HEADER_BYTES)
        var read = 0
        while (read < raw.size) {
            val n = input.read(raw, read, raw.size - read)
            if (n < 0) break
            read += n
        }
        if (read < MAGIC.size || !raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw HistoryException.NotAHistoryFile()
        val version = raw[4].toInt() and 0xFF
        if (version != VERSION) throw HistoryException.UnsupportedVersion(version)
        if (read < HEADER_BYTES) throw HistoryException.NotAHistoryFile()
        val buf = ByteBuffer.wrap(raw)
        buf.position(5)
        val kdfId = buf.get().toInt() and 0xFF
        if (kdfId != KDF_ARGON2ID) throw HistoryException.UnsupportedParameters("key derivation $kdfId")
        val memory = buf.int
        val iterations = buf.short.toInt() and 0xFFFF
        val parallelism = buf.get().toInt() and 0xFF
        val saltLength = buf.get().toInt() and 0xFF
        if (saltLength != SALT_BYTES) throw HistoryException.UnsupportedParameters("salt length $saltLength")
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val cipher = buf.get().toInt() and 0xFF
        if (cipher != CIPHER_AES256_GCM_HKDF_1MB) throw HistoryException.UnsupportedParameters("cipher $cipher")
        if (memory !in 8 * parallelism.coerceAtLeast(1)..KdfParams.MAX_MEMORY_KIB ||
            iterations !in 1..KdfParams.MAX_ITERATIONS || parallelism !in 1..KdfParams.MAX_PARALLELISM
        ) {
            throw HistoryException.UnsupportedParameters("memory $memory KiB, $iterations passes, $parallelism lanes")
        }
        return Header(KdfParams(memory, iterations, parallelism), salt) to raw
    }

    /** The passphrase as the bytes that go into Argon2id: NFKC-normalised, UTF-8. */
    fun passphraseBytes(passphrase: CharSequence): ByteArray =
        Normalizer.normalize(passphrase, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)

    /** Argon2id over the passphrase; the caller wipes the result when done. */
    fun deriveKey(passphrase: CharSequence, header: Header): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(header.kdf.iterations)
            .withMemoryAsKB(header.kdf.memoryKiB)
            .withParallelism(header.kdf.parallelism)
            .withSalt(header.salt)
            .build()
        val generator = Argon2BytesGenerator().also { it.init(params) }
        val pw = passphraseBytes(passphrase)
        return try {
            guardMemory(header.kdf.memoryKiB) { ByteArray(KEY_BYTES).also { generator.generateBytes(pw, it) } }
        } finally {
            pw.fill(0)
        }
    }

    /** Runs the key derivation; a phone that cannot give the memory the header asks for refuses the file instead of crashing the app. */
    internal fun <T> guardMemory(memoryKiB: Int, block: () -> T): T = try {
        block()
    } catch (e: OutOfMemoryError) {
        throw HistoryException.UnsupportedParameters("not enough memory for ${memoryKiB / 1024} MiB")
    }

    /** Tink's `AES256_GCM_HKDF_1MB` as a primitive over the derived key. */
    fun streaming(key: ByteArray): AesGcmHkdfStreaming = AesGcmHkdfStreaming(key, "HmacSha256", KEY_BYTES, SEGMENT_BYTES, 0)
}
