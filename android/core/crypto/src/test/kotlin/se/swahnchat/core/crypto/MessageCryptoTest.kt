package se.swahnchat.core.crypto

import org.signal.libsignal.protocol.ecc.ECPublicKey
import se.swahnchat.core.model.Content
import se.swahnchat.core.model.EnvelopeType
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MessageCryptoTest {
    private val server = FakeKeyServer()
    private val alice = TestDevice("alice", 1)
    private val bob = TestDevice("bob", 1)

    init {
        alice.register(server)
        bob.register(server)
    }

    /** Alice sends the first (prekey) message; both sides then have sessions. */
    private fun handshake() {
        connect(alice, bob, server)
        val first = alice.crypto.encrypt(text("hello"), bob.userId, bob.deviceId)
        assertEquals(EnvelopeType.PREKEY, first.type)
        assertContentEquals(text("hello"), bob.crypto.decrypt(alice.userId, alice.deviceId, first.type, first.body))
        val reply = bob.crypto.encrypt(text("hi"), alice.userId, alice.deviceId)
        assertEquals(EnvelopeType.WHISPER, reply.type)
        assertContentEquals(text("hi"), alice.crypto.decrypt(bob.userId, bob.deviceId, reply.type, reply.body))
    }

    @Test
    fun `session from bundle, first message is prekey, later ones whisper, both directions`() {
        handshake()
        val m = alice.crypto.encrypt(text("again"), bob.userId, bob.deviceId)
        assertEquals(EnvelopeType.WHISPER, m.type)
        assertContentEquals(text("again"), bob.crypto.decrypt(alice.userId, alice.deviceId, m.type, m.body))
        assertTrue(alice.crypto.hasSession(bob.userId, bob.deviceId))
        assertTrue(bob.crypto.hasSession(alice.userId, alice.deviceId))
    }

    @Test
    fun `many messages in both directions with ratchet turns`() {
        handshake()
        val rnd = Random(7)
        repeat(300) { i ->
            val (from, to) = if (rnd.nextInt(3) == 0) bob to alice else alice to bob
            val msg = text("message $i from ${from.userId} ${"x".repeat(rnd.nextInt(500))}")
            val enc = from.crypto.encrypt(msg, to.userId, to.deviceId)
            assertContentEquals(msg, to.crypto.decrypt(from.userId, from.deviceId, enc.type, enc.body))
        }
    }

    @Test
    fun `out of order delivery within the ratchet window`() {
        handshake()
        val batch = (0 until 20).map { i -> text("m$i") to alice.crypto.encrypt(text("m$i"), bob.userId, bob.deviceId) }
        for ((plain, enc) in batch.shuffled(Random(3))) {
            assertContentEquals(plain, bob.crypto.decrypt(alice.userId, alice.deviceId, enc.type, enc.body))
        }
        // and across a ratchet turn: bob replies, alice sends more, late messages from the old chain still arrive
        val old = alice.crypto.encrypt(text("old"), bob.userId, bob.deviceId)
        val r = bob.crypto.encrypt(text("turn"), alice.userId, alice.deviceId)
        alice.crypto.decrypt(bob.userId, bob.deviceId, r.type, r.body)
        val newer = alice.crypto.encrypt(text("newer"), bob.userId, bob.deviceId)
        assertContentEquals(text("newer"), bob.crypto.decrypt(alice.userId, alice.deviceId, newer.type, newer.body))
        assertContentEquals(text("old"), bob.crypto.decrypt(alice.userId, alice.deviceId, old.type, old.body))
    }

    @Test
    fun `out of order first messages when several prekey messages are in flight`() {
        connect(alice, bob, server)
        val first = alice.crypto.encrypt(text("one"), bob.userId, bob.deviceId)
        val second = alice.crypto.encrypt(text("two"), bob.userId, bob.deviceId)
        val third = alice.crypto.encrypt(text("three"), bob.userId, bob.deviceId)
        assertEquals(EnvelopeType.PREKEY, second.type) // still prekey until bob answers
        assertContentEquals(text("three"), bob.crypto.decrypt(alice.userId, alice.deviceId, third.type, third.body))
        assertContentEquals(text("one"), bob.crypto.decrypt(alice.userId, alice.deviceId, first.type, first.body))
        assertContentEquals(text("two"), bob.crypto.decrypt(alice.userId, alice.deviceId, second.type, second.body))
    }

    @Test
    fun `multi device fan out - alice has two devices, bob one`() {
        val alice2 = TestDevice("alice", 2).also { it.register(server) }
        // alice1 sends to bob and to her own other device
        connect(alice, bob, server)
        connect(alice, alice2, server)
        val content = Content(text = "to everyone", conversationId = "c", messageId = "m")
        val envelopes = alice.crypto.encryptContent(content, listOf(bob.recipient, alice2.recipient))
        assertEquals(setOf(bob.deviceId, alice2.deviceId), envelopes.map { it.deviceId }.toSet())
        assertTrue(envelopes.all { it.type == EnvelopeType.PREKEY })
        val toBob = envelopes.first { it.deviceId == bob.deviceId }
        val toAlice2 = envelopes.first { it.deviceId == alice2.deviceId }
        assertEquals(content, bob.crypto.decryptContent(alice.userId, alice.deviceId, toBob.type, toBob.body))
        assertEquals(content, alice2.crypto.decryptContent(alice.userId, alice.deviceId, toAlice2.type, toAlice2.body))
        // ciphertexts for different devices differ
        assertFalse(toBob.body == toAlice2.body)

        // bob answers to both of alice's devices: he has a session with alice.1 from the prekey message he received and
        // builds one with alice.2 from her bundle
        connect(bob, alice2, server)
        val reply = Content(text = "hello alice", conversationId = "c", messageId = "m")
        val back = bob.crypto.encryptContent(reply, listOf(alice.recipient, alice2.recipient))
        for ((dev, env) in listOf(alice to back[0], alice2 to back[1])) {
            assertEquals(env.deviceId, dev.deviceId)
            assertEquals(reply, dev.crypto.decryptContent(bob.userId, bob.deviceId, env.type, env.body))
        }
        // alice2 can also talk to alice1 afterwards
        val a2 = alice2.crypto.encryptContent(Content(text = "from my tablet", conversationId = "c", messageId = "m"), listOf(alice.recipient))
        assertEquals("from my tablet", alice.crypto.decryptContent(alice2.userId, alice2.deviceId, a2[0].type, a2[0].body).text)
    }

    @Test
    fun `one-time prekeys exhausted falls back to signed and last-resort keys`() {
        server.drainOneTimeKeys(bob)
        alice.trust(bob); bob.trust(alice)
        val bundle = server.bundle(bob)
        assertEquals(null, bundle.oneTimePrekey)
        assertEquals(1, bundle.pqPrekey.keyId) // the last-resort key has the first Kyber id
        alice.crypto.processBundle(bob.userId, bob.deviceId, bundle)
        val enc = alice.crypto.encrypt(text("no one-time key"), bob.userId, bob.deviceId)
        assertContentEquals(text("no one-time key"), bob.crypto.decrypt(alice.userId, alice.deviceId, enc.type, enc.body))

        // The last-resort Kyber key is NOT deleted from bob's storage, and the signed prekey stays.
        val lastResort = bob.storage.loadKyberPreKeys().filter { it.lastResort }
        assertEquals(1, lastResort.size)
        assertNotNull(bob.storage.loadKyberPreKey(lastResort.single().id))

        // A second, independent sender can still use the same last-resort key.
        val carol = TestDevice("carol", 1).also { it.register(server) }
        carol.trust(bob); bob.trust(carol)
        carol.crypto.processBundle(bob.userId, bob.deviceId, server.bundle(bob))
        val enc2 = carol.crypto.encrypt(text("also fine"), bob.userId, bob.deviceId)
        assertContentEquals(text("also fine"), bob.crypto.decrypt(carol.userId, carol.deviceId, enc2.type, enc2.body))
    }

    @Test
    fun `used one-time prekeys are deleted locally`() {
        val before = bob.storage.countPreKeys()
        val kyberBefore = bob.storage.loadKyberPreKeys().size
        connect(alice, bob, server)
        val enc = alice.crypto.encrypt(text("x"), bob.userId, bob.deviceId)
        bob.crypto.decrypt(alice.userId, alice.deviceId, enc.type, enc.body)
        assertEquals(before - 1, bob.storage.countPreKeys())
        assertEquals(kyberBefore - 1, bob.storage.loadKyberPreKeys().size)
    }

    @Test
    fun `corrupted ciphertext raises InvalidCiphertext and the session keeps working`() {
        handshake()
        val rnd = Random(11)
        repeat(3) { round ->
            val good = alice.crypto.encrypt(text("good $round"), bob.userId, bob.deviceId)
            val body = good.body
            val broken = buildList {
                add(body.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }) // 1 bit in the body
                add(body.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xff).toByte() }) // MAC
                add(body.copyOf().also { it[0] = (it[0].toInt() xor 0x10).toByte() }) // version byte
                add(body.copyOf(body.size - 5)) // truncated
                add(body.copyOf(10)) // very short
                add(ByteArray(0))
                add(rnd.nextBytes(body.size)) // garbage
                add(body + byteArrayOf(1, 2, 3)) // trailing junk
            }
            for ((i, b) in broken.withIndex()) {
                assertFailsWith<InvalidCiphertextException>("variant $i") {
                    bob.crypto.decrypt(alice.userId, alice.deviceId, good.type, b)
                }
            }
            // wrong envelope type for valid bytes
            assertFailsWith<InvalidCiphertextException> { bob.crypto.decrypt(alice.userId, alice.deviceId, EnvelopeType.PREKEY, body) }
            assertFailsWith<InvalidCiphertextException> { bob.crypto.decrypt(alice.userId, alice.deviceId, "bogus", body) }
            // ...and the intact message still decrypts afterwards
            assertContentEquals(text("good $round"), bob.crypto.decrypt(alice.userId, alice.deviceId, good.type, body))
            // reply so the ratchet turns between rounds
            val r = bob.crypto.encrypt(text("r$round"), alice.userId, alice.deviceId)
            alice.crypto.decrypt(bob.userId, bob.deviceId, r.type, r.body)
        }
    }

    @Test
    fun `corrupted first prekey message leaves no half-built session and the intact one works`() {
        connect(alice, bob, server)
        val first = alice.crypto.encrypt(text("first"), bob.userId, bob.deviceId)
        val prekeysBefore = bob.storage.countPreKeys()
        for (pos in listOf(first.body.size - 1, first.body.size / 2, 40, 12)) {
            val bad = first.body.copyOf().also { it[pos] = (it[pos].toInt() xor 0x04).toByte() }
            assertFailsWith<CryptoException> { bob.crypto.decrypt(alice.userId, alice.deviceId, first.type, bad) }
        }
        assertEquals(prekeysBefore, bob.storage.countPreKeys()) // one-time key not burnt by failures
        assertFalse(bob.crypto.hasSession(alice.userId, alice.deviceId))
        assertContentEquals(text("first"), bob.crypto.decrypt(alice.userId, alice.deviceId, first.type, first.body))
    }

    @Test
    fun `replays raise Duplicate`() {
        connect(alice, bob, server)
        val first = alice.crypto.encrypt(text("one"), bob.userId, bob.deviceId)
        bob.crypto.decrypt(alice.userId, alice.deviceId, first.type, first.body)
        assertFailsWith<DuplicateMessageException> { bob.crypto.decrypt(alice.userId, alice.deviceId, first.type, first.body) } // prekey replay
        val reply = bob.crypto.encrypt(text("r"), alice.userId, alice.deviceId)
        alice.crypto.decrypt(bob.userId, bob.deviceId, reply.type, reply.body)
        val second = alice.crypto.encrypt(text("two"), bob.userId, bob.deviceId)
        bob.crypto.decrypt(alice.userId, alice.deviceId, second.type, second.body)
        assertFailsWith<DuplicateMessageException> { bob.crypto.decrypt(alice.userId, alice.deviceId, second.type, second.body) } // whisper replay
        // still fine afterwards
        val third = alice.crypto.encrypt(text("three"), bob.userId, bob.deviceId)
        assertContentEquals(text("three"), bob.crypto.decrypt(alice.userId, alice.deviceId, third.type, third.body))
    }

    @Test
    fun `replay of a prekey message that used the last-resort key is also rejected`() {
        server.drainOneTimeKeys(bob)
        connect(alice, bob, server)
        val first = alice.crypto.encrypt(text("one"), bob.userId, bob.deviceId)
        bob.crypto.decrypt(alice.userId, alice.deviceId, first.type, first.body)
        assertFailsWith<DuplicateMessageException> { bob.crypto.decrypt(alice.userId, alice.deviceId, first.type, first.body) }
        // Even after bob lost the session (e.g. deleted), the replay cannot resurrect it.
        bob.crypto.deleteSession(alice.userId, alice.deviceId)
        assertFailsWith<CryptoException> { bob.crypto.decrypt(alice.userId, alice.deviceId, first.type, first.body) }
    }

    @Test
    fun `no session errors`() {
        assertFailsWith<NoSessionException> { alice.crypto.encrypt(text("x"), bob.userId, bob.deviceId) }
        handshake()
        val m = alice.crypto.encrypt(text("x"), bob.userId, bob.deviceId)
        val stranger = TestDevice("dave", 1)
        assertFailsWith<NoSessionException> { stranger.crypto.decrypt(alice.userId, alice.deviceId, m.type, m.body) }
    }

    @Test
    fun `changed identity is not trusted - bundle and prekey message paths`() {
        handshake()
        val pinned = bob.store.pinnedIdentity(alice.deviceId)!!.identityKey
        assertContentEquals(alice.keys.identityPublicKey(), pinned)

        // The same device uuid shows up with a new identity key (reinstall, or a malicious server).
        val alice2 = TestDevice("alice", 1, deviceId = alice.deviceId)
        val server2 = FakeKeyServer().also { alice2.register(it); bob.register(it) }
        assertFalse(alice2.keys.identityPublicKey().contentEquals(pinned))
        alice2.trust(bob)

        // 1) Bob building a session from the new bundle is refused.
        val e1 = assertFailsWith<UntrustedIdentityException> {
            bob.crypto.processBundle(alice.userId, alice.deviceId, server2.bundle(alice2))
        }
        assertContentEquals(alice2.keys.identityPublicKey(), e1.newIdentityKey)

        // 2) A prekey message from the new identity is refused at decrypt.
        alice2.crypto.processBundle(bob.userId, bob.deviceId, server2.bundle(bob))
        val enc = alice2.crypto.encrypt(text("it's me"), bob.userId, bob.deviceId)
        val e2 = assertFailsWith<UntrustedIdentityException> { bob.crypto.decrypt(alice.userId, alice.deviceId, enc.type, enc.body) }
        assertEquals("alice", e2.userId)
        assertContentEquals(alice2.keys.identityPublicKey(), e2.newIdentityKey)
        // the pin did not move, and bob's old session still works with the real alice
        assertContentEquals(pinned, bob.store.pinnedIdentity(alice.deviceId)!!.identityKey)
        val still = alice.crypto.encrypt(text("old alice"), bob.userId, bob.deviceId)
        assertContentEquals(text("old alice"), bob.crypto.decrypt(alice.userId, alice.deviceId, still.type, still.body))

        // 3) After explicit acceptance the new identity works and the old session is gone.
        bob.store.acceptIdentity(alice.userId, alice.deviceId, e2.newIdentityKey!!)
        assertFalse(bob.crypto.hasSession(alice.userId, alice.deviceId))
        assertContentEquals(text("it's me"), bob.crypto.decrypt(alice.userId, alice.deviceId, enc.type, enc.body))
        assertContentEquals(alice2.keys.identityPublicKey(), bob.store.pinnedIdentity(alice.deviceId)!!.identityKey)
    }

    @Test
    fun `identity pinning and verified flag`() {
        handshake()
        assertFalse(bob.store.pinnedIdentity(alice.deviceId)!!.verified)
        assertFalse(bob.store.hasVerifiedDevice(alice.userId))
        bob.store.setVerified(alice.deviceId, true)
        assertTrue(bob.store.pinnedIdentity(alice.deviceId)!!.verified)
        assertTrue(bob.store.hasVerifiedDevice(alice.userId))
        // different devices of the same user pin independently
        val alice2 = TestDevice("alice", 2).also { it.register(server) }
        connect(alice2, bob, server)
        val enc = alice2.crypto.encrypt(text("x"), bob.userId, bob.deviceId)
        bob.crypto.decrypt(alice2.userId, alice2.deviceId, enc.type, enc.body)
        assertNotNull(bob.store.pinnedIdentity(alice2.deviceId))
        assertFalse(bob.store.pinnedIdentity(alice2.deviceId)!!.verified)
    }

    @Test
    fun `libsignal accepts the user slash uuid address and keeps devices of one user apart`() {
        val alice2 = TestDevice("alice", 2).also { it.register(server) }
        connect(alice, bob, server)
        connect(alice2, bob, server)
        val m1 = alice.crypto.encrypt(text("from one"), bob.userId, bob.deviceId)
        val m2 = alice2.crypto.encrypt(text("from two"), bob.userId, bob.deviceId)
        assertContentEquals(text("from two"), bob.crypto.decrypt(alice2.userId, alice2.deviceId, m2.type, m2.body))
        assertContentEquals(text("from one"), bob.crypto.decrypt(alice.userId, alice.deviceId, m1.type, m1.body))
        assertTrue(bob.crypto.hasSession(alice.userId, alice.deviceId))
        assertTrue(bob.crypto.hasSession(alice2.userId, alice2.deviceId))
        bob.crypto.deleteAllSessions(alice.userId)
        assertFalse(bob.crypto.hasSession(alice.userId, alice.deviceId))
        assertFalse(bob.crypto.hasSession(alice2.userId, alice2.deviceId))
    }

    @Test
    fun `a device that was never pinned is untrusted - no libsignal level trust on first use`() {
        // No trust() calls: neither a bundle nor a prekey message may create a pin.
        assertFailsWith<UntrustedIdentityException> {
            alice.crypto.processBundle(bob.userId, bob.deviceId, server.bundle(bob))
        }
        assertEquals(null, alice.store.pinnedIdentity(bob.deviceId))
        alice.trust(bob)
        alice.crypto.processBundle(bob.userId, bob.deviceId, server.bundle(bob))
        val enc = alice.crypto.encrypt(text("hi"), bob.userId, bob.deviceId)
        // bob never listed alice
        assertFailsWith<UntrustedIdentityException> { bob.crypto.decrypt(alice.userId, alice.deviceId, enc.type, enc.body) }
        assertEquals(null, bob.store.pinnedIdentity(alice.deviceId))
    }

    @Test
    fun `a pinned uuid cannot be used under another user id`() {
        handshake()
        // The server relabels alice's device as mallory's: the pin is by uuid and remembers the owner.
        val e = assertFailsWith<UntrustedIdentityException> {
            bob.crypto.processBundle("mallory", alice.deviceId, server.bundle(alice))
        }
        assertEquals("mallory", e.userId)
        val enc = alice.crypto.encrypt(text("x"), bob.userId, bob.deviceId)
        assertFailsWith<CryptoException> { bob.crypto.decrypt("mallory", alice.deviceId, enc.type, enc.body) }
        assertFalse(bob.store.pinnedIdentity(alice.deviceId)!!.userId != alice.userId)
    }

    @Test
    fun `an unapproved device is blocked both ways until accepted`() {
        val newDevice = TestDevice("alice", 3).also { it.register(server) }
        bob.trust(newDevice, approved = false)
        newDevice.trust(bob)
        assertFailsWith<UntrustedIdentityException> {
            bob.crypto.processBundle(newDevice.userId, newDevice.deviceId, server.bundle(newDevice))
        }
        newDevice.crypto.processBundle(bob.userId, bob.deviceId, server.bundle(bob))
        val enc = newDevice.crypto.encrypt(text("hello"), bob.userId, bob.deviceId)
        assertFailsWith<UntrustedIdentityException> { bob.crypto.decrypt(newDevice.userId, newDevice.deviceId, enc.type, enc.body) }
        bob.store.acceptIdentity(newDevice.userId, newDevice.deviceId, newDevice.keys.identityPublicKey())
        assertContentEquals(text("hello"), bob.crypto.decrypt(newDevice.userId, newDevice.deviceId, enc.type, enc.body))
        bob.crypto.processBundle(newDevice.userId, newDevice.deviceId, server.bundle(newDevice))
    }

    @Test
    fun `a bundle of another device is refused and never pins`() {
        alice.trust(bob)
        val other = TestDevice("bob", 2).also { it.register(server) }
        assertFailsWith<InvalidBundleException> {
            alice.crypto.processBundle(bob.userId, bob.deviceId, server.bundle(other))
        }
        assertEquals(null, alice.store.pinnedIdentity(other.deviceId))
    }

    @Test
    fun `tampered bundle signatures are rejected`() {
        alice.trust(bob)
        val b = server.bundle(bob)
        val flipped = { s: String ->
            val raw = Base64.getDecoder().decode(s)
            raw[3] = (raw[3].toInt() xor 1).toByte()
            Base64.getEncoder().encodeToString(raw)
        }
        assertFailsWith<InvalidBundleException> {
            alice.crypto.processBundle(bob.userId, bob.deviceId, b.copy(signedPrekey = b.signedPrekey.copy(signature = flipped(b.signedPrekey.signature))))
        }
        assertFailsWith<InvalidBundleException> {
            alice.crypto.processBundle(bob.userId, bob.deviceId, b.copy(pqPrekey = b.pqPrekey.copy(signature = flipped(b.pqPrekey.signature))))
        }
        assertFailsWith<InvalidBundleException> {
            alice.crypto.processBundle(bob.userId, bob.deviceId, b.copy(identityKey = "AAAA"))
        }
        // a signed prekey signed by somebody else (not the bundle's identity)
        val mallory = TestDevice("mallory", 1).also { it.register(server) }
        val mb = server.bundle(mallory)
        assertFailsWith<InvalidBundleException> {
            alice.crypto.processBundle(bob.userId, bob.deviceId, b.copy(signedPrekey = mb.signedPrekey))
        }
        assertFalse(alice.crypto.hasSession(bob.userId, bob.deviceId))
        // the genuine bundle still works afterwards
        alice.crypto.processBundle(bob.userId, bob.deviceId, server.bundle(bob))
        assertTrue(alice.crypto.hasSession(bob.userId, bob.deviceId))
    }

    @Test
    fun `server visible bytes never contain the plaintext and padding hides length`() {
        handshake()
        val marker = "SECRET-MARKER-f3a9c1"
        val envelopes = alice.crypto.encryptContent(Content(text = "$marker with some more words", conversationId = "c", messageId = "m"), listOf(bob.recipient))
        val raw = Base64.getDecoder().decode(envelopes.single().body)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(marker))
        assertFalse(envelopes.single().body.contains(marker))
        assertEquals(marker + " with some more words", bob.crypto.decryptContent(alice.userId, alice.deviceId, envelopes[0].type, envelopes[0].body).text.orEmpty())

        val short = alice.crypto.encryptContent(Content(text = "a", conversationId = "c", messageId = "m"), listOf(bob.recipient)).single()
        val medium = alice.crypto.encryptContent(Content(text = "b".repeat(60), conversationId = "c", messageId = "m"), listOf(bob.recipient)).single()
        assertEquals(Base64.getDecoder().decode(short.body).size, Base64.getDecoder().decode(medium.body).size)
        bob.crypto.decryptContent(alice.userId, alice.deviceId, short.type, short.body)
        bob.crypto.decryptContent(alice.userId, alice.deviceId, medium.type, medium.body)
    }

    @Test
    fun `invalid padding or json inside a valid ciphertext is a content error`() {
        handshake()
        val notPadded = alice.crypto.encrypt(text("not padded at all"), bob.userId, bob.deviceId)
        assertFailsWith<InvalidCiphertextException> {
            bob.crypto.decryptContent(alice.userId, alice.deviceId, notPadded.type, Base64.getEncoder().encodeToString(notPadded.body))
        }
        val badB64 = assertFailsWith<InvalidCiphertextException> { bob.crypto.decryptContent(alice.userId, alice.deviceId, "whisper", "***") }
        assertNotNull(badB64.message)
    }

    @Test
    fun `content without its binding to a conversation and message id is rejected after decryption`() {
        handshake()
        val unbound = alice.crypto.encryptContent(Content(text = "no ids"), listOf(bob.recipient)).single()
        assertFailsWith<InvalidCiphertextException> {
            bob.crypto.decryptContent(alice.userId, alice.deviceId, unbound.type, unbound.body)
        }
    }

    @Test
    fun `sessions survive a restart on the same storage`() {
        handshake()
        val bob2 = bob.restarted()
        val alice2 = alice.restarted()
        val m = alice2.crypto.encrypt(text("after restart"), bob.userId, bob.deviceId)
        assertContentEquals(text("after restart"), bob2.crypto.decrypt(alice.userId, alice.deviceId, m.type, m.body))
        val r = bob2.crypto.encrypt(text("yes"), alice.userId, alice.deviceId)
        assertContentEquals(text("yes"), alice2.crypto.decrypt(bob.userId, bob.deviceId, r.type, r.body))
    }

    @Test
    fun `concurrent encrypt and decrypt do not corrupt the ratchet`() {
        handshake()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 200).map { i ->
                pool.submit<Pair<ByteArray, EncryptedMessage>> {
                    text("c$i") to alice.crypto.encrypt(text("c$i"), bob.userId, bob.deviceId)
                }
            }
            val sent = futures.map { it.get() }
            for ((plain, enc) in sent.shuffled(Random(5))) {
                assertContentEquals(plain, bob.crypto.decrypt(alice.userId, alice.deviceId, enc.type, enc.body))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `identity public key helper is a valid EC key`() {
        ECPublicKey(alice.keys.identityPublicKey())
        assertEquals(33, alice.keys.identityPublicKey().size)
    }
}
