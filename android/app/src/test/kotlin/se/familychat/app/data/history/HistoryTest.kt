package se.familychat.app.data.history

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.familychat.core.model.FamilyChatJson
import se.familychat.core.client.DownloadState
import se.familychat.core.client.MessageStatus
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The format and the behaviour of the history export and import (docs/ANDROID_SETUP.md, "Export and import of the history"). */
class HistoryTest {
    private val phone = Phone().fill()

    // --- the format -------------------------------------------------------------------------------------------------------

    @Test fun `the file starts with the magic, version 1 and the Argon2id parameters, 64 MiB, 3 passes, 1 lane, 16 random salt bytes`() {
        val a = phone.export(kdf = KdfParams.DEFAULT)
        val b = phone.export(kdf = KdfParams.DEFAULT)
        assertContentEquals("FCHX".toByteArray(), a.copyOfRange(0, 4))
        assertEquals(1, a[4].toInt())
        val (header, raw) = HistoryFormat.readHeader(ByteArrayInputStream(a))
        assertEquals(31, raw.size)
        assertEquals(KdfParams(65_536, 3, 1), header.kdf)
        assertEquals(16, header.salt.size)
        assertContentEquals(raw, a.copyOfRange(0, 31))
        assertNotEquals(header.salt.toList(), HistoryFormat.readHeader(ByteArrayInputStream(b)).first.salt.toList(), "a new salt for every file")
        // and the real thing round-trips with the real parameters
        val fresh = phone.newPhone()
        assertEquals(4, fresh.import(a).imported)
    }

    @Test fun `the header is the associated data - the right key does not open the ciphertext under another header`() {
        val file = phone.export()
        val (header, raw) = HistoryFormat.readHeader(ByteArrayInputStream(file))
        val key = HistoryFormat.deriveKey(PASS, header)
        val body = file.copyOfRange(31, file.size)
        // same key, same ciphertext, the header's associated data changed in one bit
        val other = raw.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }
        assertFailsWith<java.io.IOException> { HistoryFormat.streaming(key).newDecryptingStream(ByteArrayInputStream(body), other).readBytes() }
        // control: with the real header it opens
        assertTrue(HistoryFormat.streaming(key).newDecryptingStream(ByteArrayInputStream(body), raw).readBytes().isNotEmpty())
    }

    @Test fun `the ciphertext shows nothing of the content`() {
        val file = phone.export(includeMedia = true)
        for (needle in listOf("Hej från Bob", "Svar", "Se bilden", "Familjen", "FCHX-no", "owner_user_id", "familychat-history")) {
            assertFalse(contains(file, needle.toByteArray()), needle)
        }
    }

    // --- round trip -------------------------------------------------------------------------------------------------------

    @Test fun `text and metadata make the round trip into a phone that has the conversations`() = runBlocking {
        val file = phone.export()
        val fresh = phone.newPhone()
        val result = fresh.import(file)
        assertEquals(ImportResult(imported = 4, alreadyHere = 0, unknownConversation = 0, invalid = 0, mediaFiles = 0), result)
        val m1 = fresh.store.getMessage("m1")!!
        assertEquals("Hej från Bob åäö 👋", m1.text)
        assertEquals("bob", m1.senderUserId)
        assertEquals(1L, m1.seq)
        assertEquals(1001L, m1.clientTs)
        assertEquals(2001L, m1.serverTs)
        assertTrue(m1.imported)
        assertFalse(m1.outgoing)
        assertEquals(MessageStatus.RECEIVED, m1.status)
        assertNull(m1.senderDeviceId, "no device ids travel")
        val m2 = fresh.store.getMessage("m2")!!
        assertTrue(m2.outgoing, "own messages are recognised from the sender, on this account")
        assertEquals(MessageStatus.SENT, m2.status, "never pending: nothing is sent again")
        assertEquals("m1", m2.replyTo)
        assertTrue(m2.edited); assertEquals(3000L, m2.editedAt)
        assertTrue(fresh.store.getMessage("m3")!!.forwarded)
        assertEquals("Hej familjen", fresh.store.getMessage("g1")!!.text)
        // the reaction that stands comes along, the one that was taken back does not
        assertEquals(listOf("me" to "👍"), fresh.store.getReactions("m1").map { it.userId to it.emoji })
        // the order is the original one
        assertEquals(listOf("m1", "m2", "m3"), fresh.store.getMessages("c1", 100).filter { !it.isSystem }.map { it.id })
    }

    @Test fun `nothing is sent, nothing is changed in keys, cursor or read marks, and no notices appear`() = runBlocking {
        val fresh = phone.newPhone()
        fresh.store.setSyncCursor(77)
        fresh.store.setMyReadSeq("c1", 9)
        fresh.import(phone.export())
        assertTrue(fresh.store.getOutbox().isEmpty())
        assertEquals(77L, fresh.store.getSyncCursor())
        assertEquals(9L, fresh.store.getConversation("c1")!!.myReadSeq)
        assertTrue(fresh.store.getMessages("c1", 100).none { it.isSystem })
        assertEquals(account(), fresh.store.getAccount())
    }

    private fun account() = runBlocking { phone.store.getAccount() }

    @Test fun `messages that disappear, failed and unsent ones, deleted ones, notices and undecryptable ones stay out`() = runBlocking {
        val fresh = phone.newPhone()
        fresh.import(phone.export())
        for (id in listOf("m-expiring", "m-deleted", "m-pending", "m-failed", "m-error", "sys1")) assertNull(fresh.store.getMessage(id), id)
        val manifest = FamilyChatJson.parseToJsonElement(String(openArchive(phone.export())["manifest.json"]!!)).jsonObject
        assertEquals(3, manifest.getValue("left_out").jsonPrimitive.int, "expiring, pending and failed are counted as left out")
        assertEquals(4, manifest.getValue("messages").jsonPrimitive.int)
        assertEquals("me", manifest.getValue("owner_user_id").jsonPrimitive.content)
        assertFalse(manifest.getValue("includes_media").jsonPrimitive.boolean)
    }

    @Test fun `media files travel when asked for, under a safe name, and own the new location`() = runBlocking {
        val fresh = phone.newPhone()
        val result = fresh.import(phone.export(includeMedia = true))
        assertEquals(1, result.mediaFiles)
        val m3 = fresh.store.getMessage("m3")!!
        val photo = m3.attachments[0]
        assertEquals(DownloadState.DONE, photo.downloadState)
        val file = File(photo.localPath!!)
        assertContentEquals(phone.photo.readBytes(), file.readBytes())
        assertTrue(file.canonicalPath.startsWith(fresh.media.canonicalPath + File.separator), "inside the attachment directory the engine owns")
        assertFalse(photo.name!!.contains('/'), photo.name) // "../../etc/passwd.jpg" became a plain file name
        assertTrue(photo.name!!.endsWith("passwd.jpg"), photo.name)
        assertEquals("image/jpeg", photo.mime); assertEquals(640, photo.width); assertEquals("dGh1bWI=", photo.thumbnail)
        // no key, no checksum, no server id: it cannot be fetched again
        assertNull(photo.key); assertNull(photo.sha256); assertNotEquals(Secrets.SERVER_ATTACHMENT_ID, photo.id)
        // the attachment that was never downloaded has no file to put in
        val pdf = m3.attachments[1]
        assertNull(pdf.localPath); assertEquals(DownloadState.NONE, pdf.downloadState)
        assertEquals("inte-hämtad.pdf", pdf.name)
    }

    @Test fun `without media the attachments keep their details but no file`() = runBlocking {
        val fresh = phone.newPhone()
        assertEquals(0, fresh.import(phone.export(includeMedia = false)).mediaFiles)
        val photo = fresh.store.getMessage("m3")!!.attachments[0]
        assertNull(photo.localPath)
        assertEquals(640, photo.width)
        assertTrue(fresh.media.walkTopDown().none { it.isFile && it != fresh.photo })
    }

    @Test fun `importing twice adds nothing the second time, and removed messages stay removed`() = runBlocking {
        val file = phone.export(includeMedia = true)
        val fresh = phone.newPhone()
        fresh.import(file)
        val second = fresh.import(file)
        assertEquals(0, second.imported)
        assertEquals(4, second.alreadyHere)
        assertEquals(0, second.mediaFiles)
        assertEquals(1, fresh.store.getReactions("m1").size)
        assertEquals(1, fresh.media.listFiles()!!.filter { it.isDirectory }.sumOf { it.listFiles()!!.size }, "the second run's media was cleaned up")
        // "delete for me" is respected
        val third = phone.newPhone()
        third.store.deleteMessageLocally("m1", "c1", 5)
        val result = third.import(file)
        assertEquals(1, result.alreadyHere)
        assertNull(third.store.getMessage("m1"))
    }

    @Test fun `conversations that this phone does not have are skipped, never created`() = runBlocking {
        val fresh = phone.newPhone()
        fresh.store.removeConversation("c2")
        val result = fresh.import(phone.export())
        assertEquals(1, result.unknownConversation)
        assertEquals(3, result.imported)
        assertNull(fresh.store.getConversation("c2"))
        assertNull(fresh.store.getMessage("g1"))
    }

    // --- passphrase ---------------------------------------------------------------------------------------------------------

    @Test fun `a wrong passphrase imports nothing and leaves no files`() {
        val file = phone.export(includeMedia = true)
        val fresh = phone.newPhone()
        assertFailsWith<HistoryException.WrongPassphraseOrDamaged> { fresh.import(file, passphrase = "correct horse battery stapler") }
        assertEquals(0, fresh.messageCount("c1")); assertEquals(0, fresh.messageCount("c2"))
        assertTrue(fresh.leftovers().isEmpty(), fresh.leftovers().toString())
    }

    @Test fun `the passphrase is compared after Unicode normalisation`() {
        val composed = "Smörgåsbord är gott 123"
        val decomposed = java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFD)
        assertNotEquals(composed, decomposed)
        val file = phone.export(passphrase = composed)
        assertEquals(4, phone.newPhone().import(file, passphrase = decomposed).imported)
    }

    @Test fun `a passphrase of less than 12 characters is refused`() {
        assertFailsWith<HistoryException.WeakPassphrase> { phone.export(passphrase = "elevenchars") }
        phone.export(passphrase = "twelve chars") // exactly 12 is fine
    }

    // --- tampering ----------------------------------------------------------------------------------------------------------

    @Test fun `a changed header never imports - every byte of it`() {
        val file = phone.export()
        val fresh = phone.newPhone()
        for (i in 0 until 31) {
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val error = assertFailsWith<HistoryException>("header byte $i") { fresh.import(bad) }
            when (i) {
                in 0..3 -> assertIs<HistoryException.NotAHistoryFile>(error)
                4 -> assertIs<HistoryException.UnsupportedVersion>(error)
                in 14..29 -> assertIs<HistoryException.WrongPassphraseOrDamaged>(error, "a changed salt gives another key") // salt
                else -> assertTrue(
                    error is HistoryException.UnsupportedParameters || error is HistoryException.WrongPassphraseOrDamaged, "byte $i: $error",
                )
            }
        }
        assertEquals(0, fresh.messageCount("c1"))
        assertTrue(fresh.leftovers().isEmpty())
    }

    @Test fun `a changed byte in the ciphertext is found wherever it is, and nothing is imported`() {
        val file = phone.export(includeMedia = true)
        assertTrue(file.size > 2_400_000, "the payload spans several 1 MiB segments: ${file.size}")
        val fresh = phone.newPhone()
        val positions = listOf(31, 31 + 20, 31 + 100, 1_000_000, 1_048_576 + 31, 2_000_000, file.size - 100, file.size - 17, file.size - 1)
        for (pos in positions) {
            val bad = file.copyOf().also { it[pos] = (it[pos].toInt() xor 0x40).toByte() }
            val error = assertFailsWith<HistoryException>("byte $pos") { fresh.import(bad) }
            assertTrue(error is HistoryException.WrongPassphraseOrDamaged || error is HistoryException.Damaged, "byte $pos: $error")
        }
        assertEquals(0, fresh.messageCount("c1")); assertEquals(0, fresh.messageCount("c2"))
        assertTrue(fresh.leftovers().isEmpty(), fresh.leftovers().toString())
        assertTrue(runBlocking { fresh.store.getReactions("m1") }.isEmpty())
    }

    @Test fun `a file that was cut off is refused at every length, and nothing is imported`() {
        val file = phone.export(includeMedia = true)
        val fresh = phone.newPhone()
        val lengths = listOf(
            0, 3, 10, 30, 31, 32, 60, 500, 1_000_000, 1_048_000, 1_100_000, 2_100_000,
            file.size - 2_000, file.size - 100, file.size - 16, file.size - 1,
        )
        for (len in lengths) {
            val cut = file.copyOf(len)
            val error = assertFailsWith<HistoryException>("length $len") { fresh.import(cut, declaredLength = len.toLong()) }
            if (len < 31) assertIs<HistoryException.NotAHistoryFile>(error, "length $len")
            else assertTrue(error is HistoryException.WrongPassphraseOrDamaged || error is HistoryException.Damaged, "length $len: $error")
        }
        assertEquals(0, fresh.messageCount("c1"))
        assertTrue(fresh.leftovers().isEmpty())
    }

    @Test fun `data appended to a file is not accepted either`() {
        val file = phone.export()
        val extended = file + ByteArray(64) { 7 }
        assertFailsWith<HistoryException> { phone.newPhone().import(extended) }
    }

    @Test fun `a version this app does not know is named, and so are unknown parameters`() {
        val file = phone.export()
        val v2 = file.copyOf().also { it[4] = 2 }
        assertEquals(2, assertFailsWith<HistoryException.UnsupportedVersion> { phone.newPhone().import(v2) }.version)
        val v0 = file.copyOf().also { it[4] = 0 }
        assertEquals(0, assertFailsWith<HistoryException.UnsupportedVersion> { phone.newPhone().import(v0) }.version)
        val otherKdf = file.copyOf().also { it[5] = 2 }
        assertFailsWith<HistoryException.UnsupportedParameters> { phone.newPhone().import(otherKdf) }
        // an archive that says it is another version (written by a future exporter under the same header)
        val future = buildFile(listOf("manifest.json" to manifest(version = 2)))
        assertEquals(2, assertFailsWith<HistoryException.UnsupportedVersion> { phone.newPhone().import(future) }.version)
        assertFailsWith<HistoryException.BadContent> { phone.newPhone().import(buildFile(listOf("manifest.json" to manifest(format = "something-else")))) }
        // memory cost beyond what an importer runs: a crafted header cannot make the phone allocate gigabytes
        val huge = file.copyOf().also { it[6] = 0x7f }
        assertFailsWith<HistoryException.UnsupportedParameters> { phone.newPhone().import(huge) }
    }

    @Test fun `a file that is not a history file is recognised`() {
        assertFailsWith<HistoryException.NotAHistoryFile> { phone.newPhone().import(ByteArray(100) { 1 }) }
        assertFailsWith<HistoryException.NotAHistoryFile> { phone.newPhone().import(ByteArray(0)) }
        assertFailsWith<HistoryException.NotAHistoryFile> { phone.newPhone().import("PK\u0003\u0004 a zip file".toByteArray()) }
    }

    // --- limits -------------------------------------------------------------------------------------------------------------

    private class NeverRead : InputStream() {
        var reads = 0
        override fun read(): Int { reads++; throw AssertionError("must not be read") }
    }

    @Test fun `a file that is declared larger than the limit is refused before a byte is read`() = runBlocking {
        val never = NeverRead()
        val fresh = phone.newPhone()
        assertFailsWith<HistoryException.TooLarge> {
            HistoryImporter(fresh.store, fresh.media, HistoryLimits(maxFileBytes = 1_000)).import(never, 1_001, PASS, "me")
        }
        assertEquals(0, never.reads)
    }

    @Test fun `a stream longer than the limit is cut off when it passes it, even if it did not say how long it is`() {
        val file = phone.export(includeMedia = true)
        val fresh = phone.newPhone()
        assertFailsWith<HistoryException.TooLarge> { fresh.import(file, limits = HistoryLimits(maxFileBytes = 100_000), declaredLength = null) }
        assertFailsWith<HistoryException.TooLarge> { fresh.import(file, limits = HistoryLimits(maxFileBytes = 100_000)) }
        assertEquals(0, fresh.messageCount("c1"))
        assertTrue(fresh.leftovers().isEmpty())
    }

    @Test fun `the reading is bounded - never more than the limit plus one buffer is taken from the stream`() = runBlocking {
        class Counting(val data: ByteArray) : InputStream() {
            var taken = 0L
            private val inner = ByteArrayInputStream(data)
            override fun read(): Int = inner.read().also { if (it >= 0) taken++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) taken += it }
        }
        val file = phone.export(includeMedia = true)
        val counting = Counting(file)
        val fresh = phone.newPhone()
        assertFailsWith<HistoryException.TooLarge> {
            HistoryImporter(fresh.store, fresh.media, HistoryLimits(maxFileBytes = 200_000)).import(counting, null, PASS, "me")
        }
        assertTrue(counting.taken <= 200_001, "took ${counting.taken}")
    }

    @Test fun `what the archive unpacks to is bounded too`() {
        val file = phone.export(includeMedia = true)
        val fresh = phone.newPhone()
        assertFailsWith<HistoryException.TooLarge> { fresh.import(file, limits = HistoryLimits(maxPayloadBytes = 1_000_000)) }
        assertFailsWith<HistoryException.TooLarge> { fresh.import(file, limits = HistoryLimits(maxEntryBytes = 1_000_000)) }
        // a classic bomb: a small file that unpacks to a lot
        val bomb = buildFile(listOf("manifest.json" to manifest(), "media/0" to ByteArray(50_000_000)))
        assertTrue(bomb.size < 200_000, "the bomb itself is small: ${bomb.size}")
        assertFailsWith<HistoryException.TooLarge> { fresh.import(bomb, limits = HistoryLimits(maxPayloadBytes = 10_000_000, maxEntryBytes = 10_000_000)) }
        assertEquals(0, fresh.messageCount("c1"))
        assertTrue(fresh.leftovers().isEmpty())
    }

    // --- hostile content inside an authentic file --------------------------------------------------------------------------

    private fun line(json: String) = (json + "\n").toByteArray()

    @Test fun `a file from another account is refused`() {
        assertFailsWith<HistoryException.WrongAccount> { phone.newPhone().import(phone.export(owner = "someone-else")) }
        assertEquals(0, phone.newPhone().messageCount("c1"))
    }

    @Test fun `an archive must begin with its manifest`() {
        assertFailsWith<HistoryException.BadContent> {
            phone.newPhone().import(buildFile(listOf("messages.jsonl" to line("""{"id":"x","conversation_id":"c1","sender_user_id":"bob","text":"hej"}"""))))
        }
    }

    @Test fun `hostile records are dropped and hostile names cannot leave the folder`() = runBlocking {
        val good = """{"id":"good","conversation_id":"c1","sender_user_id":"bob","seq":50,"text":"fine"}"""
        val badId = """{"id":"../escape","conversation_id":"c1","sender_user_id":"bob","text":"x"}"""
        val badConversation = """{"id":"b2","conversation_id":"c1/../c2","sender_user_id":"bob","text":"x"}"""
        val noContent = """{"id":"b3","conversation_id":"c1","sender_user_id":"bob"}"""
        val tooMany = """{"id":"b4","conversation_id":"c1","sender_user_id":"bob","attachments":[${(1..11).joinToString(",") { """{"name":"a$it"}""" }}]}"""
        val traversal = """{"id":"b5","conversation_id":"c1","sender_user_id":"bob","text":"t",""" +
            """"attachments":[{"name":"../../../evil.sh","media":"media/../../evil","mime":"text/x\nbad"}]}"""
        val notJson = "this is not json"
        val badReaction = """{"id":"b6","conversation_id":"c1","sender_user_id":"bob","text":"r",""" +
            """"reactions":[{"user_id":"bob","emoji":"not an emoji"},{"user_id":"bob","emoji":"👍"}]}"""
        val fresh = phone.newPhone()
        val file = buildFile(
            listOf(
                "manifest.json" to manifest(),
                "media/../../outside" to "x".toByteArray(), // not a media entry: skipped
                "../outside2" to "x".toByteArray(),
                "media/0" to "media-bytes".toByteArray(),
                "messages.jsonl" to listOf(good, badId, badConversation, noContent, tooMany, traversal, notJson, badReaction).joinToString("\n").toByteArray(),
            ),
        )
        val result = fresh.import(file)
        assertEquals(3, result.imported, "good, the traversal message (sanitised) and the one with a bad reaction (filtered)")
        assertEquals(5, result.invalid)
        val t = fresh.store.getMessage("b5")!!.attachments.single()
        assertFalse(t.name!!.contains('/'))
        assertEquals("application/octet-stream", t.mime)
        assertNull(t.localPath, "a media name outside media/<n> is not followed")
        assertEquals(listOf("👍"), fresh.store.getReactions("b6").map { it.emoji })
        for (escaped in listOf(File(fresh.media.parentFile, "outside"), File(fresh.media.parentFile, "outside2"), File(fresh.dir, "outside2"))) {
            assertFalse(escaped.exists(), escaped.path)
        }
        assertTrue(fresh.leftovers().isEmpty(), "media nothing refers to is removed: ${fresh.leftovers()}")
    }

    @Test fun `a line that is too long fails the import without importing anything`() {
        val fresh = phone.newPhone()
        val file = buildFile(listOf("manifest.json" to manifest(), "messages.jsonl" to ("x".repeat(5_000) + "\n").toByteArray()))
        assertFailsWith<HistoryException.BadContent> { fresh.import(file, limits = HistoryLimits(maxLineBytes = 1_000)) }
        assertEquals(0, fresh.messageCount("c1"))
    }

    // --- what the export holds ------------------------------------------------------------------------------------------------

    @Test fun `the archive has only the documented entries and the documented fields`() {
        val entries = openArchive(phone.export(includeMedia = true))
        assertEquals(listOf("manifest.json", "conversations.jsonl", "media/0", "messages.jsonl"), entries.keys.toList())
        val messageKeys = HashSet<String>()
        val attachmentKeys = HashSet<String>()
        for (l in String(entries["messages.jsonl"]!!).lines().filter { it.isNotBlank() }) {
            val o = FamilyChatJson.parseToJsonElement(l).jsonObject
            messageKeys += o.keys
            (o["attachments"] as? JsonArray)?.forEach { a -> attachmentKeys += (a as JsonObject).keys }
        }
        val allowedMessage = setOf(
            "id", "conversation_id", "sender_user_id", "client_ts", "server_ts", "seq", "text", "reply_to", "edited", "edited_at",
            "forwarded", "attachments", "reactions",
        )
        val allowedAttachment = setOf("name", "mime", "kind", "size", "width", "height", "duration_ms", "thumbnail", "media")
        assertTrue(allowedMessage.containsAll(messageKeys), "unexpected message fields: ${messageKeys - allowedMessage}")
        assertTrue(allowedAttachment.containsAll(attachmentKeys), "unexpected attachment fields: ${attachmentKeys - allowedAttachment}")
    }

    @Test fun `no key, token, checksum, server attachment id or device id is anywhere in an export`() {
        val file = phone.export(includeMedia = true)
        val entries = openArchive(file)
        val everything = entries.values.fold(ByteArray(0)) { acc, b -> acc + b }
        for (secret in listOf(
            Secrets.ACCESS_TOKEN, Secrets.REFRESH_TOKEN, Secrets.ATTACHMENT_KEY, Secrets.ATTACHMENT_SHA, Secrets.SERVER_ATTACHMENT_ID,
            Secrets.DEVICE_ID, "my-device", Secrets.PREKEY_MARKER, "secret-meta", "pending_envelope",
        )) {
            assertFalse(contains(everything, secret.toByteArray()), "found: $secret")
        }
    }
}
