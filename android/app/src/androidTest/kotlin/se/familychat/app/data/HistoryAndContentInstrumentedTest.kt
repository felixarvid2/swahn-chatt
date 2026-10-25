package se.familychat.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import se.familychat.app.data.history.HistoryFormat
import se.familychat.app.data.history.HistoryException
import se.familychat.core.model.Content
import se.familychat.core.model.ContentCodec
import se.familychat.core.model.ContentType
import se.familychat.core.model.FamilyChatJson
import se.familychat.core.model.Padding
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 0.4.0 pieces that only break under R8 or on a real runtime: Argon2id (BouncyCastle) feeding Tink's streaming AEAD for the
 * history file with the production parameters, and the new encrypted content types.
 */
@RunWith(AndroidJUnit4::class)
class HistoryAndContentInstrumentedTest {
    private val pass = "correct horse battery staple"

    private fun seal(header: HistoryFormat.Header, plain: ByteArray): ByteArray {
        val key = HistoryFormat.deriveKey(pass, header)
        val out = ByteArrayOutputStream()
        out.write(header.encode())
        HistoryFormat.streaming(key).newEncryptingStream(out, header.encode()).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(file: ByteArray, passphrase: String): ByteArray {
        val input = ByteArrayInputStream(file)
        val (header, raw) = HistoryFormat.readHeader(input)
        val key = HistoryFormat.deriveKey(passphrase, header)
        return Channels.newInputStream(HistoryFormat.streaming(key).newDecryptingChannel(Channels.newChannel(input), raw)).readBytes()
    }

    @Test fun productionParametersRoundTripAndRejectWrongPassphraseAndTampering() {
        val plain = Random(3).nextBytes(2_300_000) // spans three 1 MiB segments
        val file = seal(HistoryFormat.newHeader(), plain)
        assertContentEquals(plain, open(file, pass))
        assertFailsWith<Exception> { open(file, "wrong passphrase!!") }
        val flippedBody = file.copyOf().also { it[file.size / 2] = (it[file.size / 2].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { open(flippedBody, pass) }
        val flippedSalt = file.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { open(flippedSalt, pass) }
        assertFailsWith<Exception> { open(file.copyOf(file.size - 100), pass) }
        val wrongVersion = file.copyOf().also { it[4] = 9 }
        assertFailsWith<HistoryException.UnsupportedVersion> { HistoryFormat.readHeader(ByteArrayInputStream(wrongVersion)) }
    }

    @Test fun reactionTimerAndUnknownContentSurviveTheCodec() {
        val reaction = Content(
            type = ContentType.REACTION, conversationId = "c", messageId = "m", targetMessageId = "t", emoji = "👍", expiresIn = 60,
        )
        val back = ContentCodec.decode(ContentCodec.encode(reaction))
        assertEquals("👍", back.emoji)
        assertEquals("t", back.targetMessageId)
        assertEquals(60L, back.expiresIn)
        val timer = ContentCodec.decode(ContentCodec.encode(Content(type = ContentType.TIMER, conversationId = "c", messageId = "m", seconds = 3600)))
        assertEquals(3600L, timer.seconds)
        // a future type is accepted when bound, and not mistaken for a known one
        val future = """{"v":2,"type":"hologram","conversation_id":"c","message_id":"m","extra":{"a":1}}"""
        val decoded = ContentCodec.decode(Padding.pad(future.toByteArray()))
        assertEquals("hologram", decoded.type)
        assertTrue(!decoded.isKnownType)
        assertNull(decoded.emoji)
        assertTrue("expires_in" !in FamilyChatJson.encodeToString(Content.serializer(), Content(text = "x")))
    }
}
