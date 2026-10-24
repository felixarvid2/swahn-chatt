package se.familychat.core.crypto

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
 * libsignal's [SignalProtocolStore] on top of [ProtocolStorage].
 *
 * Trust model (TOFU, trust on first use):
 * - The first identity key seen for an address `(userId, deviceNumber)` is pinned.
 * - A different key for a known address is **not trusted**: [isTrustedIdentity] returns false, so libsignal refuses to
 *   build a session or decrypt (`UntrustedIdentityException`) and [MessageCrypto] reports it as
 *   [UntrustedIdentityException]. Nothing is replaced automatically.
 * - After the user confirmed the change (compared safety numbers), call [acceptIdentity] to pin the new key and drop
 *   the old session. [setVerified] marks a key as verified by the user (safety numbers compared).
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

    @Synchronized
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val known = storage.getIdentity(address.name, address.deviceId)
        val bytes = identityKey.serialize()
        return when {
            known == null -> {
                storage.putIdentity(address.name, address.deviceId, StoredIdentity(bytes, verified = false))
                IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            }
            known.identityKey.contentEquals(bytes) -> IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            else -> {
                // libsignal only gets here after isTrustedIdentity() said yes, which never happens for a changed key.
                // Keep the contract anyway: replace, unverified, and drop the session built on the old key.
                storage.putIdentity(address.name, address.deviceId, StoredIdentity(bytes, verified = false))
                storage.deleteSession(address.name, address.deviceId)
                IdentityKeyStore.IdentityChange.REPLACED_EXISTING
            }
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val known = storage.getIdentity(address.name, address.deviceId) ?: return true // TOFU
        return known.identityKey.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        storage.getIdentity(address.name, address.deviceId)?.let { IdentityKey(it.identityKey) }

    /** The pinned identity of a device, or null if none has been seen. */
    fun pinnedIdentity(userId: String, deviceNumber: Int): StoredIdentity? = storage.getIdentity(userId, deviceNumber)

    /**
     * Pins [newIdentityKey] (a serialized `IdentityKey`) for the device, replacing the old one, and deletes the session
     * with it: the next message from that device must come as a new prekey message, and the next send needs a fresh
     * bundle. Call only after the user explicitly accepted the change.
     */
    @Synchronized
    fun acceptIdentity(userId: String, deviceNumber: Int, newIdentityKey: ByteArray) {
        IdentityKey(newIdentityKey) // validates
        storage.putIdentity(userId, deviceNumber, StoredIdentity(newIdentityKey.copyOf(), verified = false))
        storage.deleteSession(userId, deviceNumber)
    }

    /** Marks the currently pinned key of the device as verified (or not). No-op if nothing is pinned. */
    @Synchronized
    fun setVerified(userId: String, deviceNumber: Int, verified: Boolean) {
        val known = storage.getIdentity(userId, deviceNumber) ?: return
        storage.putIdentity(userId, deviceNumber, StoredIdentity(known.identityKey, verified))
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
        storage.loadSession(address.name, address.deviceId)?.let { SessionRecord(it) } ?: SessionRecord()

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> =
        addresses.mapTo(ArrayList()) { a ->
            storage.loadSession(a.name, a.deviceId)?.let { SessionRecord(it) }
                ?: throw NoSessionException(a, "No session for $a")
        }

    override fun getSubDeviceSessions(name: String): MutableList<Int> =
        storage.sessionDeviceNumbers(name).toMutableList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        storage.storeSession(address.name, address.deviceId, record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        val bytes = storage.loadSession(address.name, address.deviceId) ?: return false
        return try {
            val r = SessionRecord(bytes)
            r.hasSenderChain()
        } catch (e: Exception) {
            false
        }
    }

    override fun deleteSession(address: SignalProtocolAddress) = storage.deleteSession(address.name, address.deviceId)
    override fun deleteAllSessions(name: String) = storage.deleteAllSessions(name)

    // --- sender keys (group sessions; unused, FamilyChat fans out pairwise) -----------------------------------------

    @Synchronized
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        senderKeys[sender to distributionId] = record
    }

    @Synchronized
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        senderKeys[sender to distributionId]
}
