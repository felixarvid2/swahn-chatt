package se.familychat.core.model

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
        val json = FamilyChatJson.parseToJsonElement(
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
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":2,"type":"bogus","conversation_id":"c","message_id":"m"}""") }
        val ok = decodeJson("""{"v":2,"type":"delete","conversation_id":"c","message_id":"m","target_message_id":"t"}""")
        assertEquals(ContentType.DELETE, ok.type)
        assertEquals("t", ok.targetMessageId)
    }
}

/**
 * Older apps stay installed, so what a newer app sends must not break them. Pinned here: unknown JSON fields on a known type
 * are ignored (additive fields are safe), an unknown content *type* is refused with an IllegalArgumentException, which the
 * crypto layer turns into "could not decrypt" for that one message (see `MessageCrypto.decryptContent`): it is never applied
 * and never crashes. A new content type therefore needs a note in the docs that older apps will show a placeholder for it.
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
    fun `an unknown content type is refused without crashing, whatever else it carries`() {
        for (type in listOf("poll", "reaction", "pin", "location", "")) {
            val e = assertFailsWith<IllegalArgumentException>(type) {
                decodeJson("""{"v":2,"type":"$type","text":"x","conversation_id":"c","message_id":"m","target_message_id":"t","poll":{"q":"?"}}""")
            }
            assertTrue(e.message!!.contains("unknown content type"))
        }
    }

    @Test
    fun `a content version this app does not know is refused`() {
        assertFailsWith<IllegalArgumentException> { decodeJson("""{"v":3,"type":"text","text":"x","conversation_id":"c","message_id":"m"}""") }
    }
}

class DtoTest {
    @Test
    fun `auth response parses with unknown keys`() {
        val s = """{"access_token":"a","access_expires_at":5,"refresh_token":"r","refresh_expires_at":6,
            "user":{"id":"u","username":"anna","display_name":"Anna","avatar_attachment_id":null,"is_admin":true,
            "created_at":1,"future":1},
            "device":{"id":"d","user_id":"u","number":1,"name":"Pixel","created_at":0,"last_seen_at":0,
            "identity_key":null},"extra":{}}"""
        val r = FamilyChatJson.decodeFromString(AuthResponse.serializer(), s)
        assertEquals("anna", r.user.username)
        assertEquals(1, r.device.number)
        assertNull(r.device.identityKey)
    }

    @Test
    fun `send request omits null target and uses snake_case`() {
        val req = SendMessageRequest(
            "id1", "c1", MessageKind.MESSAGE, null, 5, emptyList(), listOf(Envelope("d1", EnvelopeType.PREKEY, "QQ==")),
        )
        val obj = FamilyChatJson.encodeToJsonElementForTest(req)
        assertTrue("target_message_id" !in obj)
        assertEquals("c1", obj["conversation_id"]!!.jsonPrimitive.content)
        assertTrue("prekey" in obj["envelopes"].toString())
    }

    @Test
    fun `mismatched devices error body`() {
        val b = FamilyChatJson.decodeFromString(
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
            FamilyChatJson.decodeFromString(Event.serializer(), """{"seq":3,"type":"$type","data":$data}""")
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
        val t = FamilyChatJson.parseToJsonElement(ClientFrame.Typing("c", "stop").toJson()).jsonObject
        assertEquals("typing", t["type"]!!.jsonPrimitive.content)
        assertEquals("stop", t["state"]!!.jsonPrimitive.content)
    }
}

private inline fun <reified T> kotlinx.serialization.json.Json.encodeToJsonElementForTest(v: T): JsonObject =
    encodeToJsonElement(kotlinx.serialization.serializer<T>(), v).jsonObject
