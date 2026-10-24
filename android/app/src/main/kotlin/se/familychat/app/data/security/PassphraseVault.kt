package se.familychat.app.data.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and decrypts small secrets with a key that never leaves secure hardware / the Keystore daemon. */
interface SecretWrapper {
    fun wrap(plain: ByteArray): ByteArray

    /** @throws GeneralSecurityException if the blob was not produced by this key (or was tampered with) */
    fun unwrap(blob: ByteArray): ByteArray
}

/**
 * The AES-256-GCM key in the Android Keystore (non-exportable). It is generated on first use, in StrongBox where the
 * device has one, otherwise in the TEE / software Keystore. No user authentication is required, because background
 * workers must open the database while the phone is locked.
 */
class KeystoreSecretWrapper(
    private val context: Context,
    private val alias: String = DEFAULT_ALIAS,
) : SecretWrapper {

    /** True if the key lives in StrongBox (known after the key was created or loaded). */
    var strongBox: Boolean = false
        private set

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val wantStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return try {
            generate(wantStrongBox).also { strongBox = wantStrongBox }
        } catch (e: GeneralSecurityException) {
            fallbackOrThrow(wantStrongBox, e)
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException (API 28+) is a ProviderException; catching the superclass keeps API 26/27 happy
            fallbackOrThrow(wantStrongBox, e)
        }
    }

    /** Some devices advertise StrongBox but cannot create the key; the TEE / software Keystore is the fallback. */
    private fun fallbackOrThrow(triedStrongBox: Boolean, e: Exception): SecretKey {
        if (!triedStrongBox) throw e
        Log.i(TAG, "StrongBox key generation failed (${e.javaClass.simpleName}), using the default keystore")
        return generate(false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        if (blob.isEmpty()) throw GeneralSecurityException("empty blob")
        val ivLen = blob[0].toInt() and 0xFF
        if (blob.size < 1 + ivLen + 16) throw GeneralSecurityException("blob too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 1, ivLen))
        cipher.updateAAD(AAD)
        return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    companion object {
        const val DEFAULT_ALIAS = "familychat_db_wrap_v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG = "KeystoreSecretWrapper"
        private val AAD = "familychat-db-passphrase-v1".toByteArray()
    }
}

/**
 * The SQLCipher passphrase: 32 random bytes, generated once, stored only as a blob wrapped by [wrapper] in [file]
 * (put it in `noBackupFilesDir`, so it is neither backed up nor transferred to a new phone, where the Keystore key would
 * not exist anyway).
 */
class PassphraseVault(
    private val file: File,
    private val wrapper: SecretWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    /** The blob exists but this device's key cannot open it (Keystore key lost, file damaged). */
    class UnreadableException(cause: Throwable?) : IOException("The stored database key cannot be read", cause)

    /** True if a passphrase was generated before. */
    fun exists(): Boolean = file.isFile

    /**
     * Returns the passphrase, creating it on first use.
     * @throws UnreadableException see above; the database it protected is lost, call [reset] and start over.
     */
    @Synchronized
    fun getOrCreate(): ByteArray {
        if (file.isFile) {
            val blob = try {
                file.readBytes()
            } catch (e: IOException) {
                throw UnreadableException(e)
            }
            return try {
                wrapper.unwrap(blob).also { if (it.size != LENGTH) throw GeneralSecurityException("unexpected length") }
            } catch (e: GeneralSecurityException) {
                throw UnreadableException(e)
            } catch (e: RuntimeException) {
                // e.g. KeyStoreException / ProviderException surface as runtime exceptions on some devices
                throw UnreadableException(e)
            }
        }
        val fresh = ByteArray(LENGTH).also { random.nextBytes(it) }
        val blob = wrapper.wrap(fresh)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not store the database key")
        }
        return fresh
    }

    /** Forgets the passphrase (after the database it protected was deleted). */
    @Synchronized
    fun reset() {
        file.delete()
    }

    companion object {
        const val LENGTH = 32
        const val FILE_NAME = "db_key.bin"

        fun forContext(context: Context): PassphraseVault =
            PassphraseVault(File(context.noBackupFilesDir, FILE_NAME), KeystoreSecretWrapper(context))
    }
}
