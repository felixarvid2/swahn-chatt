package se.swahnchat.core.crypto

import se.swahnchat.core.model.DeviceKeyBundle
import se.swahnchat.core.model.OneTimePreKeyDto
import se.swahnchat.core.model.PqPreKeyDto
import se.swahnchat.core.model.SignedPreKeyDto
import se.swahnchat.core.model.UploadKeysRequest
import java.util.UUID

/** A fully wired device: storage, store, key manager and crypto, as the engine uses them. */
class TestDevice(
    val userId: String,
    val number: Int,
    val deviceId: String = UUID.randomUUID().toString(),
    val storage: InMemoryProtocolStorage = InMemoryProtocolStorage(),
    clock: () -> Long = System::currentTimeMillis,
) {
    val store = PersistentSignalProtocolStore(storage)
    val keys = KeyManager(store, storage, clock)
    val crypto = MessageCrypto(store)
    val recipient get() = Recipient(userId, deviceId)

    /** Pins [other]'s identity as the directory layer does after reading the device list. */
    fun trust(other: TestDevice, approved: Boolean = true) {
        store.pinDevice(other.userId, other.deviceId, other.keys.identityPublicKey(), approved)
    }

    /** Registers with [server] (initial upload) and returns the request. */
    fun register(server: FakeKeyServer): UploadKeysRequest = keys.buildInitialUpload().also { server.put(this, it) }

    /** Fresh MessageCrypto/Store on the same storage: a process restart. */
    fun restarted(): TestDevice = TestDevice(userId, number, deviceId, storage)
}

/** Just enough of the server's key endpoints: stores uploads and hands out bundles, consuming one-time keys. */
class FakeKeyServer {
    private class Entry(
        var registrationId: Int, var identityKey: String, var signed: SignedPreKeyDto, var lastResort: PqPreKeyDto,
        val ec: ArrayDeque<OneTimePreKeyDto>, val pq: ArrayDeque<PqPreKeyDto>, val number: Int,
    )

    private val entries = HashMap<String, Entry>() // by deviceId

    fun put(d: TestDevice, r: UploadKeysRequest) {
        val e = entries[d.deviceId]
        if (e == null) {
            entries[d.deviceId] = Entry(
                r.registrationId!!, r.identityKey!!, r.signedPrekey!!, r.pqLastResortPrekey!!,
                ArrayDeque(r.oneTimePrekeys.orEmpty()), ArrayDeque(r.oneTimePqPrekeys.orEmpty()), d.number,
            )
        } else {
            r.signedPrekey?.let { e.signed = it }
            r.pqLastResortPrekey?.let { e.lastResort = it }
            e.ec.addAll(r.oneTimePrekeys.orEmpty())
            e.pq.addAll(r.oneTimePqPrekeys.orEmpty())
        }
    }

    fun status(d: TestDevice) = entries.getValue(d.deviceId).let {
        se.swahnchat.core.model.KeyStatus(it.ec.size, it.pq.size, it.signed.keyId, it.lastResort.keyId)
    }

    /** Like GET /keys/{user}?device_id=: consumes one one-time EC and one one-time PQ key. */
    fun bundle(d: TestDevice, withOneTimeEc: Boolean = true): DeviceKeyBundle {
        val e = entries.getValue(d.deviceId)
        val ec = e.ec.removeFirstOrNull().takeIf { withOneTimeEc }
        val pq = e.pq.removeFirstOrNull() ?: e.lastResort
        return DeviceKeyBundle(d.deviceId, e.number, e.registrationId, e.identityKey, e.signed, pq, ec)
    }

    fun drainOneTimeKeys(d: TestDevice) {
        entries.getValue(d.deviceId).apply { ec.clear(); pq.clear() }
    }
}

/** Alice (device 1) and Bob (device 1) with sessions established in the direction [aliceStarts]. */
fun connect(from: TestDevice, to: TestDevice, server: FakeKeyServer, withOneTimeEc: Boolean = true) {
    from.trust(to)
    to.trust(from)
    from.crypto.processBundle(to.userId, to.deviceId, server.bundle(to, withOneTimeEc))
}

fun text(s: String) = s.toByteArray(Charsets.UTF_8)
