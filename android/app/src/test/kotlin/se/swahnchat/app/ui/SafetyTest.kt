package se.swahnchat.app.ui

import se.swahnchat.app.data.computeSafety
import se.swahnchat.app.data.db.DeviceEntity
import se.swahnchat.app.data.db.IdentityRow
import se.swahnchat.app.ui.model.SafetyState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SafetyTest {
    private fun device(id: String, user: String) = DeviceEntity(id, user, 1, id, 0, 0, null)
    private fun pin(device: String, user: String, verified: Boolean = true, approved: Boolean = true) = IdentityRow(device, user, verified, approved)

    private val bob1 = device("b1", "bob")
    private val bob2 = device("b2", "bob")
    private val cy1 = device("c1", "cy")
    private val mine = device("m1", "me")

    @Test fun `all devices of all other members verified gives the shield`() {
        val s = computeSafety("me", setOf("bob", "cy"), listOf(bob1, bob2, cy1, mine), listOf(pin("b1", "bob"), pin("b2", "bob"), pin("c1", "cy")), emptySet())
        assertEquals(SafetyState.VERIFIED, s.state)
        assertNull(s.reviewUserId)
    }

    @Test fun `one unverified device, or a member without devices, gives nothing`() {
        val devices = listOf(bob1, bob2, mine)
        assertEquals(SafetyState.NONE, computeSafety("me", setOf("bob"), devices, listOf(pin("b1", "bob")), emptySet()).state)
        assertEquals(SafetyState.NONE, computeSafety("me", setOf("bob"), devices, listOf(pin("b1", "bob"), pin("b2", "bob", verified = false)), emptySet()).state)
        assertEquals(SafetyState.NONE, computeSafety("me", setOf("bob", "cy"), listOf(bob1), listOf(pin("b1", "bob")), emptySet()).state, "cy has no known device")
        assertEquals(SafetyState.NONE, computeSafety("me", setOf("bob"), emptyList(), emptyList(), emptySet()).state)
        assertEquals(SafetyState.NONE, computeSafety("me", emptySet(), listOf(mine), emptyList(), emptySet()).state, "nobody else in the conversation")
    }

    @Test fun `a pin for another owner does not count as verified`() {
        assertEquals(SafetyState.NONE, computeSafety("me", setOf("bob"), listOf(bob1), listOf(pin("b1", "cy")), emptySet()).state)
    }

    @Test fun `a changed identity gives the warning for that person, even next to verified devices`() {
        val s = computeSafety("me", setOf("bob", "cy"), listOf(bob1, cy1), listOf(pin("b1", "bob"), pin("c1", "cy")), setOf("c1"))
        assertEquals(SafetyState.WARNING, s.state)
        assertEquals("cy", s.reviewUserId)
    }

    @Test fun `a new device that is not approved yet gives the warning`() {
        val s = computeSafety("me", setOf("bob"), listOf(bob1, bob2), listOf(pin("b1", "bob"), pin("b2", "bob", verified = false, approved = false)), emptySet())
        assertEquals(SafetyState.WARNING, s.state)
        assertEquals("bob", s.reviewUserId)
    }

    @Test fun `a pending change on the own account warns in every conversation, other members first`() {
        val own = computeSafety("me", setOf("bob"), listOf(bob1, mine), listOf(pin("b1", "bob")), setOf("m1"))
        assertEquals(SafetyState.WARNING, own.state); assertEquals("me", own.reviewUserId)
        val both = computeSafety("me", setOf("bob"), listOf(bob1, mine), listOf(pin("b1", "bob")), setOf("m1", "b1"))
        assertEquals("bob", both.reviewUserId)
    }

    @Test fun `a conflict for a device nobody lists is ignored`() {
        assertEquals(SafetyState.VERIFIED, computeSafety("me", setOf("bob"), listOf(bob1), listOf(pin("b1", "bob")), setOf("gone")).state)
    }
}
