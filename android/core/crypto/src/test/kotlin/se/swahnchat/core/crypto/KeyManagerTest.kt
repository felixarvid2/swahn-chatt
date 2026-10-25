package se.swahnchat.core.crypto

import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import se.swahnchat.core.model.KeyStatus
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyManagerTest {
    private var now = 1_700_000_000_000L
    private val d = TestDevice("alice", 1, clock = { now })
    private fun b64d(s: String) = Base64.getDecoder().decode(s)

    @Test
    fun `initial upload has everything, correct sizes and valid signatures`() {
        val r = d.keys.buildInitialUpload()
        assertNotNull(r.registrationId)
        assertTrue(r.registrationId!! in 1..16380)
        val identity = ECPublicKey(b64d(r.identityKey!!))
        assertEquals(33, b64d(r.identityKey!!).size)
        val signed = r.signedPrekey!!
        assertTrue(identity.verifySignature(b64d(signed.publicKey), b64d(signed.signature)))
        assertEquals(64, b64d(signed.signature).size)
        val last = r.pqLastResortPrekey!!
        assertEquals(1569, b64d(last.publicKey).size)
        assertTrue(identity.verifySignature(b64d(last.publicKey), b64d(last.signature)))
        KEMPublicKey(b64d(last.publicKey))
        assertEquals(100, r.oneTimePrekeys!!.size)
        assertEquals(100, r.oneTimePqPrekeys!!.size)
        assertEquals(100, r.oneTimePrekeys!!.map { it.keyId }.toSet().size)
        r.oneTimePqPrekeys!!.take(5).forEach {
            assertTrue(identity.verifySignature(b64d(it.publicKey), b64d(it.signature)))
        }
        // Kyber ids: last-resort and one-time keys share one id space without collisions
        val kyberIds = r.oneTimePqPrekeys!!.map { it.keyId } + last.keyId
        assertEquals(101, kyberIds.toSet().size)
        // private halves are stored locally
        assertEquals(100, d.storage.countPreKeys())
        assertEquals(101, d.storage.loadKyberPreKeys().size)
        assertEquals(1, d.storage.loadKyberPreKeys().count { it.lastResort })
        assertEquals(1, d.storage.loadSignedPreKeys().size)
    }

    @Test
    fun `identity is created once`() {
        val first = d.keys.buildInitialUpload()
        val second = d.keys.buildFullReupload()
        assertEquals(first.identityKey, second.identityKey)
        assertEquals(first.registrationId, second.registrationId)
        assertEquals(first.signedPrekey, second.signedPrekey) // same current signed prekey
        assertEquals(first.pqLastResortPrekey, second.pqLastResortPrekey)
        // but fresh one-time keys with new ids
        assertTrue(first.oneTimePrekeys!!.map { it.keyId }.intersect(second.oneTimePrekeys!!.map { it.keyId }.toSet()).isEmpty())
    }

    @Test
    fun `top up only below the low watermark`() {
        d.keys.buildInitialUpload()
        assertNull(d.keys.buildMaintenanceUpload(KeyStatus(100, 100, 1, 1)))
        assertNull(d.keys.buildMaintenanceUpload(KeyStatus(20, 20, 1, 1)))

        val ecOnly = d.keys.buildMaintenanceUpload(KeyStatus(19, 50, 1, 1))!!
        assertEquals(100, ecOnly.oneTimePrekeys!!.size)
        assertNull(ecOnly.oneTimePqPrekeys)
        assertNull(ecOnly.signedPrekey)
        assertNull(ecOnly.identityKey)

        val pqOnly = d.keys.buildMaintenanceUpload(KeyStatus(50, 0, 1, 1))!!
        assertNull(pqOnly.oneTimePrekeys)
        assertEquals(100, pqOnly.oneTimePqPrekeys!!.size)

        val both = d.keys.buildMaintenanceUpload(KeyStatus(0, 0, 1, 1))!!
        assertEquals(100, both.oneTimePrekeys!!.size)
        assertEquals(100, both.oneTimePqPrekeys!!.size)
        // ids never repeat across batches
        val ids = (listOf(ecOnly, both).flatMap { it.oneTimePrekeys!!.map { k -> k.keyId } })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `signed prekey and last resort rotate after 30 days and old ones are kept`() {
        val first = d.keys.buildInitialUpload()
        now += 29L * 24 * 3600 * 1000
        assertNull(d.keys.buildMaintenanceUpload(KeyStatus(100, 100, 1, 1)))
        now += 2L * 24 * 3600 * 1000
        val rot = d.keys.buildMaintenanceUpload(KeyStatus(100, 100, 1, 1))!!
        assertNotNull(rot.signedPrekey)
        assertNotNull(rot.pqLastResortPrekey)
        assertNotEquals(first.signedPrekey!!.keyId, rot.signedPrekey!!.keyId)
        assertNotEquals(first.pqLastResortPrekey!!.keyId, rot.pqLastResortPrekey!!.keyId)
        assertNull(rot.oneTimePrekeys)
        assertEquals(2, d.storage.loadSignedPreKeys().size) // old one still decrypts in-flight messages
        assertEquals(2, d.storage.loadKyberPreKeys().count { it.lastResort })
        // not rotated again right away
        assertNull(d.keys.buildMaintenanceUpload(KeyStatus(100, 100, rot.signedPrekey!!.keyId, rot.pqLastResortPrekey!!.keyId)))
        // much later the superseded ones are pruned
        now += 70L * 24 * 3600 * 1000
        val rot2 = d.keys.buildMaintenanceUpload(KeyStatus(100, 100, 2, 2))!!
        assertNotNull(rot2.signedPrekey)
        assertEquals(2, d.storage.loadSignedPreKeys().size) // v1 pruned, v2 + v3 kept
    }

    @Test
    fun `missing signed prekey on the server triggers a full re-upload`() {
        val first = d.keys.buildInitialUpload()
        val r = d.keys.buildMaintenanceUpload(KeyStatus(50, 50, null, null))!!
        assertEquals(first.identityKey, r.identityKey)
        assertEquals(first.signedPrekey, r.signedPrekey)
        assertEquals(100, r.oneTimePrekeys!!.size)
    }

    @Test
    fun `a message sent against a rotated-out signed prekey still decrypts`() {
        val server = FakeKeyServer()
        d.register(server)
        val alice = TestDevice("bob", 1).also { it.register(server) }
        alice.trust(d); d.trust(alice)
        alice.crypto.processBundle(d.userId, d.deviceId, server.bundle(d)) // bundle references signed prekey 1
        now += 31L * 24 * 3600 * 1000
        d.keys.buildMaintenanceUpload(server.status(d))?.let { server.put(d, it) }
        val enc = alice.crypto.encrypt(text("late"), d.userId, d.deviceId)
        assertEquals("late", String(d.crypto.decrypt(alice.userId, alice.deviceId, enc.type, enc.body)))
    }
}
