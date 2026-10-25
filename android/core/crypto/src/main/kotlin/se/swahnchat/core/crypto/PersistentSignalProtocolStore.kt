package se.swahnchat.core.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * How a remote device is addressed in libsignal: `SignalProtocolAddress("<userId>/<deviceUuid>", 1)`. The immutable
 * device UUID identifies the device; the server's device number is informational only and never reaches libsignal.
 * libsignal requires a device id in 1..127, so the constant 1 stands in.
 */
object DeviceAddress {
    const val DEVICE_ID = 1

    fun name(userId: String, deviceId: String): String = "$userId/$deviceId"
    fun of(userId: String, deviceId: String) = SignalProtocolAddress(name(userId, deviceId), DEVICE_ID)

    /** `(userId, deviceId)` of an address name, or null if it is not one of ours. */
    fun parse(name: String): Pair<String, String>? {
        val i = name.indexOf('/')
        if (i <= 0 || i == name.length - 1) return null
        return name.substring(0, i) to name.substring(i + 1)
    }

    /** The serialized `IdentityKey` bytes of an API `identity_key` (base64), or null if it is not a valid key. */
    fun decodeIdentityKey(base64: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(base64).also { IdentityKey(it) }
    } catch (e: Exception) {
        null
    }

    /** Ids from the server end up in [name]; a `/` in the user id would let two devices share a name. */
    fun isSafe(userId: String, deviceId: String): Boolean =
        userId.isNotEmpty() && deviceId.isNotEmpty() && '/' !in userId && '/' !in deviceId
}

/**
 * libsignal's [SignalProtocolStore] on top of [ProtocolStorage].
 *
 * Trust model (pin per device UUID, no libsignal-level TOFU):
 * - Identities are pinned by the directory layer ([pinDevice], from the device list) and only there. A device that was
 *   never pinned is **not trusted**, so a session cannot be built with, and a message cannot be accepted from, a
 *   device nobody listed.
 * - The pin is keyed by the device UUID and remembers the owning user. [isTrustedIdentity] is true only if the pin
 *   exists, belongs to the user named in the address, holds exactly this key and is approved. Anything else (another
 *   key, another owner, a device waiting for approval) makes libsignal refuse (`UntrustedIdentityException`), which
 *   [MessageCrypto] reports as [UntrustedIdentityException]. Nothing is replaced automatically.
 * - After the user confirmed the change (compared safety numbers), call [acceptIdentity] to pin the new key / owner,
 *   approve the device and drop the old session. [setVerified] marks a key as verified by the user.
 * - Pins are never deleted while the account is signed in (removing and re-adding a device must not look new).
 *
 * One-time EC and Kyber prekeys are deleted when libsignal marks them used; the **last-resort** Kyber key is never
 * deleted, but each use is recorded so that an exact replay is rejected.
 */
class PersistentSignalProtocolStore(private val storage: ProtocolStorage) : SignalProtocolStore {

    private val senderKeys = HashMap<Pair<SignalProtocolAddress, UUID>, SenderKeyRecord>()

    // --- identity --------------------------------------------------------------------------------------------------

    /** True once [ProtocolStorage.setLocalIdentity] was called. */
    fun hasLocalIdentity(): Boolean = storage.getLocalIdentity() != null

    override fun getIdentityKeyPair(): IdentityKeyPair =
        IdentityKeyPair(
            (storage.getLocalIdentity() ?: throw IllegalStateException("No local identity yet")).identityKeyPair,
        )

    override fun getLocalRegistrationId(): Int =
        (storage.getLocalIdentity() ?: throw IllegalStateException("No local identity yet")).registrationId

    /**
     * libsignal calls this after it accepted a key as trusted. Pins are only created by [pinDevice] and only changed by
     * [acceptIdentity], so this never writes.
     */
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val pin = pinFor(address)
        return if (pin == null || pin.identityKey.contentEquals(identityKey.serialize())) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val (userId, _) = DeviceAddress.parse(address.name) ?: return false
        val pin = pinFor(address) ?: return false
        return pin.approved && pin.userId == userId && pin.identityKey.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val (userId, _) = DeviceAddress.parse(address.name) ?: return null
        return pinFor(address)?.takeIf { it.userId == userId }?.let { IdentityKey(it.identityKey) }
    }

    private fun pinFor(address: SignalProtocolAddress): StoredIdentity? =
        DeviceAddress.parse(address.name)?.let { storage.getIdentity(it.second) }

    /** The pinned identity of a device (by UUID), or null if none has been pinned. */
    fun pinnedIdentity(deviceId: String): StoredIdentity? = storage.getIdentity(deviceId)

    /** All pins owned by [userId]. */
    fun pinsOf(userId: String): List<StoredIdentity> = storage.identitiesOf(userId)

    /** True if the user marked at least one pinned device of [userId] as verified. */
    fun hasVerifiedDevice(userId: String): Boolean = storage.identitiesOf(userId).any { it.verified }

    /**
     * Pins [identityKey] (a serialized `IdentityKey`) for a device seen for the first time. Does nothing and returns
     * false if the device is already pinned (a later, different key is an identity change, not a new pin). With
     * [approved] false the device stays untrusted until [acceptIdentity].
     */
    @Synchronized
    fun pinDevice(userId: String, deviceId: String, identityKey: ByteArray, approved: Boolean): Boolean {
        if (storage.getIdentity(deviceId) != null) return false
        IdentityKey(identityKey) // validates
        storage.putIdentity(StoredIdentity(deviceId, userId, identityKey.copyOf(), verified = false, approved = approved))
        return true
    }

    /**
     * Pins [newIdentityKey] for the device (owned by [userId]), replacing the old pin, approves it and deletes the
     * session with it: the next message from that device must come as a new prekey message, and the next send needs a
     * fresh bundle. Call only after the user explicitly accepted the change.
     */
    @Synchronized
    fun acceptIdentity(userId: String, deviceId: String, newIdentityKey: ByteArray) {
        IdentityKey(newIdentityKey) // validates
        val old = storage.getIdentity(deviceId)
        val same = old != null && old.userId == userId && old.identityKey.contentEquals(newIdentityKey)
        storage.putIdentity(
            StoredIdentity(deviceId, userId, newIdentityKey.copyOf(), verified = same && old!!.verified, approved = true),
        )
        // The session (if any) was built on the old key or owner.
        old?.let { storage.deleteSession(DeviceAddress.name(it.userId, deviceId)) }
        storage.deleteSession(DeviceAddress.name(userId, deviceId))
    }

    /** Marks the currently pinned key of the device as verified (or not). No-op if nothing is pinned. */
    @Synchronized
    fun setVerified(deviceId: String, verified: Boolean) {
        val known = storage.getIdentity(deviceId) ?: return
        storage.putIdentity(StoredIdentity(known.deviceId, known.userId, known.identityKey, verified, known.approved))
    }

    // --- one-time EC prekeys ---------------------------------------------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        PreKeyRecord(storage.loadPreKey(preKeyId) ?: throw InvalidKeyIdException("No prekey $preKeyId"))

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = storage.storePreKey(preKeyId, record.serialize())
    override fun containsPreKey(preKeyId: Int): Boolean = storage.loadPreKey(preKeyId) != null
    override fun removePreKey(preKeyId: Int) = storage.removePreKey(preKeyId)

    // --- signed prekeys --------------------------------------------------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        SignedPreKeyRecord(storage.loadSignedPreKey(signedPreKeyId) ?: throw InvalidKeyIdException("No signed prekey $signedPreKeyId"))

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> =
        storage.loadSignedPreKeys().mapTo(ArrayList()) { SignedPreKeyRecord(it) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        storage.storeSignedPreKey(signedPreKeyId, record.serialize())

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = storage.loadSignedPreKey(signedPreKeyId) != null
    override fun removeSignedPreKey(signedPreKeyId: Int) = storage.removeSignedPreKey(signedPreKeyId)

    // --- Kyber prekeys ---------------------------------------------------------------------------------------------

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        KyberPreKeyRecord(storage.loadKyberPreKey(kyberPreKeyId) ?: throw InvalidKeyIdException("No Kyber prekey $kyberPreKeyId"))

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> =
        storage.loadKyberPreKeys().mapTo(ArrayList()) { KyberPreKeyRecord(it.record) }

    /** Stores a one-time Kyber key. Use [storeLastResortKyberPreKey] for the last-resort key. */
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        storage.storeKyberPreKey(kyberPreKeyId, record.serialize(), lastResort = false)

    fun storeLastResortKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        storage.storeKyberPreKey(kyberPreKeyId, record.serialize(), lastResort = true)

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = storage.loadKyberPreKey(kyberPreKeyId) != null

    @Synchronized
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val stored = storage.loadKyberPreKeys().firstOrNull { it.id == kyberPreKeyId }
            ?: throw InvalidKeyIdException("No Kyber prekey $kyberPreKeyId")
        if (stored.lastResort) {
            if (!storage.markLastResortUsed(kyberPreKeyId, signedPreKeyId, baseKey.serialize())) {
                throw ReusedBaseKeyException("Last-resort Kyber prekey reused with the same base key")
            }
        } else {
            storage.removeKyberPreKey(kyberPreKeyId)
        }
    }

    // --- sessions --------------------------------------------------------------------------------------------------

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        storage.loadSession(address.name)?.let { SessionRecord(it) } ?: SessionRecord()

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> =
        addresses.mapTo(ArrayList()) { a ->
            storage.loadSession(a.name)?.let { SessionRecord(it) }
                ?: throw NoSessionException(a, "No session for $a")
        }

    /** Device ids are encoded in the address name, so there are no "sub device" sessions. */
    override fun getSubDeviceSessions(name: String): MutableList<Int> = ArrayList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        storage.storeSession(address.name, record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        val bytes = storage.loadSession(address.name) ?: return false
        return try {
            val r = SessionRecord(bytes)
            r.hasSenderChain()
        } catch (e: Exception) {
            false
        }
    }

    /** True if any session record (even an archived one) is stored for the device. */
    fun hasSessionRecord(userId: String, deviceId: String): Boolean =
        storage.loadSession(DeviceAddress.name(userId, deviceId)) != null

    override fun deleteSession(address: SignalProtocolAddress) = storage.deleteSession(address.name)

    /** [name] is a user id: deletes the sessions with all that user's devices. */
    override fun deleteAllSessions(name: String) = storage.deleteSessionsWithPrefix("$name/")

    // --- sender keys (group sessions; unused, Swahn Chat fans out pairwise) -----------------------------------------

    @Synchronized
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        senderKeys[sender to distributionId] = record
    }

    @Synchronized
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        senderKeys[sender to distributionId]
}
