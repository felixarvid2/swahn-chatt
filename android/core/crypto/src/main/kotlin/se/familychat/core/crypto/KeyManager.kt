package se.familychat.core.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import se.familychat.core.model.KeyStatus
import se.familychat.core.model.OneTimePreKeyDto
import se.familychat.core.model.PqPreKeyDto
import se.familychat.core.model.SignedPreKeyDto
import se.familychat.core.model.UploadKeysRequest
import java.security.SecureRandom
import java.util.Base64

/**
 * Generates and maintains this device's key material and builds the `PUT /keys` requests.
 *
 * Typical use by the engine:
 * ```
 * if (!keyManager.hasIdentity()) { keyManager.createIdentity(); api.uploadKeys(keyManager.buildInitialUpload()) }
 * else keyManager.buildMaintenanceUpload(api.keyStatus())?.let { api.uploadKeys(it) }
 * ```
 *
 * Private keys never leave [ProtocolStorage]; only public keys and signatures are put into requests.
 * [clock] returns epoch milliseconds (inject for tests).
 */
class KeyManager(
    private val store: PersistentSignalProtocolStore,
    private val storage: ProtocolStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    // --- identity ----------------------------------------------------------------------------------------------------

    fun hasIdentity(): Boolean = storage.getLocalIdentity() != null

    /** Generates the identity key pair and a registration id (1..16380) and stores them. Idempotent. */
    fun createIdentity() {
        if (hasIdentity()) return
        val pair = IdentityKeyPair.generate()
        storage.setLocalIdentity(LocalIdentity(pair.serialize(), random.nextInt(MAX_REGISTRATION_ID) + 1))
    }

    /** Serialized public `IdentityKey` (33 bytes) of this device. */
    fun identityPublicKey(): ByteArray = store.identityKeyPair.publicKey.serialize()

    fun registrationId(): Int = store.localRegistrationId

    // --- uploads -----------------------------------------------------------------------------------------------------

    /**
     * The first upload: identity, registration id, a new signed prekey, a new Kyber last-resort key and
     * [BATCH_SIZE] one-time keys of each kind. Creates the identity if needed.
     */
    fun buildInitialUpload(): UploadKeysRequest {
        createIdentity()
        return UploadKeysRequest(
            registrationId = registrationId(),
            identityKey = b64(identityPublicKey()),
            signedPrekey = newSignedPreKey(),
            pqLastResortPrekey = newLastResortKyberKey(),
            oneTimePrekeys = newOneTimePreKeys(BATCH_SIZE),
            oneTimePqPrekeys = newOneTimeKyberKeys(BATCH_SIZE),
        )
    }

    /**
     * Re-sends the full current key set (identity, current signed prekey, current last-resort key) plus fresh one-time
     * keys; for when the server reports no signed prekey for this device (e.g. a restored server database).
     */
    fun buildFullReupload(): UploadKeysRequest = UploadKeysRequest(
        registrationId = registrationId(),
        identityKey = b64(identityPublicKey()),
        signedPrekey = currentSignedPreKeyDto() ?: newSignedPreKey(),
        pqLastResortPrekey = currentLastResortDto() ?: newLastResortKyberKey(),
        oneTimePrekeys = newOneTimePreKeys(BATCH_SIZE),
        oneTimePqPrekeys = newOneTimeKyberKeys(BATCH_SIZE),
    )

    /**
     * What to upload given the server's [status], or null if nothing is needed:
     * - one-time EC / Kyber keys: a new batch of [BATCH_SIZE] when the server holds fewer than [LOW_WATERMARK],
     * - signed prekey and Kyber last-resort key: rotated when the current one is older than [ROTATION_MS] (30 days).
     *   Old ones stay in storage for [RETENTION_MS] so that messages already in flight still decrypt.
     */
    fun buildMaintenanceUpload(status: KeyStatus): UploadKeysRequest? {
        if (status.signedPrekeyId == null || status.pqLastResortPrekeyId == null) return buildFullReupload()
        val now = clock()
        var signed: SignedPreKeyDto? = null
        var lastResort: PqPreKeyDto? = null
        val currentSigned = currentSignedPreKey()
        if (currentSigned == null || now - currentSigned.timestamp > ROTATION_MS) {
            signed = newSignedPreKey()
            lastResort = newLastResortKyberKey()
            pruneOldKeys(now)
        }
        val ec = if (status.oneTimePrekeys < LOW_WATERMARK) newOneTimePreKeys(BATCH_SIZE) else null
        val pq = if (status.oneTimePqPrekeys < LOW_WATERMARK) newOneTimeKyberKeys(BATCH_SIZE) else null
        if (signed == null && ec == null && pq == null) return null
        return UploadKeysRequest(
            signedPrekey = signed, pqLastResortPrekey = lastResort, oneTimePrekeys = ec, oneTimePqPrekeys = pq,
        )
    }

    // --- key generation ----------------------------------------------------------------------------------------------

    private fun identityPair(): IdentityKeyPair = store.identityKeyPair

    private fun newSignedPreKey(): SignedPreKeyDto {
        val id = storage.reserveKeyIds(KeyIdKind.SIGNED_PRE_KEY, 1)
        val pair = ECKeyPair.generate()
        val sig = identityPair().privateKey.calculateSignature(pair.publicKey.serialize())
        val record = SignedPreKeyRecord(id, clock(), pair, sig)
        store.storeSignedPreKey(id, record)
        return SignedPreKeyDto(id, b64(pair.publicKey.serialize()), b64(sig))
    }

    private fun newLastResortKyberKey(): PqPreKeyDto = newKyberKey(lastResort = true)

    private fun newKyberKey(lastResort: Boolean): PqPreKeyDto {
        val id = storage.reserveKeyIds(KeyIdKind.KYBER_PRE_KEY, 1)
        return storeKyber(id, lastResort)
    }

    private fun storeKyber(id: Int, lastResort: Boolean): PqPreKeyDto {
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val pub = pair.publicKey.serialize()
        val sig = identityPair().privateKey.calculateSignature(pub)
        val record = KyberPreKeyRecord(id, clock(), pair, sig)
        if (lastResort) store.storeLastResortKyberPreKey(id, record) else store.storeKyberPreKey(id, record)
        return PqPreKeyDto(id, b64(pub), b64(sig))
    }

    private fun newOneTimePreKeys(count: Int): List<OneTimePreKeyDto> {
        val first = storage.reserveKeyIds(KeyIdKind.PRE_KEY, count)
        return (0 until count).map { i ->
            val pair = ECKeyPair.generate()
            store.storePreKey(first + i, PreKeyRecord(first + i, pair))
            OneTimePreKeyDto(first + i, b64(pair.publicKey.serialize()))
        }
    }

    private fun newOneTimeKyberKeys(count: Int): List<PqPreKeyDto> {
        val first = storage.reserveKeyIds(KeyIdKind.KYBER_PRE_KEY, count)
        return (0 until count).map { i -> storeKyber(first + i, lastResort = false) }
    }

    // --- current keys ------------------------------------------------------------------------------------------------

    private fun currentSignedPreKey(): SignedPreKeyRecord? =
        store.loadSignedPreKeys().maxByOrNull { it.timestamp }

    private fun currentSignedPreKeyDto(): SignedPreKeyDto? = currentSignedPreKey()?.let {
        SignedPreKeyDto(it.id, b64(it.keyPair.publicKey.serialize()), b64(it.signature))
    }

    private fun currentLastResort(): KyberPreKeyRecord? =
        storage.loadKyberPreKeys().filter { it.lastResort }.map { KyberPreKeyRecord(it.record) }.maxByOrNull { it.timestamp }

    private fun currentLastResortDto(): PqPreKeyDto? = currentLastResort()?.let {
        PqPreKeyDto(it.id, b64(it.keyPair.publicKey.serialize()), b64(it.signature))
    }

    /** Drops signed prekeys and last-resort Kyber keys that were superseded more than [RETENTION_MS] ago. */
    private fun pruneOldKeys(now: Long) {
        val curSigned = currentSignedPreKey()?.id
        store.loadSignedPreKeys().filter { it.id != curSigned && now - it.timestamp > ROTATION_MS + RETENTION_MS }
            .forEach { store.removeSignedPreKey(it.id) }
        val curKyber = currentLastResort()?.id
        storage.loadKyberPreKeys().filter { it.lastResort && it.id != curKyber }
            .map { KyberPreKeyRecord(it.record) }
            .filter { now - it.timestamp > ROTATION_MS + RETENTION_MS }
            .forEach { storage.removeKyberPreKey(it.id) }
    }

    companion object {
        const val BATCH_SIZE = 100
        const val LOW_WATERMARK = 20
        const val ROTATION_MS = 30L * 24 * 3600 * 1000
        const val RETENTION_MS = 30L * 24 * 3600 * 1000
        private const val MAX_REGISTRATION_ID = 16380

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}

/** Convenience: serialized identity key bytes as libsignal object. */
fun ByteArray.toIdentityKey(): IdentityKey = IdentityKey(this)
