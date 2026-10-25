package se.swahnchat.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaddingTest {
    @Test
    fun `padded length is a positive multiple of 160 and round-trips`() {
        for (n in listOf(0, 1, 2, 100, 158, 159, 160, 161, 319, 320, 1000, 5000)) {
            val data = Random(n).nextBytes(n)
            val padded = Padding.pad(data)
            assertEquals(0, padded.size % 160, "size for $n")
            assertTrue(padded.size > n)
            assertTrue(padded.size - n <= 160)
            assertContentEquals(data, Padding.unpad(padded))
        }
    }

    @Test
    fun `plaintext ending in zeros and 0x80 bytes survives`() {
        val data = byteArrayOf(1, 0, 0, 0x80.toByte(), 0x80.toByte(), 0, 0)
        assertContentEquals(data, Padding.unpad(Padding.pad(data)))
    }

    @Test
    fun `invalid padding is rejected`() {
        assertFailsWith<InvalidPaddingException> { Padding.unpad(ByteArray(0)) }
        assertFailsWith<InvalidPaddingException> { Padding.unpad(ByteArray(100)) } // not a multiple
        assertFailsWith<InvalidPaddingException> { Padding.unpad(ByteArray(160)) } // all zeros, no marker
        assertFailsWith<InvalidPaddingException> { Padding.unpad(ByteArray(160) { 7 }) } // no marker
        val good = Padding.pad("hello".toByteArray())
        val bad = good.copyOf().also { it[it.size - 1] = 1 } // garbage after the marker
        assertFailsWith<InvalidPaddingException> { Padding.unpad(bad) }
        val noMarker = good.copyOf().also { it[5] = 0 } // marker destroyed
        assertFailsWith<InvalidPaddingException> { Padding.unpad(noMarker) }
        // Two blocks where all of the second block is padding: not something pad() produces.
        val excess = ByteArray(320).also { it[3] = 0x80.toByte() }
        assertFailsWith<InvalidPaddingException> { Padding.unpad(excess) }
    }
}

class ContentTest {
    @Test
    fun `content round trip with attachments`() {
        val c = Content(
            type = ContentType.TEXT, text = "Hej åäö 👋", replyTo = "r1", conversationId = "c1", messageId = "m1",
            attachments = listOf(
                AttachmentRef(
                    "a1", "a2V5", "ab12", 1234, "image/jpeg", "x.jpg", AttachmentKind.IMAGE, 640, 480, null, "dGh1bWI=",
                ),
            ),
        )
        val bytes = ContentCodec.encode(c)
        assertEquals(0, bytes.size % 160)
        assertEquals(c, ContentCodec.decode(bytes))
    }

    @Test
    fun `wire format uses snake_case and includes the version`() {
        val json = SwahnChatJson.parseToJsonElement(
            Padding.unpad(ContentCodec.encode(Content(text = "x", replyTo = "m1", conversationId = "c1", messageId = "m2"))).decodeToString(),
        ).jsonObject
        assertEquals("2", json["v"]!!.jsonPrimitive.content)
        assertEquals("m1", json["reply_to"]!!.jsonPrimitive.content)
        assertEquals("c1", json["conversation_id"]!!.jsonPrimitive.content)
        assertEquals("m2", json["message_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `bad content is rejected`() {
        assertFailsWith<InvalidPaddingException> { ContentCodec.decode(ByteArray(7)) }
        assertFailsWith<Exception> { ContentCodec.decode(Padding.pad("not json".toByteArray())) }
        assertFailsWith<IllegalArgumentException> {
            ContentCodec.decode(Padding.pad("""{"v":99,"type":"text"}""".toByteArray()))
        }
    }

    private fun decodeJson(json: String) = ContentCodec.decode(Padding.pad(json.toByteArray()))

    @Test
    fun `content must carry its binding fields`() {
        // old format (v1) and v2 without conversation / message ids
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":1,"type":"text","text":"x"}""") }
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"text","text":"x"}""") }
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"text","text":"x","conversation_id":"c"}""") }
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"text","text":"x","message_id":"m"}""") }
        // edits and deletes need a target; a plain message must not have one
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"edit","text":"x","conversation_id":"c","message_id":"m"}""") }
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"delete","conversation_id":"c","message_id":"m"}""") }
        assertFailsWith<IllegalArgumentException> {
            decodeJson("""{"v":2,"type":"text","text":"x","conversation_id":"c","message_id":"m","target_message_id":"t"}""")
        }
        // a type this version does not know is not an error (a newer app's content), see ContentForwardCompatibilityTest
        assertTrue(!decodeJson("""{"v":2,"type":"bogus","conversation_id":"c","message_id":"m"}""").isKnownType)
        val ok = decodeJson("""{"v":2,"type":"delete","conversation_id":"c","message_id":"m","target_message_id":"t"}""")
        assertEquals(ContentType.DELETE, ok.type)
        assertEquals("t", ok.targetMessageId)
    }
}

/**
 * Older apps stay installed and newer ones will come: what a newer app sends must not break this one. Pinned here: unknown
 * JSON fields on a known type are ignored (additive fields are safe), and content of a type this version does not know is
 * accepted by the codec when it is well-formed and bound (version 2, conversation and message id), so the receiver can consume
 * it without showing "could not decrypt". Malformed content is still refused. A new content type still shows a placeholder
 * on apps older than 0.4, which cannot be fixed afterwards.
 */
class ContentForwardCompatibilityTest {
    private fun decodeJson(json: String) = ContentCodec.decode(Padding.pad(json.toByteArray()))

    @Test
    fun `unknown fields next to a known type are ignored`() {
        val c = decodeJson("""{"v":2,"type":"text","text":"hej","conversation_id":"c","message_id":"m","future_field":{"a":[1,2]},"mentions":["u1"]}""")
        assertEquals("hej", c.text)
        assertEquals(ContentType.TEXT, c.type)
    }

    @Test
    fun `well-formed bound content of an unknown type is accepted and marked unknown`() {
        for (type in listOf("hologram", "pin", "geofence", "sticker")) {
            val c = decodeJson("""{"v":2,"type":"$type","text":"x","conversation_id":"c","message_id":"m","target_message_id":"t","poll":{"q":"?"}}""")
            assertEquals(type, c.type)
            assertTrue(!c.isKnownType, type)
            assertEquals("c", c.conversationId)
            assertEquals("m", c.messageId)
        }
    }

    @Test
    fun `unknown types are still refused when unbound or malformed`() {
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"pin","message_id":"m"}""") }
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"pin","conversation_id":"c"}""") }
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"pin","conversation_id":"","message_id":"m"}""") }
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"","conversation_id":"c","message_id":"m"}""") }
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":3,"type":"pin","conversation_id":"c","message_id":"m"}""") }
        assertFailsWith<Exception> { decodeJson("""{"v":2,"type":"pin","conversation_id":"c","message_id":"m""") }
        assertFailsWith<Exception> { decodeJson("""{"v":2,"type":7,"conversation_id":"c","message_id":"m"}""") }
        assertFailsWith<InvalidPaddingException> { ContentCodec.decode(ByteArray(7)) }
    }

    @Test
    fun `a content version this app does not know is refused`() {
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":3,"type":"text","text":"x","conversation_id":"c","message_id":"m"}""") }
    }

    @Test
    fun `new optional fields are omitted from the wire when unset so older apps see the content they know`() {
        val json = SwahnChatJson.parseToJsonElement(
            Padding.unpad(ContentCodec.encode(Content(text = "x", conversationId = "c", messageId = "m"))).decodeToString(),
        ).jsonObject
        assertEquals(setOf("v", "type", "text", "attachments", "conversation_id", "message_id"), json.keys)
    }
}

class ReactionAndTimerContentTest {
    private fun decodeJson(json: String) = ContentCodec.decode(Padding.pad(json.toByteArray()))
    private fun reaction(emoji: String?, extra: String = "") =
        """{"v":2,"type":"reaction","emoji":${emoji?.let { "\"$it\"" }},"remove":false,"conversation_id":"c","message_id":"m","target_message_id":"t"$extra}"""

    @Test
    fun `a reaction round-trips`() {
        val c = Content(type = ContentType.REACTION, emoji = "\uD83D\uDC4D", remove = true, conversationId = "c", messageId = "m", targetMessageId = "t")
        assertEquals(c, ContentCodec.decode(ContentCodec.encode(c)))
    }

    @Test
    fun `a reaction needs a target and one valid emoji and carries no text`() {
        assertEquals("\u2764\uFE0F", decodeJson(reaction("\u2764\uFE0F")).emoji)
        assertFailsWith<IllegalArgumentException> { decodeJson(reaction(null)) }
        assertFailsWith<IllegalArgumentException> { decodeJson(reaction("ab")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(reaction("\uD83D\uDC4D\uD83D\uDC4D")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(reaction("\uD83D\uDC4D", ""","text":"hi"""")) }
        assertFailsWith<IllegalArgumentException> {
            decodeJson("""{"v":2,"type":"reaction","emoji":"\uD83D\uDC4D","conversation_id":"c","message_id":"m"}""")
        }
    }

    @Test
    fun `a timer needs a valid number of seconds and no target`() {
        fun timer(seconds: String, extra: String = "") = """{"v":2,"type":"timer","seconds":$seconds,"conversation_id":"c","message_id":"m"$extra}"""
        assertEquals(0L, decodeJson(timer("0")).seconds)
        assertEquals(86_400L, decodeJson(timer("86400")).seconds)
        assertFailsWith<IllegalArgumentException> { decodeJson(timer("299")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(timer("31536001")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(timer("-5")) }
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"timer","conversation_id":"c","message_id":"m"}""") }
        assertFailsWith<IllegalArgumentException> { decodeJson(timer("300", ""","target_message_id":"t"""")) }
    }

    @Test
    fun `expires_in and forwarded travel on text content`() {
        val c = decodeJson("""{"v":2,"type":"text","text":"x","expires_in":3600,"forwarded":true,"conversation_id":"c","message_id":"m"}""")
        assertEquals(3600L, c.expiresIn)
        assertEquals(true, c.forwarded)
    }
}

class EmojiTest {
    @Test
    fun `single emoji are valid`() {
        val valid = listOf(
            "\uD83D\uDC4D", // thumbs up
            "\uD83D\uDC4D\uD83C\uDFFD", // with skin tone
            "\u2764\uFE0F", // red heart
            "\u2764", // heart without selector
            "\uD83C\uDDF8\uD83C\uDDEA", // flag SE
            "1\uFE0F\u20E3", // keycap 1
            "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F", "\uD83D\uDD25",
            "\uD83D\uDC68\u200D\uD83D\uDCBB", // man technologist (ZWJ)
        )
        for (e in valid) assertTrue(Emoji.isValid(e), e)
    }

    @Test
    fun `text, several emoji, control characters and oversize input are refused`() {
        val invalid = listOf(
            "", " ", "a", "ab", "1", "\n", "\uD83D\uDC4D ", " \uD83D\uDC4D", "\uD83D\uDC4D\uD83D\uDC4D", "\uD83D\uDC4Da",
            "\u200D", "\uD83D\uDC4D\u200D", "\uD83C\uDDF8", "\uD83C\uDDF8\uD83C\uDDEA\uD83C\uDDF8", "\uFE0F", "\u202E\uD83D\uDC4D",
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", // family: 18 bytes, over the 16-byte limit
            "\uD83D", // lone surrogate
        )
        for (e in invalid) assertTrue(!Emoji.isValid(e), "should be refused: ${e.map { it.code.toString(16) }}")
        assertTrue(!Emoji.isValid(null))
    }
}

class DtoTest {
    @Test
    fun `a call signal round-trips and is a known type`() {
        val offer = callContent(CallSignal(CALL_ID, CallAction.OFFER, video = true, sdp = SDP))
        val ice = callContent(CallSignal(CALL_ID, CallAction.ICE, candidates = listOf(IceCandidate("0", 0, "candidate:1 1 udp 1 10.0.0.1 9 typ host"))))
        val hangup = callContent(CallSignal(CALL_ID, CallAction.HANGUP, reason = "done"))
        for (c in listOf(offer, ice, hangup)) {
            val back = ContentCodec.decode(ContentCodec.encode(c))
            assertEquals(c, back)
            assertTrue(back.isKnownType)
        }
        val wire = SwahnChatJson.parseToJsonElement(SwahnChatJson.encodeToString(Content.serializer(), ice)).jsonObject["call"]!!.jsonObject
        assertEquals("ice", wire["action"]!!.jsonPrimitive.content)
        assertTrue("sdp_m_line_index" in wire["candidates"].toString())
    }

    @Test
    fun `a call signal carries sent_at on the wire and still decodes without it`() {
        val offer = callContent(CallSignal(CALL_ID, CallAction.OFFER, sdp = SDP, sentAt = 1_700_000_000_123))
        val back = ContentCodec.decode(ContentCodec.encode(offer))
        assertEquals(1_700_000_000_123, back.call!!.sentAt)
        val wire = SwahnChatJson.parseToJsonElement(SwahnChatJson.encodeToString(Content.serializer(), offer)).jsonObject["call"]!!.jsonObject
        assertEquals("1700000000123", wire["sent_at"]!!.jsonPrimitive.content)
        // a signal from a sender that does not set it decodes with null (the receiver then drops an offer)
        val old = ContentCodec.decode(ContentCodec.encode(callContent(CallSignal(CALL_ID, CallAction.OFFER, sdp = SDP))))
        assertNull(old.call!!.sentAt)
    }

    @Test
    fun `a call signal needs a call, a uuid call_id, a known action and no target or content`() {
        fun fails(c: Content) = assertFailsWith<IllegalArgumentException> { ContentCodec.decode(ContentCodec.encode(c)) }
        val ok = callContent(CallSignal(CALL_ID, CallAction.HANGUP))
        ContentCodec.decode(ContentCodec.encode(ok))
        fails(ok.copy(call = null))
        fails(ok.copy(call = ok.call!!.copy(callId = "not-a-uuid")))
        fails(ok.copy(call = ok.call!!.copy(action = "ring")))
        fails(ok.copy(targetMessageId = "t"))
        fails(ok.copy(text = "hi"))
        fails(ok.copy(attachments = listOf(AttachmentRef("a", "k", "h", 1, "image/png"))))
    }

    @Test
    fun `an offer or answer needs a bounded sdp with a DTLS fingerprint`() {
        fun fails(sdp: String?, action: String = CallAction.OFFER) =
            assertFailsWith<IllegalArgumentException> { ContentCodec.decode(ContentCodec.encode(callContent(CallSignal(CALL_ID, action, sdp = sdp)))) }
        for (action in listOf(CallAction.OFFER, CallAction.ANSWER)) {
            ContentCodec.decode(ContentCodec.encode(callContent(CallSignal(CALL_ID, action, sdp = SDP))))
            fails(null, action)
            fails("   ", action)
            fails("v=0\r\na=setup:actpass\r\n", action)
        }
        val padding = "x".repeat(Content.MAX_SDP_BYTES)
        fails(SDP + padding)
        ContentCodec.decode(ContentCodec.encode(callContent(CallSignal(CALL_ID, CallAction.OFFER, sdp = SDP + "x".repeat(Content.MAX_SDP_BYTES - SDP.length)))))
    }

    @Test
    fun `an ice signal needs one to fifty candidates of at most 1 KiB`() {
        fun ice(vararg c: IceCandidate) = callContent(CallSignal(CALL_ID, CallAction.ICE, candidates = c.toList()))
        fun cand(size: Int = 20) = IceCandidate("0", 0, "c".repeat(size))
        ContentCodec.decode(ContentCodec.encode(ice(cand())))
        ContentCodec.decode(ContentCodec.encode(ice(*Array(Content.MAX_CANDIDATES) { cand() })))
        ContentCodec.decode(ContentCodec.encode(ice(cand(Content.MAX_CANDIDATE_BYTES))))
        assertFailsWith<IllegalArgumentException> { ContentCodec.decode(ContentCodec.encode(ice())) }
        assertFailsWith<IllegalArgumentException> { ContentCodec.decode(ContentCodec.encode(ice(*Array(Content.MAX_CANDIDATES + 1) { cand() }))) }
        assertFailsWith<IllegalArgumentException> { ContentCodec.decode(ContentCodec.encode(ice(cand(Content.MAX_CANDIDATE_BYTES + 1)))) }
        assertFailsWith<IllegalArgumentException> {
            ContentCodec.decode(ContentCodec.encode(callContent(CallSignal(CALL_ID, CallAction.ICE, candidates = null))))
        }
    }

    @Test
    fun `turn credentials parse with and without a secret`() {
        val full = SwahnChatJson.decodeFromString(TurnCredentials.serializer(), """{"urls":["turn:h:3478"],"username":"u","credential":"c","ttl":600}""")
        assertEquals(TurnCredentials(listOf("turn:h:3478"), "u", "c", 600), full)
        val stun = SwahnChatJson.decodeFromString(TurnCredentials.serializer(), """{"urls":["stun:h:3478"],"username":null,"credential":null,"ttl":0}""")
        assertNull(stun.username)
        assertNull(stun.credential)
    }

    @Test
    fun `auth response parses with unknown keys`() {
        val s = """{"access_token":"a","access_expires_at":5,"refresh_token":"r","refresh_expires_at":6,
            "user":{"id":"u","username":"anna","display_name":"Anna","avatar_attachment_id":null,"is_admin":true,
            "created_at":1,"future":1},
            "device":{"id":"d","user_id":"u","number":1,"name":"Pixel","created_at":0,"last_seen_at":0,
            "identity_key":null},"extra":{}}"""
        val r = SwahnChatJson.decodeFromString(AuthResponse.serializer(), s)
        assertEquals("anna", r.user.username)
        assertEquals(1, r.device.number)
        assertNull(r.device.identityKey)
    }

    @Test
    fun `send request omits null target and uses snake_case`() {
        val req = SendMessageRequest(
            "id1", "c1", MessageKind.MESSAGE, null, 5, emptyList(), listOf(Envelope("d1", EnvelopeType.PREKEY, "QQ==")),
        )
        val obj = SwahnChatJson.encodeToJsonElementForTest(req)
        assertTrue("target_message_id" !in obj)
        assertEquals("c1", obj["conversation_id"]!!.jsonPrimitive.content)
        assertTrue("prekey" in obj["envelopes"].toString())
    }

    @Test
    fun `mismatched devices error body`() {
        val b = SwahnChatJson.decodeFromString(
            ErrorBody.serializer(),
            """{"code":"mismatched_devices","message":"m","missing_devices":[{"user_id":"u","device_id":"d"}],
               "extra_devices":[]}""",
        )
        assertEquals(DeviceRef("u", "d"), b.missingDevices.single())
        assertTrue(b.extraDevices.isEmpty())
    }

    @Test
    fun `events parse per type and never throw`() {
        fun ev(type: String, data: String) =
            SwahnChatJson.decodeFromString(Event.serializer(), """{"seq":3,"type":"$type","data":$data}""")
        val msg = """{"id":"m","conversation_id":"c","sender_user_id":"u","sender_device_id":"d","kind":"edit",
            "target_message_id":"t","client_ts":1,"server_ts":2,"seq":9,"attachment_ids":[],"deleted":false,
            "envelope":{"type":"whisper","body":"QQ=="}}"""
        val m = assertIs<ParsedEvent.MessageReceived>(parseEvent(ev("message", msg)))
        assertEquals("t", m.message.targetMessageId)
        assertEquals("whisper", m.message.envelope!!.type)
        val r = assertIs<ParsedEvent.Receipt>(
            parseEvent(ev("receipt", """{"conversation_id":"c","user_id":"u","type":"read","up_to_seq":4}""")),
        )
        assertEquals(4L, r.receipt.upToSeq)
        assertIs<ParsedEvent.ConversationRemoved>(parseEvent(ev("conversation_removed", """{"conversation_id":"c"}""")))
        assertIs<ParsedEvent.DevicesChanged>(parseEvent(ev("devices_changed", """{"user_id":"u"}""")))
        assertIs<ParsedEvent.Unknown>(parseEvent(ev("brand_new", "{}")))
        assertIs<ParsedEvent.Unknown>(parseEvent(ev("message", """{"bogus":1}""")))
    }

    @Test
    fun `websocket frames`() {
        assertEquals(ServerFrame.Hello(12), ServerFrame.parse("""{"type":"hello","cursor":12}"""))
        assertEquals(ServerFrame.Sync(13), ServerFrame.parse("""{"type":"sync","cursor":13}"""))
        assertEquals(ServerFrame.Pong, ServerFrame.parse("""{"type":"pong"}"""))
        assertEquals(
            ServerFrame.Typing("c", "u", TypingState.START),
            ServerFrame.parse("""{"type":"typing","conversation_id":"c","user_id":"u","state":"start"}"""),
        )
        assertIs<ServerFrame.Unknown>(ServerFrame.parse("""{"type":"zzz"}"""))
        assertNull(ServerFrame.parse("garbage"))
        assertEquals("""{"type":"ping"}""", ClientFrame.Ping.toJson())
        val t = SwahnChatJson.parseToJsonElement(ClientFrame.Typing("c", "stop").toJson()).jsonObject
        assertEquals("typing", t["type"]!!.jsonPrimitive.content)
        assertEquals("stop", t["state"]!!.jsonPrimitive.content)
    }
}

private inline fun <reified T> kotlinx.serialization.json.Json.encodeToJsonElementForTest(v: T): JsonObject =
    encodeToJsonElement(kotlinx.serialization.serializer<T>(), v).jsonObject

private const val CALL_ID = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b"
private const val SDP = "v=0\r\na=fingerprint:sha-256 AA:BB\r\na=setup:actpass\r\n"

private fun callContent(signal: CallSignal) = Content(type = ContentType.CALL, conversationId = "c", messageId = "m", call = signal)

class MentionPollContentTest {
    private fun decodeJson(json: String) = ContentCodec.decode(Padding.pad(json.toByteArray()))
    private val bind = """"conversation_id":"c","message_id":"m""""

    @Test
    fun `mentions round-trip on text and are omitted when unset`() {
        val c = Content(text = "hej @Anna", mentions = listOf("u1", "u2"), conversationId = "c", messageId = "m")
        assertEquals(c, ContentCodec.decode(ContentCodec.encode(c)))
        val plain = SwahnChatJson.parseToJsonElement(
            Padding.unpad(ContentCodec.encode(Content(text = "x", conversationId = "c", messageId = "m"))).decodeToString(),
        ).jsonObject
        assertTrue("mentions" !in plain.keys && "poll" !in plain.keys && "option_ids" !in plain.keys && "close" !in plain.keys)
    }

    @Test
    fun `more than 50 mentions or an empty id are refused`() {
        val fifty = (1..50).joinToString(",") { "\"u$it\"" }
        assertEquals(50, decodeJson("""{"v":2,"type":"text","text":"x","mentions":[$fifty],$bind}""").mentions?.size)
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"text","text":"x","mentions":[$fifty,"u51"],$bind}""") }
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"text","text":"x","mentions":[""],$bind}""") }
    }

    private fun poll(question: String = "Pizza?", options: String = """[{"id":0,"text":"Ja"},{"id":1,"text":"Nej"}]""", extra: String = "") =
        """{"v":2,"type":"poll","text":"$question","poll":{"options":$options,"multiple":false},$bind$extra}"""

    @Test
    fun `a poll round-trips and is a known type`() {
        val c = Content(
            type = ContentType.POLL, text = "Pizza?", poll = PollSpec(listOf(PollOption(0, "Ja"), PollOption(1, "Nej")), multiple = true),
            conversationId = "c", messageId = "m",
        )
        val back = ContentCodec.decode(ContentCodec.encode(c))
        assertEquals(c, back)
        assertTrue(back.isKnownType)
        assertEquals(true, decodeJson(poll()).poll?.options?.size == 2)
    }

    @Test
    fun `a poll needs a question, two to ten distinct options and no target`() {
        assertFailsWith<IllegalArgumentException> { decodeJson(poll(question = "")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(poll(question = "x".repeat(201))) }
        decodeJson(poll(question = "x".repeat(200)))
        assertFailsWith<IllegalArgumentException> { decodeJson(poll(options = """[{"id":0,"text":"Ja"}]""")) }
        val eleven = (0..10).joinToString(",", "[", "]") { """{"id":$it,"text":"a$it"}""" }
        assertFailsWith<IllegalArgumentException> { decodeJson(poll(options = eleven)) }
        val ten = (0..9).joinToString(",", "[", "]") { """{"id":$it,"text":"a$it"}""" }
        assertEquals(10, decodeJson(poll(options = ten)).poll?.options?.size)
        assertFailsWith<IllegalArgumentException> { decodeJson(poll(options = """[{"id":0,"text":"a"},{"id":0,"text":"b"}]""")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(poll(options = """[{"id":0,"text":"a"},{"id":10,"text":"b"}]""")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(poll(options = """[{"id":0,"text":"a"},{"id":1,"text":"${"y".repeat(101)}"}]""")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(poll(options = """[{"id":0,"text":"a"},{"id":1,"text":" "}]""")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(poll(extra = ""","target_message_id":"t"""")) }
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"poll","text":"x",$bind}""") }
    }

    private fun vote(body: String) = """{"v":2,"type":"poll_vote",$body,$bind}"""

    @Test
    fun `a poll vote names a target and valid options, or closes the poll`() {
        assertEquals(listOf(1, 3), decodeJson(vote(""""target_message_id":"p","option_ids":[1,3]""")).optionIds)
        assertEquals(emptyList(), decodeJson(vote(""""target_message_id":"p","option_ids":[]""")).optionIds) // withdrawn
        assertEquals(true, decodeJson(vote(""""target_message_id":"p","close":true""")).close)
        assertFailsWith<IllegalArgumentException> { decodeJson(vote(""""option_ids":[1]""")) } // no target
        assertFailsWith<IllegalArgumentException> { decodeJson(vote(""""target_message_id":"p"""")) } // neither options nor close
        assertFailsWith<IllegalArgumentException> { decodeJson(vote(""""target_message_id":"p","option_ids":[1,1]""")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(vote(""""target_message_id":"p","option_ids":[-1]""")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(vote(""""target_message_id":"p","option_ids":[10]""")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(vote(""""target_message_id":"p","option_ids":[1],"close":true""")) }
        assertFailsWith<IllegalArgumentException> { decodeJson(vote(""""target_message_id":"p","option_ids":[1],"text":"hi"""")) }
    }

    @Test
    fun `unknown types are still tolerated after the new ones were added`() {
        val c = decodeJson("""{"v":2,"type":"hologram",$bind,"x":1}""")
        assertTrue(!c.isKnownType)
        assertTrue(ContentType.POLL in ContentType.KNOWN && ContentType.POLL_VOTE in ContentType.KNOWN)
    }

    private fun location(body: String = """"lat":59.3293,"lon":18.0686,"accuracy_m":12.5""", extra: String = "") =
        """{"v":2,"type":"location","location":{$body},$bind$extra}"""

    @Test
    fun `a location round-trips, is a known type and omits an unset label`() {
        val c = Content(
            type = ContentType.LOCATION, location = LocationSpec(59.3293, 18.0686, 12.5, "Hemma"), conversationId = "c", messageId = "m",
        )
        val back = ContentCodec.decode(ContentCodec.encode(c))
        assertEquals(c, back)
        assertTrue(back.isKnownType && ContentType.LOCATION in ContentType.KNOWN)
        val json = SwahnChatJson.encodeToString(Content.serializer(), c.copy(location = LocationSpec(1.0, 2.0, 3.0)))
        assertTrue("\"accuracy_m\":3.0" in json && "label" !in json, json)
        assertEquals(null, decodeJson(location()).location?.label)
    }

    @Test
    fun `a location needs valid ranges, a short label and nothing else`() {
        decodeJson(location("\"lat\":90,\"lon\":-180,\"accuracy_m\":0"))
        decodeJson(location("\"lat\":-90,\"lon\":180,\"accuracy_m\":100000"))
        decodeJson(location("\"lat\":1,\"lon\":2,\"accuracy_m\":3,\"label\":\"${"x".repeat(100)}\""))
        fun bad(body: String, extra: String = "") = assertFailsWith<IllegalArgumentException>(body) { decodeJson(location(body, extra)) }
        bad("\"lat\":90.01,\"lon\":0,\"accuracy_m\":1")
        bad("\"lat\":-90.01,\"lon\":0,\"accuracy_m\":1")
        bad("\"lat\":0,\"lon\":180.5,\"accuracy_m\":1")
        bad("\"lat\":0,\"lon\":-181,\"accuracy_m\":1")
        bad("\"lat\":0,\"lon\":0,\"accuracy_m\":-1")
        bad("\"lat\":0,\"lon\":0,\"accuracy_m\":100000.5")
        bad("\"lat\":1,\"lon\":2,\"accuracy_m\":3,\"label\":\"${"x".repeat(101)}\"")
        bad("\"lat\":1,\"lon\":2,\"accuracy_m\":3", ""","target_message_id":"t"""")
        bad("\"lat\":1,\"lon\":2,\"accuracy_m\":3", ""","text":"hi"""")
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"location",$bind}""") }
        assertTrue(!LocationSpec(Double.NaN, 0.0, 1.0).isValid && !LocationSpec(0.0, Double.POSITIVE_INFINITY, 1.0).isValid)
        assertTrue(!LocationSpec(0.0, 0.0, Double.NaN).isValid)
    }
}
