package se.familychat.app.ui

import se.familychat.app.ui.model.AvatarUi
import se.familychat.app.ui.model.MemberUi
import se.familychat.app.ui.screens.buildMessageInfo
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.ReceiptTimes
import se.familychat.core.client.StoredMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageInfoTest {
    private fun member(id: String, me: Boolean = false) = MemberUi(id, id.replaceFirstChar { it.uppercase() }, id, AvatarUi(id), false, me)
    private val me = member("me", me = true)
    private val bob = member("bob")
    private val cy = member("cy")

    private fun own(
        deliveredTo: Set<String> = emptySet(),
        readBy: Set<String> = emptySet(),
        serverTs: Long? = 1_000,
        status: MessageStatus = MessageStatus.SENT,
    ) = StoredMessage(
        "m", "c", "me", "d", true, status, seq = serverTs?.let { 1 }, clientTs = 900, serverTs = serverTs, text = "hej", replyTo = null,
        deliveredTo = deliveredTo, readBy = readBy,
    )

    @Test fun `a direct chat lists the other person with the times of their receipts`() {
        val info = buildMessageInfo(own(setOf("bob"), setOf("bob")), listOf(ReceiptTimes("m", "bob", 2_000, 3_000)), listOf(me, bob), isGroup = false)
        val r = info.recipients.single()
        assertEquals("bob", r.member.userId)
        assertTrue(r.delivered); assertEquals(2_000L, r.deliveredAt)
        assertTrue(r.read); assertEquals(3_000L, r.readAt)
        assertEquals(1_000L, info.sentAt)
        assertFalse(info.isGroup)
    }

    @Test fun `a group lists every other member, with its own state for each`() {
        val info = buildMessageInfo(
            own(deliveredTo = setOf("bob", "cy"), readBy = setOf("bob")),
            listOf(ReceiptTimes("m", "bob", 2_000, 3_000), ReceiptTimes("m", "cy", 2_500, null)), listOf(me, bob, cy), isGroup = true,
        )
        assertEquals(listOf("bob", "cy"), info.recipients.map { it.member.userId })
        val (b, c) = info.recipients
        assertTrue(b.read); assertEquals(3_000L, b.readAt)
        assertTrue(c.delivered); assertEquals(2_500L, c.deliveredAt)
        assertFalse(c.read); assertNull(c.readAt)
        assertTrue(info.isGroup)
    }

    @Test fun `no receipt at all shows nothing delivered and nothing read`() {
        val r = buildMessageInfo(own(), emptyList(), listOf(me, bob, cy), true).recipients
        assertEquals(2, r.size)
        assertTrue(r.none { it.delivered || it.read || it.deliveredAt != null || it.readAt != null })
    }

    @Test fun `old messages known as read but without times keep the status and have no times`() {
        val r = buildMessageInfo(own(setOf("bob"), setOf("bob"), status = MessageStatus.READ), emptyList(), listOf(me, bob), false).recipients.single()
        assertTrue(r.delivered); assertTrue(r.read)
        assertNull(r.deliveredAt); assertNull(r.readAt)
    }

    @Test fun `a silent read leaves a delivered time and no read time`() {
        val r = buildMessageInfo(own(setOf("bob")), listOf(ReceiptTimes("m", "bob", 2_000, null)), listOf(me, bob), false).recipients.single()
        assertTrue(r.delivered); assertEquals(2_000L, r.deliveredAt)
        assertFalse(r.read); assertNull(r.readAt)
    }

    @Test fun `reading implies delivery and a read time implies reading`() {
        val onlyRead = buildMessageInfo(own(readBy = setOf("bob")), emptyList(), listOf(me, bob), false).recipients.single()
        assertTrue(onlyRead.delivered); assertTrue(onlyRead.read)
        val timeOnly = buildMessageInfo(own(), listOf(ReceiptTimes("m", "bob", null, 5_000)), listOf(me, bob), false).recipients.single()
        assertTrue(timeOnly.read); assertEquals(5_000L, timeOnly.readAt)
    }

    @Test fun `yourself is never listed, a missing message closes the screen`() {
        assertTrue(buildMessageInfo(own(), emptyList(), listOf(me), false).recipients.isEmpty())
        val gone = buildMessageInfo(null, emptyList(), listOf(me, bob), false)
        assertTrue(gone.gone); assertTrue(gone.loaded)
    }

    @Test fun `a message the server has not stored yet has no sent time`() {
        assertNull(buildMessageInfo(own(serverTs = null), emptyList(), listOf(me, bob), false).sentAt)
    }
}
