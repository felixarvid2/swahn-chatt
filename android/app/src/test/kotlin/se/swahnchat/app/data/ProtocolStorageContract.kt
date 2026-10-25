package se.swahnchat.app.data

import se.swahnchat.core.crypto.KeyIdKind
import se.swahnchat.core.crypto.LocalIdentity
import se.swahnchat.core.crypto.ProtocolStorage
import se.swahnchat.core.crypto.StoredIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What every [ProtocolStorage] must do (from `InMemoryProtocolStorage`); run against it and against the Room version. */
abstract class ProtocolStorageContract {
    abstract fun newStorage(): ProtocolStorage

    protected val s: ProtocolStorage by lazy { newStorage() }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `local identity round trips and is replaced`() {
        assertNull(s.getLocalIdentity())
        s.setLocalIdentity(LocalIdentity(bytes(1, 2, 3), 1234))
        s.setLocalIdentity(LocalIdentity(bytes(9, 8), 77))
        val id = s.getLocalIdentity()!!
        assertContentEquals(bytes(9, 8), id.identityKeyPair)
        assertEquals(77, id.registrationId)
    }

    @Test fun `one time prekeys are stored loaded counted and removed`() {
        assertNull(s.loadPreKey(1))
        s.storePreKey(1, bytes(1)); s.storePreKey(2, bytes(2)); s.storePreKey(2, bytes(22))
        assertContentEquals(bytes(22), s.loadPreKey(2))
        assertEquals(2, s.countPreKeys())
        s.removePreKey(1); s.removePreKey(99)
        assertNull(s.loadPreKey(1))
        assertEquals(1, s.countPreKeys())
    }

    @Test fun `signed prekeys are stored and listed`() {
        s.storeSignedPreKey(5, bytes(5)); s.storeSignedPreKey(3, bytes(3))
        assertContentEquals(bytes(5), s.loadSignedPreKey(5))
        assertEquals(setOf(listOf<Byte>(3), listOf<Byte>(5)), s.loadSignedPreKeys().map { it.toList() }.toSet())
        s.removeSignedPreKey(5)
        assertNull(s.loadSignedPreKey(5))
        assertEquals(1, s.loadSignedPreKeys().size)
    }

    @Test fun `kyber prekeys keep their last resort flag`() {
        s.storeKyberPreKey(1, bytes(1), lastResort = false)
        s.storeKyberPreKey(2, bytes(2), lastResort = true)
        assertContentEquals(bytes(2), s.loadKyberPreKey(2))
        val all = s.loadKyberPreKeys().associateBy { it.id }
        assertFalse(all.getValue(1).lastResort)
        assertTrue(all.getValue(2).lastResort)
        s.removeKyberPreKey(1)
        assertNull(s.loadKyberPreKey(1))
        assertEquals(1, s.loadKyberPreKeys().size)
    }

    @Test fun `a last resort use is new once and a replay afterwards`() {
        assertTrue(s.markLastResortUsed(1, 2, bytes(7, 7)))
        assertFalse(s.markLastResortUsed(1, 2, bytes(7, 7)))
        assertTrue(s.markLastResortUsed(1, 2, bytes(7, 8)))
        assertTrue(s.markLastResortUsed(1, 3, bytes(7, 7)))
        assertTrue(s.markLastResortUsed(2, 2, bytes(7, 7)))
    }

    @Test fun `sessions are stored per address and can be deleted one or by exact prefix`() {
        assertNull(s.loadSession("u1/d1"))
        s.storeSession("u1/d2", bytes(2)); s.storeSession("u1/d1", bytes(1)); s.storeSession("u2/d1", bytes(3))
        s.storeSession("u1/d1", bytes(11))
        assertContentEquals(bytes(11), s.loadSession("u1/d1"))
        s.deleteSession("u1/d1")
        assertNull(s.loadSession("u1/d1"))
        assertNotNull(s.loadSession("u1/d2"))
        s.deleteSessionsWithPrefix("u1/")
        assertNull(s.loadSession("u1/d2"))
        assertNotNull(s.loadSession("u2/d1"))
    }

    @Test fun `deleting by prefix is not a pattern match`() {
        s.storeSession("u%/d1", bytes(1)); s.storeSession("u_/d1", bytes(2)); s.storeSession("ux/d1", bytes(3)); s.storeSession("u1/d1", bytes(4))
        s.deleteSessionsWithPrefix("u%/")
        assertNull(s.loadSession("u%/d1"))
        assertNotNull(s.loadSession("u_/d1")); assertNotNull(s.loadSession("ux/d1")); assertNotNull(s.loadSession("u1/d1"))
        s.deleteSessionsWithPrefix("u_/")
        assertNull(s.loadSession("u_/d1")); assertNotNull(s.loadSession("ux/d1"))
    }

    @Test fun `remote identities are pinned by device uuid with owner verified and approved flags`() {
        assertNull(s.getIdentity("d1"))
        s.putIdentity(StoredIdentity("d1", "u1", bytes(4, 4), verified = false, approved = false))
        val first = s.getIdentity("d1")!!
        assertFalse(first.verified); assertFalse(first.approved); assertEquals("u1", first.userId); assertEquals("d1", first.deviceId)
        s.putIdentity(StoredIdentity("d1", "u1", bytes(4, 4), verified = true, approved = true))
        val id = s.getIdentity("d1")!!
        assertTrue(id.verified); assertTrue(id.approved)
        assertContentEquals(bytes(4, 4), id.identityKey)
        assertNull(s.getIdentity("d2"))
        // the owner is data, not part of the key: the same uuid for another user replaces the pin (only accept does that)
        s.putIdentity(StoredIdentity("d2", "u1", bytes(5), verified = true))
        s.putIdentity(StoredIdentity("d3", "u2", bytes(6), verified = false))
        assertEquals(setOf("d1", "d2"), s.identitiesOf("u1").map { it.deviceId }.toSet())
        assertEquals(listOf("d3"), s.identitiesOf("u2").map { it.deviceId })
        assertTrue(s.identitiesOf("nobody").isEmpty())
    }

    @Test fun `key ids start at one are consecutive and per kind`() {
        assertEquals(1, s.reserveKeyIds(KeyIdKind.PRE_KEY, 100))
        assertEquals(101, s.reserveKeyIds(KeyIdKind.PRE_KEY, 5))
        assertEquals(1, s.reserveKeyIds(KeyIdKind.KYBER_PRE_KEY, 1))
        assertEquals(1, s.reserveKeyIds(KeyIdKind.SIGNED_PRE_KEY, 1))
        assertEquals(106, s.reserveKeyIds(KeyIdKind.PRE_KEY, 1))
    }

    @Test fun `key ids wrap to one before the range would overflow`() {
        val max = 0xFFFFFF
        assertEquals(1, s.reserveKeyIds(KeyIdKind.PRE_KEY, max - 5))
        assertEquals(max - 4, s.reserveKeyIds(KeyIdKind.PRE_KEY, 5))
        assertEquals(1, s.reserveKeyIds(KeyIdKind.PRE_KEY, 10)) // would run past the maximum: wrap
        assertEquals(11, s.reserveKeyIds(KeyIdKind.PRE_KEY, 1))
    }

    @Test fun `concurrent reservations never overlap`() {
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val threads = (1..8).map {
            Thread { repeat(25) { val first = s.reserveKeyIds(KeyIdKind.KYBER_PRE_KEY, 3); for (i in 0 until 3) assertTrue(seen.add(first + i), "id ${first + i} handed out twice") } }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(8 * 25 * 3, seen.size)
    }

    @Test fun `clearAll forgets everything including counters`() {
        s.setLocalIdentity(LocalIdentity(bytes(1), 1)); s.storePreKey(1, bytes(1)); s.storeSignedPreKey(1, bytes(1))
        s.storeKyberPreKey(1, bytes(1), true); s.markLastResortUsed(1, 1, bytes(1)); s.storeSession("u/d", bytes(1))
        s.putIdentity(StoredIdentity("d", "u", bytes(1), true)); s.reserveKeyIds(KeyIdKind.PRE_KEY, 10)
        s.clearAll()
        assertNull(s.getLocalIdentity()); assertNull(s.loadPreKey(1)); assertEquals(0, s.countPreKeys())
        assertTrue(s.loadSignedPreKeys().isEmpty()); assertTrue(s.loadKyberPreKeys().isEmpty())
        assertNull(s.loadSession("u/d")); assertNull(s.getIdentity("d"))
        assertTrue(s.markLastResortUsed(1, 1, bytes(1)))
        assertEquals(1, s.reserveKeyIds(KeyIdKind.PRE_KEY, 1))
    }
}
