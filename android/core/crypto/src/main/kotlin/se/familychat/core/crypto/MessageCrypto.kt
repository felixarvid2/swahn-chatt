package se.familychat.core.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import se.familychat.core.model.Content
import se.familychat.core.model.ContentCodec
import se.familychat.core.model.DeviceKeyBundle
import se.familychat.core.model.Envelope
import se.familychat.core.model.EnvelopeType
import java.util.Base64
import org.signal.libsignal.protocol.DuplicateMessageException as SignalDuplicateMessageException
import org.signal.libsignal.protocol.NoSessionException as SignalNoSessionException
import org.signal.libsignal.protocol.UntrustedIdentityException as SignalUntrustedIdentityException

/** A device we encrypt for: [userId] + the device's UUID [deviceId] address the Signal session and the envelope. */
data class Recipient(val userId: String, val deviceId: String)

/** Ciphertext of one message for one device. [type] is [EnvelopeType.PREKEY] or [EnvelopeType.WHISPER]. */
class EncryptedMessage(val type: String, val body: ByteArray)

/**
 * Session setup, encryption and decryption on top of [PersistentSignalProtocolStore].
 *
 * Thread-safety: every operation takes one lock, so concurrent calls (a sync decrypting while the outbox encrypts)
 * never interleave ratchet updates. Calls are CPU-bound and block on [ProtocolStorage]; call them off the main thread.
 *
 * Failure semantics: libsignal only stores session changes after a successful operation. A failed [decrypt] therefore
 * leaves the session exactly as it was, and a later valid message still decrypts. Errors are reported as
 * [CryptoException] subtypes, never as raw libsignal exceptions.
 */
class MessageCrypto(private val store: PersistentSignalProtocolStore) {
    private val lock = Any()

    /**
     * Runs [block] holding the lock all other operations of this class take (re-entrant for the same thread).
     *
     * Lock order: a caller that also needs a database transaction around crypto calls (see
     * `LocalStore.blockingTransaction`) must take **this lock first and the transaction second**. The operations above
     * hold the lock while they write to [ProtocolStorage]; entering a transaction first and the lock second could
     * deadlock against them.
     */
    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }

    private fun address(userId: String, deviceId: String): SignalProtocolAddress {
        require(DeviceAddress.isSafe(userId, deviceId)) { "Unusable user or device id" }
        return DeviceAddress.of(userId, deviceId)
    }

    /** True if a usable session with the device exists. */
    fun hasSession(userId: String, deviceId: String): Boolean = synchronized(lock) {
        store.containsSession(address(userId, deviceId))
    }

    fun deleteSession(userId: String, deviceId: String) = synchronized(lock) {
        store.deleteSession(address(userId, deviceId))
    }

    fun deleteAllSessions(userId: String) = synchronized(lock) { store.deleteAllSessions(userId) }

    /**
     * Builds a session with device [deviceId] of [userId] from a server prekey bundle (PQXDH). libsignal verifies the
     * signed prekey and Kyber prekey signatures against the bundle's identity key, and the identity key must be the one
     * pinned for the device ([PersistentSignalProtocolStore.pinDevice]): the bundle never creates a pin.
     *
     * @throws UntrustedIdentityException the device is not pinned, pinned to another key or user, or not yet approved
     * @throws InvalidBundleException the bundle is for another device, or a signature / key encoding is invalid
     */
    fun processBundle(userId: String, deviceId: String, bundle: DeviceKeyBundle) = synchronized(lock) {
        if (bundle.deviceId != deviceId) {
            throw InvalidBundleException("Bundle is for device ${bundle.deviceId}, not $deviceId")
        }
        val addr = address(userId, deviceId)
        val identity = try {
            IdentityKey(b64d(bundle.identityKey))
        } catch (e: Exception) {
            throw InvalidBundleException("Bad identity key for $userId/$deviceId", e)
        }
        val signal = try {
            val oneTime = bundle.oneTimePrekey
            PreKeyBundle(
                bundle.registrationId,
                DeviceAddress.DEVICE_ID,
                oneTime?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
                oneTime?.let { ECPublicKey(b64d(it.publicKey)) },
                bundle.signedPrekey.keyId,
                ECPublicKey(b64d(bundle.signedPrekey.publicKey)),
                b64d(bundle.signedPrekey.signature),
                identity,
                bundle.pqPrekey.keyId,
                KEMPublicKey(b64d(bundle.pqPrekey.publicKey)),
                b64d(bundle.pqPrekey.signature),
            )
        } catch (e: Exception) {
            throw InvalidBundleException("Malformed bundle for $userId/$deviceId", e)
        }
        try {
            SessionBuilder(store, addr).process(signal)
        } catch (e: SignalUntrustedIdentityException) {
            throw UntrustedIdentityException(userId, deviceId, identity.serialize(), e)
        } catch (e: Exception) {
            throw InvalidBundleException("Bundle for $userId/$deviceId failed verification", e)
        }
    }

    /** Encrypts [plaintext] for one device. @throws NoSessionException @throws UntrustedIdentityException */
    fun encrypt(plaintext: ByteArray, userId: String, deviceId: String): EncryptedMessage = synchronized(lock) {
        val addr = address(userId, deviceId)
        val message: CiphertextMessage = try {
            SessionCipher(store, addr).encrypt(plaintext)
        } catch (e: SignalNoSessionException) {
            throw NoSessionException(userId, deviceId, e)
        } catch (e: SignalUntrustedIdentityException) {
            throw UntrustedIdentityException(userId, deviceId, e.untrustedIdentity?.serialize(), e)
        } catch (e: Exception) {
            throw InvalidCiphertextException("Encryption for $userId/$deviceId failed", e)
        }
        val type = when (message.type) {
            CiphertextMessage.PREKEY_TYPE -> EnvelopeType.PREKEY
            CiphertextMessage.WHISPER_TYPE -> EnvelopeType.WHISPER
            else -> throw InvalidCiphertextException("Unexpected ciphertext type ${message.type}")
        }
        EncryptedMessage(type, message.serialize())
    }

    /** Encrypts the padded [plaintext] separately for every recipient and returns API envelopes (base64 bodies). */
    fun encryptForDevices(plaintext: ByteArray, recipients: List<Recipient>): List<Envelope> =
        recipients.map { r ->
            val enc = encrypt(plaintext, r.userId, r.deviceId)
            Envelope(r.deviceId, enc.type, Base64.getEncoder().encodeToString(enc.body))
        }

    /** [ContentCodec.encode]s [content] (JSON + padding) and encrypts it for [recipients]. */
    fun encryptContent(content: Content, recipients: List<Recipient>): List<Envelope> =
        encryptForDevices(ContentCodec.encode(content), recipients)

    /**
     * Decrypts one envelope body from device [senderDeviceId] of [senderUserId] and returns the padded plaintext.
     * [envelopeType] is [EnvelopeType.PREKEY] or [EnvelopeType.WHISPER].
     *
     * @throws InvalidCiphertextException corrupt/unknown-type/unparsable ciphertext (session untouched)
     * @throws DuplicateMessageException already processed
     * @throws UntrustedIdentityException a prekey message presented a changed identity key
     * @throws NoSessionException a whisper message but no session
     */
    fun decrypt(senderUserId: String, senderDeviceId: String, envelopeType: String, body: ByteArray): ByteArray =
        synchronized(lock) {
            val addr = address(senderUserId, senderDeviceId)
            var presentedIdentity: ByteArray? = null
            try {
                val cipher = SessionCipher(store, addr)
                when (envelopeType) {
                    EnvelopeType.PREKEY -> {
                        val msg = PreKeySignalMessage(body)
                        presentedIdentity = msg.identityKey.serialize()
                        cipher.decrypt(msg)
                    }
                    EnvelopeType.WHISPER -> {
                        // libsignal reports a missing session as "decryption failed"; tell the two cases apart here.
                        if (!store.hasSessionRecord(senderUserId, senderDeviceId)) {
                            throw NoSessionException(senderUserId, senderDeviceId)
                        }
                        cipher.decrypt(SignalMessage(body))
                    }
                    else -> throw InvalidCiphertextException("Unknown envelope type '$envelopeType'")
                }
            } catch (e: CryptoException) {
                throw e
            } catch (e: SignalDuplicateMessageException) {
                throw DuplicateMessageException("Already processed message from $senderUserId/$senderDeviceId", e)
            } catch (e: ReusedBaseKeyException) {
                throw DuplicateMessageException("Replayed prekey message from $senderUserId/$senderDeviceId", e)
            } catch (e: SignalUntrustedIdentityException) {
                throw UntrustedIdentityException(
                    senderUserId, senderDeviceId, e.untrustedIdentity?.serialize() ?: presentedIdentity, e,
                )
            } catch (e: SignalNoSessionException) {
                throw NoSessionException(senderUserId, senderDeviceId, e)
            } catch (e: Exception) {
                throw InvalidCiphertextException("Could not decrypt message from $senderUserId/$senderDeviceId", e)
            }
        }

    /**
     * [decrypt]s a base64 envelope body and parses the padded [Content].
     *
     * @throws InvalidCiphertextException also for bad padding or JSON inside an otherwise valid ciphertext (the
     *   message was consumed by the ratchet; it cannot be retried)
     */
    fun decryptContent(senderUserId: String, senderDeviceId: String, envelopeType: String, bodyBase64: String): Content {
        val body = try {
            Base64.getDecoder().decode(bodyBase64)
        } catch (e: IllegalArgumentException) {
            throw InvalidCiphertextException("Envelope body is not valid base64", e)
        }
        val padded = decrypt(senderUserId, senderDeviceId, envelopeType, body)
        return try {
            ContentCodec.decode(padded)
        } catch (e: Exception) {
            throw InvalidCiphertextException("Decrypted content is malformed", e)
        }
    }

    private fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)
}
