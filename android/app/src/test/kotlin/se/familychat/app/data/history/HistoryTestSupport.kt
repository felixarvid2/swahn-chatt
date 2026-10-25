package se.familychat.app.data.history

import kotlinx.coroutines.runBlocking
import se.familychat.core.client.Account
import se.familychat.core.client.DownloadState
import se.familychat.core.client.InMemoryLocalStore
import se.familychat.core.client.LocalAttachment
import se.familychat.core.client.LocalStore
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.Reaction
import se.familychat.core.client.StoredMessage
import se.familychat.core.client.SystemEvent
import se.familychat.core.model.Conversation
import se.familychat.core.model.ConversationMember
import se.familychat.core.model.User
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Cheap Argon2id parameters for tests that are not about the parameters (the default takes a few hundred milliseconds). */
val TEST_KDF = KdfParams(memoryKiB = 256, iterations = 1, parallelism = 1)

const val PASS = "correct horse battery staple"

/** The secrets of a phone: none of them may appear anywhere in an export. */
object Secrets {
    const val ACCESS_TOKEN = "access-token-SECRET-1234567890"
    const val REFRESH_TOKEN = "refresh-token-SECRET-0987654321"
    const val ATTACHMENT_KEY = "QVRUQUNITUVOVC1LRVktU0VDUkVULTMyLUJZVEVTLSEhIQ=="
    const val ATTACHMENT_SHA = "5ec2e7a1f0b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d"
    const val SERVER_ATTACHMENT_ID = "11111111-aaaa-bbbb-cccc-server-attach-id"
    const val DEVICE_ID = "dev-bob-device-uuid-0001"
    const val PREKEY_MARKER = "PRIVATE-PREKEY-RECORD"
}

/** A phone with one direct conversation, one group, and a handful of messages of every kind that matters for an export. */
class Phone(val dir: File = Files.createTempDirectory("history-test").toFile()) {
    val store = InMemoryLocalStore()
    val media = File(dir, "attachments").also { it.mkdirs() }
    val photo = File(media, "att-1").also { it.writeBytes(Random(1).nextBytes(2_500_000)) } // more than two 1 MiB segments, incompressible

    fun fill(): Phone = runBlocking {
        store.saveAccount(Account("https://x.example/", "me", "my-device", 1, "anna", "Anna", false, "Pixel"))
        store.upsertUsers(listOf(User("me", "anna", "Anna"), User("bob", "bob", "Bob")))
        store.upsertConversation(
            Conversation("c1", "direct", null, createdBy = "me", members = listOf(ConversationMember("me"), ConversationMember("bob"))),
        )
        store.upsertConversation(
            Conversation("c2", "group", "Familjen", createdBy = "me", members = listOf(ConversationMember("me"), ConversationMember("bob"))),
        )
        store.upsertMessage(msg("m1", seq = 1, text = "Hej från Bob åäö 👋", sender = "bob"))
        store.upsertMessage(msg("m2", seq = 2, text = "Svar", sender = "me", edited = true, replyTo = "m1"))
        store.upsertMessage(
            msg("m3", seq = 3, text = "Se bilden", sender = "bob", forwarded = true).copy(
                attachments = listOf(
                    LocalAttachment(
                        id = Secrets.SERVER_ATTACHMENT_ID, key = Secrets.ATTACHMENT_KEY, sha256 = Secrets.ATTACHMENT_SHA, size = photo.length(),
                        mime = "image/jpeg", name = "../../etc/passwd.jpg", kind = "image", width = 640, height = 480, durationMs = null,
                        thumbnail = "dGh1bWI=", localPath = photo.absolutePath, downloadState = DownloadState.DONE,
                    ),
                    LocalAttachment(
                        id = "gone", key = Secrets.ATTACHMENT_KEY, sha256 = Secrets.ATTACHMENT_SHA, size = 5, mime = "application/pdf",
                        name = "inte-hämtad.pdf", kind = "file", width = null, height = null, durationMs = null, thumbnail = null,
                        localPath = null, downloadState = DownloadState.NONE,
                    ),
                ),
            ),
        )
        store.upsertMessage(msg("m-expiring", seq = 4, text = "försvinner", sender = "bob").copy(expiresIn = 3600, expireAt = 5))
        store.upsertMessage(msg("m-deleted", seq = 5, text = null, sender = "bob").copy(deleted = true))
        store.upsertMessage(msg("m-pending", seq = null, text = "ej skickat", sender = "me", status = MessageStatus.PENDING))
        store.upsertMessage(msg("m-failed", seq = null, text = "misslyckades", sender = "me", status = MessageStatus.FAILED))
        store.upsertMessage(msg("m-error", seq = 6, text = null, sender = "bob").copy(decryptError = se.familychat.core.client.DecryptError.CORRUPT))
        store.upsertMessage(
            StoredMessage(
                id = "sys1", conversationId = "c1", senderUserId = "", senderDeviceId = null, outgoing = false, status = MessageStatus.RECEIVED,
                seq = 3, clientTs = 1, serverTs = null, text = null, replyTo = null, systemEvent = SystemEvent.MemberAdded("bob"),
            ),
        )
        store.upsertMessage(msg("g1", conv = "c2", seq = 1, text = "Hej familjen", sender = "bob"))
        store.putReaction(Reaction("m1", "c1", "me", "👍", 7, 70))
        store.putReaction(Reaction("m1", "c1", "bob", null, 8, 80)) // taken back: not exported
        store.setMeta("secret-meta", Secrets.REFRESH_TOKEN)
        this@Phone
    }

    private fun msg(
        id: String, conv: String = "c1", seq: Long?, text: String?, sender: String, status: MessageStatus? = null,
        edited: Boolean = false, replyTo: String? = null, forwarded: Boolean = false,
    ) = StoredMessage(
        id = id, conversationId = conv, senderUserId = sender, senderDeviceId = if (sender == "me") "my-device" else Secrets.DEVICE_ID,
        outgoing = sender == "me", status = status ?: if (sender == "me") MessageStatus.READ else MessageStatus.RECEIVED, seq = seq,
        clientTs = 1000L + (seq ?: 99), serverTs = seq?.let { 2000L + it }, text = text, replyTo = replyTo, edited = edited,
        editedAt = if (edited) 3000 else null, forwarded = forwarded, deliveredTo = setOf("bob"), readBy = setOf("bob"),
        pendingEnvelopeType = null, pendingEnvelopeBody = null,
    )

    /** A fresh phone of the same account that has the conversations (as after signing in again) but no messages. */
    fun newPhone(): Phone = runBlocking {
        Phone().also { p ->
            p.store.saveAccount(store.getAccount()!!)
            p.store.upsertUsers(store.getUsers())
            for (c in store.getConversations()) p.store.upsertConversation(c)
        }
    }

    fun export(
        passphrase: String = PASS, includeMedia: Boolean = false, kdf: KdfParams = TEST_KDF, owner: String = "me",
        from: LocalStore = store,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { HistoryExporter(from).export(out, passphrase, owner, includeMedia, "0.4.0-test", kdf) }
        return out.toByteArray()
    }

    fun import(
        file: ByteArray, passphrase: String = PASS, limits: HistoryLimits = HistoryLimits(), declaredLength: Long? = file.size.toLong(),
        owner: String = "me", into: LocalStore = store,
    ): ImportResult = runBlocking {
        HistoryImporter(into, media, limits, spoolDir = File(dir, "spool")).import(ByteArrayInputStream(file), declaredLength, passphrase, owner)
    }

    fun messageCount(c: String, into: LocalStore = store) = runBlocking { into.getMessages(c, 1000).size }

    /** Every file under [media] and the spool directory: after a failed import there must be none. */
    fun leftovers(): List<File> = media.walkTopDown().filter { it.isFile && it != photo }.toList() +
        File(dir, "spool").walkTopDown().filter { it.isFile }.toList()
}

/** Decrypts a history file the way the importer does and returns every archive entry. */
fun openArchive(file: ByteArray, passphrase: String = PASS): Map<String, ByteArray> {
    val (header, raw) = HistoryFormat.readHeader(ByteArrayInputStream(file))
    val key = HistoryFormat.deriveKey(passphrase, header)
    val input = ByteArrayInputStream(file, HistoryFormat.HEADER_BYTES, file.size)
    val zip = ZipInputStream(HistoryFormat.streaming(key).newDecryptingStream(input, raw))
    val entries = LinkedHashMap<String, ByteArray>()
    while (true) {
        val e = zip.nextEntry ?: break
        entries[e.name] = zip.readBytes()
    }
    return entries
}

/** Builds a history file from arbitrary archive entries with the real format (for hostile content). */
fun buildFile(entries: List<Pair<String, ByteArray>>, passphrase: String = PASS, kdf: KdfParams = TEST_KDF): ByteArray {
    val header = HistoryFormat.newHeader(kdf)
    val raw = header.encode()
    val key = HistoryFormat.deriveKey(passphrase, header)
    val out = ByteArrayOutputStream()
    out.write(raw)
    val enc = HistoryFormat.streaming(key).newEncryptingStream(out, raw)
    ZipOutputStream(enc).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

fun manifest(owner: String = "me", version: Int = 1, format: String = "familychat-history") =
    """{"format":"$format","version":$version,"created_at":1,"owner_user_id":"$owner","includes_media":false}""".toByteArray()

fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty() || haystack.size < needle.size) return false
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}
