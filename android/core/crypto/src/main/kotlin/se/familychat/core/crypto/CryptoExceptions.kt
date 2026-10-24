package se.familychat.core.crypto

/** Base class of everything [MessageCrypto] and friends throw for protocol-level problems. */
sealed class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The ciphertext (or the content inside it) is corrupt, truncated, forged, of the wrong type, references an unknown
 * one-time key, or carries bad padding/JSON. Session state is untouched; show "could not decrypt".
 */
class InvalidCiphertextException(message: String, cause: Throwable? = null) : CryptoException(message, cause)

/** libsignal has already seen this message (replay or redelivery). Safe to ignore if the message is stored. */
class DuplicateMessageException(message: String, cause: Throwable? = null) : CryptoException(message, cause)

/**
 * The remote device's identity key differs from the one pinned on first use (or a bundle/prekey message arrived
 * with another key). Nothing was decrypted or sent. [newIdentityKey] is the key that was presented (serialized
 * `IdentityKey`), if known; pass it to [PersistentSignalProtocolStore.acceptIdentity] after the user confirmed.
 */
class UntrustedIdentityException(
    val userId: String,
    val deviceNumber: Int,
    val newIdentityKey: ByteArray?,
    cause: Throwable? = null,
) : CryptoException("Identity key of $userId.$deviceNumber changed", cause)

/** No session exists for the device (encrypting before a bundle was processed, or decrypting a `whisper` without one). */
class NoSessionException(val userId: String, val deviceNumber: Int, cause: Throwable? = null) :
    CryptoException("No session with $userId.$deviceNumber", cause)

/** A prekey bundle from the server did not verify (bad signature or malformed key). */
class InvalidBundleException(message: String, cause: Throwable? = null) : CryptoException(message, cause)

/** Attachment decryption failed: wrong key, tampered or truncated ciphertext. */
class AttachmentCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
