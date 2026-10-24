package se.familychat.app.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair
import se.familychat.app.appContext
import se.familychat.core.crypto.AttachmentCipher
import se.familychat.core.crypto.AttachmentCryptoException
import se.familychat.core.crypto.InMemoryProtocolStorage
import se.familychat.core.crypto.KeyManager
import se.familychat.core.crypto.MessageCrypto
import se.familychat.core.crypto.PersistentSignalProtocolStore
import se.familychat.core.crypto.Recipient
import se.familychat.core.model.AttachmentKind
import se.familychat.core.model.AttachmentRef
import se.familychat.core.model.Content
import se.familychat.core.model.ContentType
import se.familychat.core.model.DeviceKeyBundle
import se.familychat.core.model.EnvelopeType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** libsignal (JNI, arm64/x86_64 natives) and Tink on a device, through the :core:crypto API the app uses. */
@RunWith(AndroidJUnit4::class)
class CryptoInstrumentedTest {
    /** A device as the engine wires it, on the in-memory storage; no server involved. */
    private class Device(val userId: String, val deviceId: String, val number: Int = 1) {
        val storage = InMemoryProtocolStorage()
        val store = PersistentSignalProtocolStore(storage)
        val keys = KeyManager(store, storage)
        val crypto = MessageCrypto(store)
        val upload = keys.buildInitialUpload()

        /** What `GET /keys/{user}` would hand out for this device. */
        fun bundle() = DeviceKeyBundle(
            deviceId, number, upload.registrationId!!, upload.identityKey!!, upload.signedPrekey!!, upload.pqLastResortPrekey!!,
            upload.oneTimePrekeys!!.first(),
        )

        fun trust(other: Device) = store.pinDevice(other.userId, other.deviceId, other.keys.identityPublicKey(), true)
    }

    private fun text(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test fun libsignalNativeLibraryLoadsAndGeneratesKeys() {
        val a = IdentityKeyPair.generate()
        val b = IdentityKeyPair.generate()
        assertEquals(33, a.publicKey.serialize().size)
        assertFalse(a.publicKey.serialize().contentEquals(b.publicKey.serialize()))
        val sig = a.privateKey.calculateSignature(text("hej"))
        assertTrue(a.publicKey.publicKey.verifySignature(text("hej"), sig))
        assertFalse(a.publicKey.publicKey.verifySignature(text("hej!"), sig))
    }

    @Test fun identityAndPrekeysAreGenerated() {
        val d = Device("anna", "dev-a")
        assertTrue(d.keys.hasIdentity())
        assertEquals(33, d.keys.identityPublicKey().size)
        assertTrue(d.keys.registrationId() in 1..16380)
        assertNotNull(d.upload.signedPrekey)
        assertNotNull(d.upload.pqLastResortPrekey)
        assertTrue(d.upload.oneTimePrekeys!!.isNotEmpty())
        assertTrue(d.upload.oneTimePqPrekeys!!.isNotEmpty())
    }

    @Test fun twoPartiesEstablishAPqxdhSessionAndExchangeMessages() {
        val alice = Device("alice", "dev-alice")
        val bob = Device("bob", "dev-bob")
        alice.trust(bob)
        bob.trust(alice)
        alice.crypto.processBundle(bob.userId, bob.deviceId, bob.bundle())

        val first = alice.crypto.encrypt(text("hej Bob"), bob.userId, bob.deviceId)
        assertEquals(EnvelopeType.PREKEY, first.type)
        assertContentEquals(text("hej Bob"), bob.crypto.decrypt(alice.userId, alice.deviceId, first.type, first.body))

        val reply = bob.crypto.encrypt(text("hej Alice"), alice.userId, alice.deviceId)
        assertEquals(EnvelopeType.WHISPER, reply.type)
        assertContentEquals(text("hej Alice"), alice.crypto.decrypt(bob.userId, bob.deviceId, reply.type, reply.body))

        val rnd = Random(5)
        repeat(50) { i ->
            val (from, to) = if (i % 3 == 0) bob to alice else alice to bob
            val msg = text("meddelande $i åäö ${"x".repeat(rnd.nextInt(300))}")
            val enc = from.crypto.encrypt(msg, to.userId, to.deviceId)
            assertContentEquals(msg, to.crypto.decrypt(from.userId, from.deviceId, enc.type, enc.body))
        }
        assertTrue(alice.crypto.hasSession(bob.userId, bob.deviceId))
    }

    @Test fun contentSurvivesJsonPaddingAndEncryptionBetweenTwoDevices() {
        val alice = Device("alice", "dev-alice")
        val bob = Device("bob", "dev-bob")
        alice.trust(bob)
        bob.trust(alice)
        alice.crypto.processBundle(bob.userId, bob.deviceId, bob.bundle())

        val content = Content(
            type = ContentType.TEXT, text = "Hej på dig, Åsa! 😀", replyTo = "m0",
            conversationId = "c1", messageId = "m1",
            attachments = listOf(AttachmentRef("att1", "a2V5", "ab12", 1234, "image/jpeg", "bild.jpg", AttachmentKind.IMAGE, 640, 480)),
        )
        val envelope = alice.crypto.encryptContent(content, listOf(Recipient(bob.userId, bob.deviceId))).single()
        assertEquals(EnvelopeType.PREKEY, envelope.type)
        assertEquals(bob.deviceId, envelope.deviceId)
        val decoded = bob.crypto.decryptContent(alice.userId, alice.deviceId, envelope.type, envelope.body)
        assertEquals(content, decoded)
        // the envelope really is base64 ciphertext
        assertTrue(Base64.getDecoder().decode(envelope.body).size > 100)
    }

    private fun tinkEncrypt(data: ByteArray, key: ByteArray = AttachmentCipher.generateKey()): Pair<ByteArray, ByteArray> {
        val out = ByteArrayOutputStream()
        AttachmentCipher.encrypt(ByteArrayInputStream(data), out, key)
        return key to out.toByteArray()
    }

    @Test fun tinkAttachmentCipherRoundTripAndTamperDetection() {
        for (n in listOf(0, 1, 4095, 4096, 4097, 100_000)) {
            val data = Random(n).nextBytes(n)
            val (key, ct) = tinkEncrypt(data)
            assertFalse(n > 64 && ct.toList().windowed(32).any { it == data.take(32) }, "ciphertext must not contain the plaintext")
            val out = ByteArrayOutputStream()
            assertEquals(n.toLong(), AttachmentCipher.decrypt(ByteArrayInputStream(ct), out, key))
            assertContentEquals(data, out.toByteArray(), "size $n")
        }
        val (key, ct) = tinkEncrypt(Random(1).nextBytes(20_000))
        val tampered = ct.copyOf().also { it[ct.size / 2] = (it[ct.size / 2].toInt() xor 1).toByte() }
        assertFailsWith<AttachmentCryptoException> { AttachmentCipher.decrypt(ByteArrayInputStream(tampered), ByteArrayOutputStream(), key) }
        assertFailsWith<AttachmentCryptoException> { AttachmentCipher.decrypt(ByteArrayInputStream(ct), ByteArrayOutputStream(), AttachmentCipher.generateKey()) }
        assertFailsWith<AttachmentCryptoException> {
            AttachmentCipher.decrypt(ByteArrayInputStream(ct.copyOf(ct.size - 100)), ByteArrayOutputStream(), key)
        }
    }

    @Test fun tinkFileRoundTripOnDeviceStorage() {
        val dir = File(appContext.cacheDir, "att-test").apply { mkdirs() }
        try {
            val src = File(dir, "src").apply { writeBytes(Random(3).nextBytes(1_500_000)) }
            val enc = File(dir, "enc")
            val dec = File(dir, "dec")
            val r = AttachmentCipher.encryptFile(src, enc)
            assertEquals(enc.length(), r.ciphertextSize)
            assertEquals(AttachmentCipher.sha256Hex(enc), r.sha256Hex)
            AttachmentCipher.decryptFile(enc, dec, r.key)
            assertContentEquals(src.readBytes(), dec.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}
