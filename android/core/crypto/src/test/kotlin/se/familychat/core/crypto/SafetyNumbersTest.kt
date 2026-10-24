package se.familychat.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SafetyNumbersTest {
    private val alice = TestDevice("alice", 1).also { it.keys.createIdentity() }
    private val bob = TestDevice("bob", 2).also { it.keys.createIdentity() }

    private fun num(local: TestDevice, remote: TestDevice) = SafetyNumbers.compute(
        local.userId, local.number, local.keys.identityPublicKey(),
        remote.userId, remote.number, remote.keys.identityPublicKey(),
    )

    @Test
    fun `both sides compute the same 60 digits`() {
        val a = num(alice, bob)
        val b = num(bob, alice)
        assertEquals(60, a.digits.length)
        assertTrue(a.digits.all { it.isDigit() })
        assertEquals(a.digits, b.digits)
        assertEquals(12, a.groups.size)
        assertEquals(71, a.displayText.length)
    }

    @Test
    fun `differs when a key or a device differs`() {
        val base = num(alice, bob)
        val carol = TestDevice("bob", 2).also { it.keys.createIdentity() } // same address, different key
        assertNotEquals(base.digits, num(alice, carol).digits)
        val bob3 = TestDevice("bob", 3).also { it.keys.createIdentity() }
        assertNotEquals(base.digits, num(alice, bob3).digits)
        // same keys but a different address binds differently
        val swapped = SafetyNumbers.compute(
            "alice", 1, alice.keys.identityPublicKey(), "bob", 9, bob.keys.identityPublicKey(),
        )
        assertNotEquals(base.digits, swapped.digits)
    }

    @Test
    fun `scannable payloads match across sides and reject others`() {
        val a = num(alice, bob)
        val b = num(bob, alice)
        assertTrue(a.matchesScanned(b.scannable))
        assertTrue(b.matchesScanned(a.scannable))
        val other = num(alice, TestDevice("eve", 1).also { it.keys.createIdentity() })
        assertFalse(a.matchesScanned(other.scannable))
        assertFalse(a.matchesScanned(byteArrayOf(1, 2, 3)))
    }
}
