package se.familychat.core.crypto

import org.signal.libsignal.protocol.ecc.ECPublicKey
import se.familychat.core.model.Content
import se.familychat.core.model.EnvelopeType
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
        val first = alice.crypto.encrypt(text("hello"), "bob", 1)
        assertEquals(EnvelopeType.PREKEY, first.type)
        assertContentEquals(text("hello"), bob.crypto.decrypt("alice", 1, first.type, first.body))
        val reply = bob.crypto.encrypt(text("hi"), "alice", 1)
        assertEquals(EnvelopeType.WHISPER, reply.type)
        assertContentEquals(text("hi"), alice.crypto.decrypt("bob", 1, reply.type, reply.body))
    }

    @Test
    fun `session from bundle, first message is prekey, later ones whisper, both directions`() {
        handshake()
        val m = alice.crypto.encrypt(text("again"), "bob", 1)
        assertEquals(EnvelopeType.WHISPER, m.type)
        assertContentEquals(text("again"), bob.crypto.decrypt("alice", 1, m.type, m.body))
        assertTrue(alice.crypto.hasSession("bob", 1))
        assertTrue(bob.crypto.hasSession("alice", 1))
    }

    @Test
    fun `many messages in both directions with ratchet turns`() {
        handshake()
        val rnd = Random(7)
        repeat(300) { i ->
            val (from, to) = if (rnd.nextInt(3) == 0) bob to alice else alice to bob
            val msg = text("message $i from ${from.userId} ${"x".repeat(rnd.nextInt(500))}")
            val enc = from.crypto.encrypt(msg, to.userId, 1)
            assertContentEquals(msg, to.crypto.decrypt(from.userId, 1, enc.type, enc.body))
        }
    }

    @Test
    fun `out of order delivery within the ratchet window`() {
        handshake()
        val batch = (0 until 20).map { i -> text("m$i") to alice.crypto.encrypt(text("m$i"), "bob", 1) }
        for ((plain, enc) in batch.shuffled(Random(3))) {
            assertContentEquals(plain, bob.crypto.decrypt("alice", 1, enc.type, enc.body))
        }
        // and across a ratchet turn: bob replies, alice sends more, late messages from the old chain still arrive
        val old = alice.crypto.encrypt(text("old"), "bob", 1)
        val r = bob.crypto.encrypt(text("turn"), "alice", 1)
        alice.crypto.decrypt("bob", 1, r.type, r.body)
        val newer = alice.crypto.encrypt(text("newer"), "bob", 1)
        assertContentEquals(text("newer"), bob.crypto.decrypt("alice", 1, newer.type, newer.body))
        assertContentEquals(text("old"), bob.crypto.decrypt("alice", 1, old.type, old.body))
    }

    @Test
    fun `out of order first messages when several prekey messages are in flight`() {
        connect(alice, bob, server)
        val first = alice.crypto.encrypt(text("one"), "bob", 1)
        val second = alice.crypto.encrypt(text("two"), "bob", 1)
        val third = alice.crypto.encrypt(text("three"), "bob", 1)
        assertEquals(EnvelopeType.PREKEY, second.type) // still prekey until bob answers
        assertContentEquals(text("three"), bob.crypto.decrypt("alice", 1, third.type, third.body))
        assertContentEquals(text("one"), bob.crypto.decrypt("alice", 1, first.type, first.body))
        assertContentEquals(text("two"), bob.crypto.decrypt("alice", 1, second.type, second.body))
    }

    @Test
    fun `multi device fan out - alice has two devices, bob one`() {
        val alice2 = TestDevice("alice", 2).also { it.register(server) }
        // alice1 sends to bob and to her own other device
        connect(alice, bob, server)
        connect(alice, alice2, server)
        val content = Content(text = "to everyone")
        val envelopes = alice.crypto.encryptContent(content, listOf(bob.recipient, alice2.recipient))
        assertEquals(setOf(bob.deviceId, alice2.deviceId), envelopes.map { it.deviceId }.toSet())
        assertTrue(envelopes.all { it.type == EnvelopeType.PREKEY })
        val toBob = envelopes.first { it.deviceId == bob.deviceId }
        val toAlice2 = envelopes.first { it.deviceId == alice2.deviceId }
        assertEquals(content, bob.crypto.decryptContent("alice", 1, toBob.type, toBob.body))
        assertEquals(content, alice2.crypto.decryptContent("alice", 1, toAlice2.type, toAlice2.body))
        // ciphertexts for different devices differ
        assertFalse(toBob.body == toAlice2.body)

        // bob answers to both of alice's devices: he has a session with alice.1 from the prekey message he received and
        // builds one with alice.2 from her bundle
        connect(bob, alice2, server)
        val reply = Content(text = "hello alice")
        val back = bob.crypto.encryptContent(reply, listOf(alice.recipient, Recipient("alice", 2, alice2.deviceId)))
        for ((dev, env) in listOf(alice to back[0], alice2 to back[1])) {
            assertEquals(env.deviceId, dev.deviceId)
            assertEquals(reply, dev.crypto.decryptContent("bob", 1, env.type, env.body))
        }
        // alice2 can also talk to alice1 afterwards
        val a2 = alice2.crypto.encryptContent(Content(text = "from my tablet"), listOf(alice.recipient))
        assertEquals("from my tablet", alice.crypto.decryptContent("alice", 2, a2[0].type, a2[0].body).text)
    }

    @Test
    fun `one-time prekeys exhausted falls back to signed and last-resort keys`() {
        server.drainOneTimeKeys(bob)
        val bundle = server.bundle(bob)
        assertEquals(null, bundle.oneTimePrekey)
        assertEquals(1, bundle.pqPrekey.keyId) // the last-resort key has the first Kyber id
        alice.crypto.processBundle("bob", bundle)
        val enc = alice.crypto.encrypt(text("no one-time key"), "bob", 1)
        assertContentEquals(text("no one-time key"), bob.crypto.decrypt("alice", 1, enc.type, enc.body))

        // The last-resort Kyber key is NOT deleted from bob's storage, and the signed prekey stays.
        val lastResort = bob.storage.loadKyberPreKeys().filter { it.lastResort }
        assertEquals(1, lastResort.size)
        assertNotNull(bob.storage.loadKyberPreKey(lastResort.single().id))

        // A second, independent sender can still use the same last-resort key.
        val carol = TestDevice("carol", 1).also { it.register(server) }
        carol.crypto.processBundle("bob", server.bundle(bob))
        val enc2 = carol.crypto.encrypt(text("also fine"), "bob", 1)
        assertContentEquals(text("also fine"), bob.crypto.decrypt("carol", 1, enc2.type, enc2.body))
    }

    @Test
    fun `used one-time prekeys are deleted locally`() {
        val before = bob.storage.countPreKeys()
        val kyberBefore = bob.storage.loadKyberPreKeys().size
        connect(alice, bob, server)
        val enc = alice.crypto.encrypt(text("x"), "bob", 1)
        bob.crypto.decrypt("alice", 1, enc.type, enc.body)
        assertEquals(before - 1, bob.storage.countPreKeys())
        assertEquals(kyberBefore - 1, bob.storage.loadKyberPreKeys().size)
    }

    @Test
    fun `corrupted ciphertext raises InvalidCiphertext and the session keeps working`() {
        handshake()
        val rnd = Random(11)
        repeat(3) { round ->
            val good = alice.crypto.encrypt(text("good $round"), "bob", 1)
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
                    bob.crypto.decrypt("alice", 1, good.type, b)
                }
            }
            // wrong envelope type for valid bytes
            assertFailsWith<InvalidCiphertextException> { bob.crypto.decrypt("alice", 1, EnvelopeType.PREKEY, body) }
            assertFailsWith<InvalidCiphertextException> { bob.crypto.decrypt("alice", 1, "bogus", body) }
            // ...and the intact message still decrypts afterwards
            assertContentEquals(text("good $round"), bob.crypto.decrypt("alice", 1, good.type, body))
            // reply so the ratchet turns between rounds
            val r = bob.crypto.encrypt(text("r$round"), "alice", 1)
            alice.crypto.decrypt("bob", 1, r.type, r.body)
        }
    }

    @Test
    fun `corrupted first prekey message leaves no half-built session and the intact one works`() {
        connect(alice, bob, server)
        val first = alice.crypto.encrypt(text("first"), "bob", 1)
        val prekeysBefore = bob.storage.countPreKeys()
        for (pos in listOf(first.body.size - 1, first.body.size / 2, 40, 12)) {
            val bad = first.body.copyOf().also { it[pos] = (it[pos].toInt() xor 0x04).toByte() }
            assertFailsWith<CryptoException> { bob.crypto.decrypt("alice", 1, first.type, bad) }
        }
        assertEquals(prekeysBefore, bob.storage.countPreKeys()) // one-time key not burnt by failures
        assertFalse(bob.crypto.hasSession("alice", 1))
        assertContentEquals(text("first"), bob.crypto.decrypt("alice", 1, first.type, first.body))
    }

    @Test
    fun `replays raise Duplicate`() {
        connect(alice, bob, server)
        val first = alice.crypto.encrypt(text("one"), "bob", 1)
        bob.crypto.decrypt("alice", 1, first.type, first.body)
        assertFailsWith<DuplicateMessageException> { bob.crypto.decrypt("alice", 1, first.type, first.body) } // prekey replay
        val reply = bob.crypto.encrypt(text("r"), "alice", 1)
        alice.crypto.decrypt("bob", 1, reply.type, reply.body)
        val second = alice.crypto.encrypt(text("two"), "bob", 1)
        bob.crypto.decrypt("alice", 1, second.type, second.body)
        assertFailsWith<DuplicateMessageException> { bob.crypto.decrypt("alice", 1, second.type, second.body) } // whisper replay
        // still fine afterwards
        val third = alice.crypto.encrypt(text("three"), "bob", 1)
        assertContentEquals(text("three"), bob.crypto.decrypt("alice", 1, third.type, third.body))
    }

    @Test
    fun `replay of a prekey message that used the last-resort key is also rejected`() {
        server.drainOneTimeKeys(bob)
        connect(alice, bob, server)
        val first = alice.crypto.encrypt(text("one"), "bob", 1)
        bob.crypto.decrypt("alice", 1, first.type, first.body)
        assertFailsWith<DuplicateMessageException> { bob.crypto.decrypt("alice", 1, first.type, first.body) }
        // Even after bob lost the session (e.g. deleted), the replay cannot resurrect it.
        bob.crypto.deleteSession("alice", 1)
        assertFailsWith<CryptoException> { bob.crypto.decrypt("alice", 1, first.type, first.body) }
    }

    @Test
    fun `no session errors`() {
        assertFailsWith<NoSessionException> { alice.crypto.encrypt(text("x"), "bob", 1) }
        handshake()
        val m = alice.crypto.encrypt(text("x"), "bob", 1)
        val stranger = TestDevice("dave", 1)
        assertFailsWith<NoSessionException> { stranger.crypto.decrypt("alice", 1, m.type, m.body) }
    }

    @Test
    fun `changed identity is not trusted - bundle and prekey message paths`() {
        handshake()
        val pinned = bob.store.pinnedIdentity("alice", 1)!!.identityKey
        assertContentEquals(alice.keys.identityPublicKey(), pinned)

        // Alice reinstalls but (incorrectly or maliciously) keeps user id + device number: new identity key.
        val alice2 = TestDevice("alice", 1)
        val server2 = FakeKeyServer().also { alice2.register(it); bob.register(it) }
        assertFalse(alice2.keys.identityPublicKey().contentEquals(pinned))

        // 1) Bob building a session from the new bundle is refused.
        val e1 = assertFailsWith<UntrustedIdentityException> { bob.crypto.processBundle("alice", server2.bundle(alice2)) }
        assertContentEquals(alice2.keys.identityPublicKey(), e1.newIdentityKey)

        // 2) A prekey message from the new identity is refused at decrypt.
        alice2.crypto.processBundle("bob", server2.bundle(bob))
        val enc = alice2.crypto.encrypt(text("it's me"), "bob", 1)
        val e2 = assertFailsWith<UntrustedIdentityException> { bob.crypto.decrypt("alice", 1, enc.type, enc.body) }
        assertEquals("alice", e2.userId)
        assertContentEquals(alice2.keys.identityPublicKey(), e2.newIdentityKey)
        // the pin did not move, and bob's old session still works with the real alice
        assertContentEquals(pinned, bob.store.pinnedIdentity("alice", 1)!!.identityKey)
        val still = alice.crypto.encrypt(text("old alice"), "bob", 1)
        assertContentEquals(text("old alice"), bob.crypto.decrypt("alice", 1, still.type, still.body))

        // 3) After explicit acceptance the new identity works and the old session is gone.
        bob.store.acceptIdentity("alice", 1, e2.newIdentityKey!!)
        assertFalse(bob.crypto.hasSession("alice", 1))
        assertContentEquals(text("it's me"), bob.crypto.decrypt("alice", 1, enc.type, enc.body))
        assertContentEquals(alice2.keys.identityPublicKey(), bob.store.pinnedIdentity("alice", 1)!!.identityKey)
    }

    @Test
    fun `identity pinning and verified flag`() {
        handshake()
        assertFalse(bob.store.pinnedIdentity("alice", 1)!!.verified)
        bob.store.setVerified("alice", 1, true)
        assertTrue(bob.store.pinnedIdentity("alice", 1)!!.verified)
        // different devices of the same user pin independently (TOFU per device)
        val alice2 = TestDevice("alice", 2).also { it.register(server) }
        connect(alice2, bob, server)
        val enc = alice2.crypto.encrypt(text("x"), "bob", 1)
        bob.crypto.decrypt("alice", 2, enc.type, enc.body)
        assertNotNull(bob.store.pinnedIdentity("alice", 2))
    }

    @Test
    fun `tampered bundle signatures are rejected`() {
        val b = server.bundle(bob)
        val flipped = { s: String ->
            val raw = Base64.getDecoder().decode(s)
            raw[3] = (raw[3].toInt() xor 1).toByte()
            Base64.getEncoder().encodeToString(raw)
        }
        assertFailsWith<InvalidBundleException> {
            alice.crypto.processBundle("bob", b.copy(signedPrekey = b.signedPrekey.copy(signature = flipped(b.signedPrekey.signature))))
        }
        assertFailsWith<InvalidBundleException> {
            alice.crypto.processBundle("bob", b.copy(pqPrekey = b.pqPrekey.copy(signature = flipped(b.pqPrekey.signature))))
        }
        assertFailsWith<InvalidBundleException> {
            alice.crypto.processBundle("bob", b.copy(identityKey = "AAAA"))
        }
        // a signed prekey signed by somebody else (not the bundle's identity)
        val mallory = TestDevice("mallory", 1).also { it.register(server) }
        val mb = server.bundle(mallory)
        assertFailsWith<InvalidBundleException> {
            alice.crypto.processBundle("bob", b.copy(signedPrekey = mb.signedPrekey))
        }
        assertFalse(alice.crypto.hasSession("bob", 1))
        // the genuine bundle still works afterwards
        alice.crypto.processBundle("bob", server.bundle(bob))
        assertTrue(alice.crypto.hasSession("bob", 1))
    }

    @Test
    fun `server visible bytes never contain the plaintext and padding hides length`() {
        handshake()
        val marker = "SECRET-MARKER-f3a9c1"
        val envelopes = alice.crypto.encryptContent(Content(text = "$marker with some more words"), listOf(bob.recipient))
        val raw = Base64.getDecoder().decode(envelopes.single().body)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(marker))
        assertFalse(envelopes.single().body.contains(marker))
        assertEquals(marker + " with some more words", bob.crypto.decryptContent("alice", 1, envelopes[0].type, envelopes[0].body).text.orEmpty())

        val short = alice.crypto.encryptContent(Content(text = "a"), listOf(bob.recipient)).single()
        val medium = alice.crypto.encryptContent(Content(text = "b".repeat(60)), listOf(bob.recipient)).single()
        assertEquals(Base64.getDecoder().decode(short.body).size, Base64.getDecoder().decode(medium.body).size)
        bob.crypto.decryptContent("alice", 1, short.type, short.body)
        bob.crypto.decryptContent("alice", 1, medium.type, medium.body)
    }

    @Test
    fun `invalid padding or json inside a valid ciphertext is a content error`() {
        handshake()
        val notPadded = alice.crypto.encrypt(text("not padded at all"), "bob", 1)
        assertFailsWith<InvalidCiphertextException> {
            bob.crypto.decryptContent("alice", 1, notPadded.type, Base64.getEncoder().encodeToString(notPadded.body))
        }
        val badB64 = assertFailsWith<InvalidCiphertextException> { bob.crypto.decryptContent("alice", 1, "whisper", "***") }
        assertNotNull(badB64.message)
    }

    @Test
    fun `sessions survive a restart on the same storage`() {
        handshake()
        val bob2 = bob.restarted()
        val alice2 = alice.restarted()
        val m = alice2.crypto.encrypt(text("after restart"), "bob", 1)
        assertContentEquals(text("after restart"), bob2.crypto.decrypt("alice", 1, m.type, m.body))
        val r = bob2.crypto.encrypt(text("yes"), "alice", 1)
        assertContentEquals(text("yes"), alice2.crypto.decrypt("bob", 1, r.type, r.body))
    }

    @Test
    fun `concurrent encrypt and decrypt do not corrupt the ratchet`() {
        handshake()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 200).map { i ->
                pool.submit<Pair<ByteArray, EncryptedMessage>> {
                    text("c$i") to alice.crypto.encrypt(text("c$i"), "bob", 1)
                }
            }
            val sent = futures.map { it.get() }
            for ((plain, enc) in sent.shuffled(Random(5))) {
                assertContentEquals(plain, bob.crypto.decrypt("alice", 1, enc.type, enc.body))
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
