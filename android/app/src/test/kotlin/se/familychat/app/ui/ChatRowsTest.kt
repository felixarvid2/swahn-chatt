package se.familychat.app.ui

import se.familychat.app.ui.model.ChatRow
import se.familychat.app.ui.model.ChatRowContext
import se.familychat.app.ui.model.DeliveryUi
import se.familychat.app.ui.model.SystemKind
import se.familychat.app.ui.model.TransferUi
import se.familychat.app.ui.model.UndecryptableUi
import se.familychat.app.ui.model.buildChatRows
import se.familychat.core.client.DecryptError
import se.familychat.core.client.DownloadState
import se.familychat.core.client.LocalAttachment
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.StoredMessage
import se.familychat.core.client.SystemEvent
import se.familychat.core.model.User
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRowsTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 5, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val users = mapOf("me" to User("me", "anna", "Anna"), "bob" to User("bob", "bob", "Bob", avatarAttachmentId = "av"), "cy" to User("cy", "cy", "Cy"))

    private fun m(
        id: String, from: String = "bob", seq: Long? = null, ts: Long = at(10, 9), text: String? = "hej", status: MessageStatus? = null,
        attachments: List<LocalAttachment> = emptyList(), replyTo: String? = null,
    ): StoredMessage {
        val outgoing = from == "me"
        return StoredMessage(
            id = id, conversationId = "c", senderUserId = from, senderDeviceId = "d-$from", outgoing = outgoing,
            status = status ?: if (outgoing) MessageStatus.SENT else MessageStatus.RECEIVED, seq = seq, clientTs = ts, serverTs = seq?.let { ts },
            text = text, replyTo = replyTo, attachments = attachments,
        )
    }

    private fun ctx(group: Boolean = false, validated: Map<String, Boolean> = emptyMap(), downloading: Map<String, Float?> = emptyMap(), referenced: Map<String, StoredMessage> = emptyMap()) =
        ChatRowContext("me", group, users, referenced, downloading, validated, zone = zone)

    private fun attachment(id: String, kind: String = "image", state: DownloadState = DownloadState.NONE, path: String? = null) =
        LocalAttachment(id, "k", "s", 100, "image/jpeg", "f.jpg", kind, 10, 10, null, null, path, state)

    @Test fun `a date separator starts every new day`() {
        val rows = buildChatRows(listOf(m("a", ts = at(10, 9)), m("b", ts = at(10, 23)), m("c", ts = at(11, 1))), ctx())
        assertEquals(listOf("date", "msg", "msg", "date", "msg"), rows.map { when (it) { is ChatRow.DateHeader -> "date"; is ChatRow.Message -> "msg"; else -> "other" } })
        assertEquals(at(10, 0), (rows[0] as ChatRow.DateHeader).dayStart)
    }

    @Test fun `the first message of a run by one sender is marked`() {
        val rows = buildChatRows(listOf(m("a", "bob"), m("b", "bob"), m("c", "me"), m("d", "bob")), ctx(group = true)).filterIsInstance<ChatRow.Message>()
        assertEquals(listOf(true, false, true, true), rows.map { it.firstOfRun })
    }

    @Test fun `groups show sender names for incoming messages only`() {
        val group = buildChatRows(listOf(m("a", "bob"), m("b", "me")), ctx(group = true)).filterIsInstance<ChatRow.Message>()
        assertEquals("Bob", group[0].message.senderName)
        assertEquals("av", group[0].message.senderAvatar!!.attachmentId)
        assertNull(group[1].message.senderName)
        val direct = buildChatRows(listOf(m("a", "bob")), ctx(group = false)).filterIsInstance<ChatRow.Message>()
        assertNull(direct[0].message.senderName)
    }

    @Test fun `delivery states map to the five indicators`() {
        fun d(s: MessageStatus) = buildChatRows(listOf(m("x", "me", status = s)), ctx()).filterIsInstance<ChatRow.Message>().single().message.delivery
        assertEquals(DeliveryUi.PENDING, d(MessageStatus.PENDING))
        assertEquals(DeliveryUi.SENT, d(MessageStatus.SENT))
        assertEquals(DeliveryUi.DELIVERED, d(MessageStatus.DELIVERED))
        assertEquals(DeliveryUi.READ, d(MessageStatus.READ))
        assertEquals(DeliveryUi.FAILED, d(MessageStatus.FAILED))
        assertEquals(DeliveryUi.NONE, buildChatRows(listOf(m("y", "bob")), ctx()).filterIsInstance<ChatRow.Message>().single().message.delivery)
    }

    @Test fun `failed messages can be retried and only sent own messages can be edited`() {
        val rows = buildChatRows(
            listOf(m("f", "me", status = MessageStatus.FAILED), m("s", "me"), m("in", "bob"), m("att", "me", text = null, attachments = listOf(attachment("a")))),
            ctx(),
        ).filterIsInstance<ChatRow.Message>().associateBy { it.message.id }
        assertTrue(rows.getValue("f").message.failed)
        assertFalse(rows.getValue("f").message.canEdit)
        assertTrue(rows.getValue("s").message.canEdit)
        assertFalse(rows.getValue("in").message.canEdit)
        assertFalse(rows.getValue("in").message.canDelete)
        assertFalse(rows.getValue("att").message.canEdit, "nothing to edit without text")
        assertTrue(rows.getValue("att").message.canDelete)
    }

    @Test fun `deleted and undecryptable messages are flagged`() {
        val rows = buildChatRows(
            listOf(
                m("del", "bob", text = null).copy(deleted = true),
                m("c", "bob", text = null).copy(decryptError = DecryptError.CORRUPT),
                m("i", "bob", text = null).copy(decryptError = DecryptError.UNTRUSTED_IDENTITY),
                m("n", "bob", text = null).copy(decryptError = DecryptError.NO_SESSION),
            ),
            ctx(),
        ).filterIsInstance<ChatRow.Message>().map { it.message }
        assertTrue(rows[0].deleted)
        assertEquals(UndecryptableUi.CORRUPT, rows[1].undecryptable)
        assertEquals(UndecryptableUi.RETRYABLE_IDENTITY, rows[2].undecryptable)
        assertEquals(UndecryptableUi.OTHER, rows[3].undecryptable)
    }

    @Test fun `edited messages keep their label`() {
        val msg = buildChatRows(listOf(m("e", "me").copy(edited = true, editedAt = 5)), ctx()).filterIsInstance<ChatRow.Message>().single().message
        assertTrue(msg.edited)
    }

    @Test fun `system notices become their own rows with the person's name`() {
        val rows = buildChatRows(
            listOf(
                m("sys-1", "", text = null).copy(senderUserId = "", systemEvent = SystemEvent.NewDevice("cy", "dev-2", "Pixel")),
                m("sys-2", "", text = null).copy(senderUserId = "", systemEvent = SystemEvent.IdentityChanged("bob", "dev-1")),
                m("sys-3", "", text = null).copy(senderUserId = "", systemEvent = SystemEvent.NewDevice("me", "dev-3", "Tablet")),
                m("sys-4", "", text = null).copy(senderUserId = "", systemEvent = SystemEvent.NewDevice("bob", "dev-4", "Phone", needsApproval = true)),
                m("sys-5", "", text = null).copy(senderUserId = "", systemEvent = SystemEvent.MemberAdded("cy")),
                m("sys-6", "", text = null).copy(senderUserId = "", systemEvent = SystemEvent.MemberRemoved("bob")),
            ),
            ctx(),
        ).filterIsInstance<ChatRow.System>()
        assertEquals(SystemKind.NEW_DEVICE, rows[0].kind); assertEquals("Cy", rows[0].personName); assertEquals("Pixel", rows[0].deviceName)
        assertEquals(SystemKind.IDENTITY_CHANGED, rows[1].kind); assertEquals("Bob", rows[1].personName)
        assertFalse(rows[0].own)
        assertEquals(SystemKind.NEW_DEVICE, rows[2].kind); assertTrue(rows[2].own, "the own account's devices are marked")
        assertEquals(SystemKind.NEW_DEVICE_BLOCKED, rows[3].kind)
        assertEquals(SystemKind.MEMBER_ADDED, rows[4].kind); assertEquals("Cy", rows[4].personName)
        assertEquals(SystemKind.MEMBER_REMOVED, rows[5].kind)
    }

    @Test fun `a reply finds its target in the window or in the referenced messages`() {
        val target = m("t", "bob", text = "original")
        val inWindow = buildChatRows(listOf(target, m("r", "me", replyTo = "t")), ctx()).filterIsInstance<ChatRow.Message>().last().message.reply!!
        assertEquals("Bob", inWindow.senderName); assertEquals("original", inWindow.text)
        val outside = buildChatRows(listOf(m("r", "me", replyTo = "t")), ctx(referenced = mapOf("t" to m("t", "me", text = "mine")))).filterIsInstance<ChatRow.Message>().single().message.reply!!
        assertNull(outside.senderName, "a reply to an own message is shown as 'You'")
        assertNull(buildChatRows(listOf(m("r", "me", replyTo = "gone")), ctx()).filterIsInstance<ChatRow.Message>().single().message.reply)
        val deleted = buildChatRows(listOf(m("t", "bob", text = null).copy(deleted = true), m("r", "me", replyTo = "t")), ctx()).filterIsInstance<ChatRow.Message>().last().message.reply!!
        assertTrue(deleted.deleted); assertNull(deleted.text)
    }

    @Test fun `received files are shown only after they were validated`() {
        fun transfer(a: LocalAttachment, c: ChatRowContext) =
            buildChatRows(listOf(m("x", "bob", attachments = listOf(a))), c).filterIsInstance<ChatRow.Message>().single().message.attachments.single().transfer
        assertIs<TransferUi.Remote>(transfer(attachment("a"), ctx()))
        assertIs<TransferUi.Failed>(transfer(attachment("a", state = DownloadState.FAILED), ctx()))
        val done = attachment("a", state = DownloadState.DONE, path = "/f/a")
        assertIs<TransferUi.Downloading>(transfer(done, ctx()), "not rendered while the check is running")
        assertEquals("/f/a", (transfer(done, ctx(validated = mapOf("a" to true))) as TransferUi.Ready).path)
        assertIs<TransferUi.Invalid>(transfer(done, ctx(validated = mapOf("a" to false))))
        assertEquals(0.5f, (transfer(attachment("a"), ctx(downloading = mapOf("a" to 0.5f))) as TransferUi.Downloading).progress)
    }

    @Test fun `own pending attachments show as uploading and thumbnails decode from base64`() {
        val a = attachment("local-1", state = DownloadState.DONE, path = "/src").copy(thumbnail = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
        val msg = buildChatRows(listOf(m("p", "me", status = MessageStatus.PENDING, attachments = listOf(a))), ctx(validated = mapOf("local-1" to true)))
            .filterIsInstance<ChatRow.Message>().single().message
        assertIs<TransferUi.Uploading>(msg.attachments.single().transfer)
        assertEquals(listOf<Byte>(1, 2, 3), msg.attachments.single().thumbnail!!.toList())
        val broken = attachment("b").copy(thumbnail = "%%%not base64%%%")
        assertNull(buildChatRows(listOf(m("q", "bob", attachments = listOf(broken))), ctx()).filterIsInstance<ChatRow.Message>().single().message.attachments.single().thumbnail)
    }
}
