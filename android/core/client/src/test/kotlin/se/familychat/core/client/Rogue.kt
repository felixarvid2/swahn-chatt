package se.familychat.core.client

import se.familychat.core.crypto.DeviceAddress
import se.familychat.core.crypto.InMemoryProtocolStorage
import se.familychat.core.crypto.KeyManager
import se.familychat.core.crypto.MessageCrypto
import se.familychat.core.crypto.PersistentSignalProtocolStore
import se.familychat.core.crypto.Recipient
import se.familychat.core.model.Content
import se.familychat.core.model.DeviceKeyBundle
import se.familychat.core.model.Envelope
import se.familychat.core.model.UploadKeysRequest
import java.util.UUID

/**
 * A device the attacker (a malicious server, or a device it controls) runs: real libsignal keys and a real session
 * state, so its key bundles carry valid signatures and its ciphertexts are genuine. A test failing on an invalid
 * bundle would prove nothing; these devices fail (or not) only because of the engine's trust decisions.
 */
class RogueDevice(val userId: String, val deviceId: String = UUID.randomUUID().toString()) {
    private val storage = InMemoryProtocolStorage()
    private val store = PersistentSignalProtocolStore(storage)
    private val keyManager = KeyManager(store, storage, System::currentTimeMillis)
    private val crypto = MessageCrypto(store)

    /** What the device "uploads": identity, signed prekey, last-resort and one-time keys. */
    val upload: UploadKeysRequest = keyManager.buildInitialUpload()
    val identityKey: String get() = upload.identityKey!!

    /** A bundle for [deviceId] as the server would hand it out (signed by this device's identity). */
    fun bundle(asDeviceId: String = deviceId, number: Int = 1): DeviceKeyBundle = DeviceKeyBundle(
        asDeviceId, number, upload.registrationId!!, upload.identityKey!!, upload.signedPrekey!!, upload.pqLastResortPrekey!!,
        upload.oneTimePrekeys?.firstOrNull(),
    )

    /** Encrypts [content] to a target using the target's real [bundle]; the attacker trusts whatever it talks to. */
    fun encryptFor(target: Recipient, bundle: DeviceKeyBundle, content: Content): Envelope {
        store.pinDevice(target.userId, target.deviceId, DeviceAddress.decodeIdentityKey(bundle.identityKey)!!, true)
        if (!crypto.hasSession(target.userId, target.deviceId)) crypto.processBundle(target.userId, target.deviceId, bundle)
        return crypto.encryptContent(content, listOf(target)).single()
    }
}
