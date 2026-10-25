package se.swahnchat.core.crypto

/** The device's own identity: serialized `IdentityKeyPair` and the libsignal registration id. */
class LocalIdentity(val identityKeyPair: ByteArray, val registrationId: Int)

/**
 * A pinned remote device identity, keyed by the device's immutable server UUID ([deviceId]). [userId] is the account
 * the device belonged to when it was pinned, [identityKey] the serialized `IdentityKey` (33 bytes). [verified]: the
 * user compared safety numbers. [approved]: the device may be used; false for a device that appeared next to a
 * verified one and still waits for the user's explicit decision (nothing is sent to or accepted from it meanwhile).
 */
class StoredIdentity(
    val deviceId: String,
    val userId: String,
    val identityKey: ByteArray,
    val verified: Boolean,
    val approved: Boolean = true,
)

/**
 * Persistence primitives the Signal store needs, as plain byte arrays. The app implements this once on top of
 * its encrypted Room database; [InMemoryProtocolStorage] serves tests.
 *
 * Contract:
 * - All functions are **blocking** and must be thread-safe: libsignal calls the store synchronously from its
 *   native code. [MessageCrypto] additionally serialises all crypto operations of one device behind a lock.
 * - Sessions are addressed by `name` = `"<userId>/<deviceUuid>"` (see [DeviceAddress]); the server's device number is
 *   never used for trust or sessions. Pinned identities are keyed by the device UUID alone, so a server that moves a
 *   known device to another user or number cannot make it look new.
 * - Records are opaque serializations produced by libsignal (`SessionRecord.serialize()` etc.). Store them
 *   as BLOBs, never parse them.
 * - Writes must be durable when the call returns (a ratchet step that is lost cannot be recovered).
 */
interface ProtocolStorage {
    // --- local identity ------------------------------------------------------------------------------------------
    fun getLocalIdentity(): LocalIdentity?
    fun setLocalIdentity(identity: LocalIdentity)

    // --- one-time EC prekeys -------------------------------------------------------------------------------------
    fun loadPreKey(id: Int): ByteArray?
    fun storePreKey(id: Int, record: ByteArray)
    fun removePreKey(id: Int)

    /** Number of one-time EC prekeys still held locally (for diagnostics / top-up decisions). */
    fun countPreKeys(): Int

    // --- signed prekeys ------------------------------------------------------------------------------------------
    fun loadSignedPreKey(id: Int): ByteArray?
    fun loadSignedPreKeys(): List<ByteArray>
    fun storeSignedPreKey(id: Int, record: ByteArray)
    fun removeSignedPreKey(id: Int)

    // --- Kyber (post-quantum) prekeys ----------------------------------------------------------------------------
    fun loadKyberPreKey(id: Int): ByteArray?

    /** All Kyber records with their last-resort flag. */
    fun loadKyberPreKeys(): List<StoredKyberPreKey>
    fun storeKyberPreKey(id: Int, record: ByteArray, lastResort: Boolean)
    fun removeKyberPreKey(id: Int)

    /**
     * Records that a message used last-resort Kyber key [kyberPreKeyId] with signed prekey [signedPreKeyId] and
     * sender base key [baseKey]. Returns false if exactly this triple was recorded before (a replay), true if new.
     * Must be atomic (insert-if-absent).
     */
    fun markLastResortUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ByteArray): Boolean

    // --- sessions ------------------------------------------------------------------------------------------------
    fun loadSession(name: String): ByteArray?
    fun storeSession(name: String, record: ByteArray)
    fun deleteSession(name: String)

    /** Deletes every session whose name starts with [prefix] (exact prefix match, no wildcards). */
    fun deleteSessionsWithPrefix(prefix: String)

    // --- pinned remote identities --------------------------------------------------------------------------------
    fun getIdentity(deviceId: String): StoredIdentity?
    fun putIdentity(identity: StoredIdentity)

    /** All pins whose owner is [userId]. */
    fun identitiesOf(userId: String): List<StoredIdentity>

    // --- id allocation -------------------------------------------------------------------------------------------
    /**
     * Atomically reserves [count] consecutive ids for [kind] and returns the first one. Ids start at 1, never repeat
     * and wrap to 1 after 0xFFFFFF (the libsignal limit). Persist the counter so that ids of consumed keys are not
     * reused.
     */
    fun reserveKeyIds(kind: KeyIdKind, count: Int): Int

    // --- lifecycle -----------------------------------------------------------------------------------------------
    /** Deletes everything (identity, keys, sessions, pins, counters). Called on logout: a new login is a new device. */
    fun clearAll()
}

class StoredKyberPreKey(val id: Int, val record: ByteArray, val lastResort: Boolean)

/** Id spaces: signed prekeys, one-time EC prekeys, and Kyber prekeys (one-time and last-resort share one space). */
enum class KeyIdKind { SIGNED_PRE_KEY, PRE_KEY, KYBER_PRE_KEY }
