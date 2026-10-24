package se.familychat.app.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import se.familychat.app.platform.push.Distributor
import se.familychat.app.platform.push.PushStatus
import se.familychat.app.ui.components.PlaybackUi
import se.familychat.app.ui.model.AttachmentKindUi
import se.familychat.app.ui.model.AttachmentUi
import se.familychat.app.ui.model.AvatarUi
import se.familychat.app.ui.model.ChatRow
import se.familychat.app.ui.model.ConversationItemUi
import se.familychat.app.ui.model.DeliveryUi
import se.familychat.app.ui.model.MemberUi
import se.familychat.app.ui.model.MessageUi
import se.familychat.app.ui.model.PreviewKind
import se.familychat.app.ui.model.ReplyUi
import se.familychat.app.ui.model.SystemKind
import se.familychat.app.ui.model.TransferUi
import se.familychat.app.ui.model.UndecryptableUi
import se.familychat.app.ui.model.UserUi
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime

/** Fake state for the screenshot tests. Fixed times, names and pictures so the images only change when the UI does. */
object Fixtures {
    val zone: ZoneId = ZoneId.of("UTC")
    fun at(month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    /** "Now" of the screenshots: Saturday 24 October 2026, 16:30. */
    val NOW = at(10, 24, 16, 30)

    private fun jpeg(w: Int, h: Int, top: Int, bottom: Int, sun: Boolean): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        if (sun) { p.color = Color.rgb(255, 214, 120); c.drawCircle(w * 0.75f, h * 0.3f, h * 0.14f, p) }
        p.color = Color.argb(200, 30, 70, 60)
        c.drawOval(-w * 0.2f, h * 0.65f, w * 0.7f, h * 1.4f, p)
        p.color = Color.argb(200, 20, 50, 50)
        c.drawOval(w * 0.3f, h * 0.75f, w * 1.3f, h * 1.5f, p)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
    }

    val photoThumb: ByteArray by lazy { jpeg(160, 120, Color.rgb(120, 180, 230), Color.rgb(230, 200, 160), true) }
    val videoThumb: ByteArray by lazy { jpeg(160, 90, Color.rgb(60, 80, 140), Color.rgb(240, 140, 100), false) }

    val anna = UserUi("me", "Anna Lindqvist", "anna", AvatarUi("Anna Lindqvist"))
    val bob = UserUi("bob", "Bertil Ek", "bertil", AvatarUi("Bertil Ek"))
    val cia = UserUi("cia", "Cecilia Ek", "cecilia", AvatarUi("Cecilia Ek"))
    val david = UserUi("david", "David Ek", "david", AvatarUi("David Ek"))
    val emma = UserUi("emma", "Emma Berg", "emma", AvatarUi("Emma Berg"))
    val people = listOf(bob, cia, david, emma)

    // --- conversation list ----------------------------------------------------------------------------------------------

    val conversationItems = listOf(
        ConversationItemUi("g1", "Familjen Ek", true, AvatarUi("Familjen Ek"), PreviewKind.TEXT, "Vi grillar på lördag, vem kommer?", "Cecilia", false, DeliveryUi.NONE, at(10, 24, 15, 58), 3),
        ConversationItemUi("d1", "Bertil Ek", false, bob.avatar, PreviewKind.VOICE, null, null, false, DeliveryUi.NONE, at(10, 24, 12, 41), 1),
        ConversationItemUi(
            "d2", "Cecilia Ek", false, cia.avatar, PreviewKind.TEXT, "Perfekt, vi ses då!", null, true, DeliveryUi.READ, at(10, 23, 21, 5), 0,
            draft = "Jag kan hämta på fredag, passar det?",
        ),
        ConversationItemUi("d3", "Emma Berg", false, emma.avatar, PreviewKind.PHOTO, null, null, true, DeliveryUi.DELIVERED, at(10, 22, 9, 15), 0, typing = false),
        ConversationItemUi("g2", "Semesterplanering", true, AvatarUi("Semesterplanering"), PreviewKind.FILE, "boende-kroatien.pdf", "David", false, DeliveryUi.NONE, at(10, 19, 18, 2), 0),
        ConversationItemUi("d4", "David Ek", false, david.avatar, PreviewKind.TEXT, "Skickas inte än …", null, true, DeliveryUi.PENDING, at(10, 14, 8, 30), 0, typing = false),
        ConversationItemUi("d5", "Farmor", false, AvatarUi("Farmor"), PreviewKind.DELETED, null, null, false, DeliveryUi.NONE, at(9, 30, 10, 0), 0),
        ConversationItemUi("d6", "Mormor", false, AvatarUi("Mormor"), PreviewKind.TEXT, "", null, false, DeliveryUi.NONE, at(2, 3, 10, 0), 0, typing = true),
    )

    /** The same list with a pinned, a muted and some archived conversations (one archived conversation is muted). */
    val conversationItemsOrganised: List<ConversationItemUi> = conversationItems.map {
        when (it.id) {
            "d2" -> it.copy(pinned = true)
            "d1" -> it.copy(muted = true)
            "g2" -> it.copy(archived = true, unread = 2, muted = true)
            "d5", "d6" -> it.copy(archived = true)
            "g1" -> it.copy(pinned = true)
            else -> it
        }
    }

    // --- search -----------------------------------------------------------------------------------------------------------

    private fun hit(id: String, conversation: String, time: Long, sender: String?, text: String, terms: String) =
        se.familychat.app.ui.model.SearchHitUi(id, conversation, time, sender, se.familychat.app.data.Snippets.of(text, se.familychat.app.data.SearchQuery.terms(terms)))

    val searchGroups = listOf(
        se.familychat.app.ui.model.SearchGroupUi(
            "g1", "Familjen Ek", AvatarUi("Familjen Ek"),
            listOf(
                hit("s1", "g1", at(10, 24, 15, 58), "Cecilia Ek", "Vi grillar på lördag, vem kommer? Jag tar med middag till alla och bakar en kaka", "middag"),
                hit("s2", "g1", at(10, 21, 18, 2), null, "Kan någon ta med kolsyra till middagen på söndag?", "middag"),
            ),
        ),
        se.familychat.app.ui.model.SearchGroupUi(
            "d2", "Cecilia Ek", cia.avatar,
            listOf(hit("s3", "d2", at(9, 30, 20, 11), "Cecilia Ek", "Middag hos oss på fredag, passar det?", "middag")),
        ),
    )

    // --- conversation ---------------------------------------------------------------------------------------------------

    private fun msg(
        id: String, from: UserUi?, text: String?, time: Long, group: Boolean = true, delivery: DeliveryUi = DeliveryUi.NONE,
        attachments: List<AttachmentUi> = emptyList(), reply: ReplyUi? = null, edited: Boolean = false, deleted: Boolean = false,
        undecryptable: UndecryptableUi? = null, failed: Boolean = false,
    ): ChatRow.Message {
        val outgoing = from == null
        return ChatRow.Message(
            MessageUi(
                id = id, outgoing = outgoing, senderName = if (group && !outgoing) from!!.name else null,
                senderAvatar = if (group && !outgoing) from!!.avatar else null, text = text, reply = reply, attachments = attachments,
                time = time, delivery = if (outgoing) delivery else DeliveryUi.NONE, edited = edited, deleted = deleted,
                undecryptable = undecryptable, failed = failed, canEdit = outgoing && !failed && text != null, canDelete = outgoing,
            ),
            firstOfRun = true,
        )
    }

    private fun photo(id: String, transfer: TransferUi = TransferUi.Remote) =
        AttachmentUi(id, AttachmentKindUi.IMAGE, "image/jpeg", "IMG_2041.jpg", 1_800_000, 1600, 1200, null, photoThumb, transfer)

    private fun runStart(rows: List<ChatRow>): List<ChatRow> {
        // mark first-of-run like buildChatRows does
        var previous: Boolean? = null
        var prevSender: String? = null
        return rows.map { r ->
            if (r is ChatRow.Message) {
                val sender = r.message.senderName ?: "me"
                val first = sender != prevSender
                prevSender = sender
                previous = first
                r.copy(firstOfRun = first)
            } else {
                prevSender = null
                r
            }
        }
    }

    val groupRows: List<ChatRow> = runStart(
        listOf(
            ChatRow.DateHeader(at(10, 23, 0)),
            msg("1", bob, "Hej alla! Har någon sett kartan till stugan?", at(10, 23, 19, 12)),
            msg("2", null, "Den ligger i hallen, bredvid nycklarna.", at(10, 23, 19, 14), delivery = DeliveryUi.READ),
            msg("3", cia, "Tack! Här är bilden från i somras 😊", at(10, 23, 19, 20)),
            msg("4", cia, null, at(10, 23, 19, 20), attachments = listOf(photo("p1"))),
            ChatRow.DateHeader(at(10, 24, 0)),
            ChatRow.System("s1", SystemKind.NEW_DEVICE, "david", "David Ek", "Pixel 9", at(10, 24, 8, 2)),
            msg(
                "5", null, "Jag tar med mat. Är det ok?", at(10, 24, 9, 40), delivery = DeliveryUi.DELIVERED,
                reply = ReplyUi("3", "Cecilia Ek", "Tack! Här är bilden från i somras 😊", null, false), edited = true,
            ),
            msg(
                "6", david, null, at(10, 24, 11, 5),
                attachments = listOf(
                    AttachmentUi("v1", AttachmentKindUi.VOICE, "audio/mp4", "voice.m4a", 48_000, null, null, 23_000, null, TransferUi.Ready("/x")),
                ),
            ),
            msg(
                "7", bob, null, at(10, 24, 11, 20),
                attachments = listOf(AttachmentUi("v2", AttachmentKindUi.VIDEO, "video/mp4", "film.mp4", 9_400_000, 1280, 720, 42_000, videoThumb, TransferUi.Ready("/y"))),
            ),
            msg("8", cia, null, at(10, 24, 12, 3), attachments = listOf(AttachmentUi("f1", AttachmentKindUi.FILE, "application/pdf", "boende-kroatien.pdf", 482_000, null, null, null, null, TransferUi.Remote))),
            msg("9", david, null, at(10, 24, 12, 30), undecryptable = UndecryptableUi.CORRUPT),
            msg("10", bob, null, at(10, 24, 13, 0), deleted = true),
            ChatRow.System("s2", SystemKind.IDENTITY_CHANGED, "emma", "Emma Berg", null, at(10, 24, 14, 0)),
            msg("11", null, "Hoppas den här kommer fram …", at(10, 24, 15, 0), delivery = DeliveryUi.PENDING),
            msg("12", null, "Det här gick inte att skicka", at(10, 24, 15, 1), failed = true, delivery = DeliveryUi.FAILED),
            msg("13", cia, "Vi grillar på lördag, vem kommer?", at(10, 24, 15, 58)),
        ),
    )

    val directRows: List<ChatRow> = runStart(
        listOf(
            ChatRow.DateHeader(at(10, 24, 0)),
            msg("a", bob, "Ska vi ses vid fyra?", at(10, 24, 14, 30), group = false),
            msg("b", null, "Gärna! Jag tar med kaffe.", at(10, 24, 14, 32), group = false, delivery = DeliveryUi.READ),
            msg("c", null, null, at(10, 24, 14, 33), group = false, delivery = DeliveryUi.SENT, attachments = listOf(photo("p2", TransferUi.Downloading(0.4f)))),
            msg("d", bob, "Fint foto! Vilken utsikt. Mer här: https://exempel.se/utsikt eller ring 070-123 45 67", at(10, 24, 14, 40), group = false, undecryptable = null),
        ),
    )

    /** The group conversation as it opens with six unread messages: the divider sits above the first of them (oldest first). */
    val groupRowsUnread: List<ChatRow> = groupRows.flatMap { if (it is ChatRow.Message && it.message.id == "6") listOf(ChatRow.UnreadDivider, it) else listOf(it) }

    /** [rows] (oldest first) as a fully loaded page stream, newest first: what the conversation screen reads. */
    fun pages(rows: List<ChatRow>): PagingData<ChatRow> {
        val done = LoadState.NotLoading(endOfPaginationReached = true)
        return PagingData.from(rows.asReversed(), LoadStates(refresh = LoadState.NotLoading(false), prepend = done, append = done))
    }

    val idle = PlaybackUi()
    val playing = PlaybackUi("v1", playing = true, positionMs = 9_000, durationMs = 23_000)

    // --- groups, settings -----------------------------------------------------------------------------------------------

    val members = listOf(
        MemberUi("me", "Anna Lindqvist", "anna", anna.avatar, isAdmin = true, isMe = true),
        MemberUi("bob", "Bertil Ek", "bertil", bob.avatar, isAdmin = true, isMe = false),
        MemberUi("cia", "Cecilia Ek", "cecilia", cia.avatar, isAdmin = false, isMe = false),
        MemberUi("david", "David Ek", "david", david.avatar, isAdmin = false, isMe = false),
    )

    val distributors = listOf(Distributor("io.heckel.ntfy", "ntfy"))
    val pushActive = PushStatus.ACTIVE
}
