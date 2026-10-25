package se.familychat.app.data.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import se.familychat.core.client.DownloadState
import se.familychat.core.client.LocalStore
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.StoredMessage
import se.familychat.core.model.FamilyChatJson
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Counts of what an export wrote. */
data class ExportResult(val conversations: Int, val messages: Int, val mediaFiles: Int, val leftOut: Int)

/**
 * Writes the encrypted history file (see [HistoryFormat]).
 *
 * What goes in: conversations (id, kind, name, member names), and per message the text and metadata a person would want
 * back: sender, times, order number, reply, edited, forwarded, reactions, and per attachment its name, type and size, the
 * small preview and, if [includeMedia] was chosen, the decrypted file.
 *
 * What never goes in, by construction (this code reads messages through [LocalStore] and copies named fields only): keys of
 * any kind (identity, sessions, prekeys), tokens, the attachment keys and checksums and server ids that would let someone
 * fetch an attachment, device ids, receipts, the undecrypted envelopes. Messages that disappear (they have a timer) are left
 * out, as are failed and unsent ones, deleted ones, notices and the ones that could not be decrypted.
 */
class HistoryExporter(private val store: LocalStore, private val clock: () -> Long = System::currentTimeMillis) {

    /**
     * Streams the file to [out] and closes it. The caller checked the passphrase with [PassphraseStrength]; shorter than
     * [HistoryFormat.MIN_PASSPHRASE_LENGTH] is refused here too.
     */
    suspend fun export(
        out: OutputStream,
        passphrase: CharSequence,
        ownerUserId: String,
        includeMedia: Boolean,
        appVersion: String = "",
        kdf: KdfParams = KdfParams.DEFAULT,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        if (passphrase.length < HistoryFormat.MIN_PASSPHRASE_LENGTH) throw HistoryException.WeakPassphrase()
        val header = HistoryFormat.newHeader(kdf)
        val headerBytes = header.encode()
        val key = HistoryFormat.deriveKey(passphrase, header)
        try {
            out.write(headerBytes)
            val encrypting = HistoryFormat.streaming(key).newEncryptingStream(out, headerBytes)
            ZipOutputStream(encrypting).use { zip -> writeArchive(zip, ownerUserId, includeMedia, appVersion, onProgress) }
        } finally {
            key.fill(0)
        }
    }

    private suspend fun writeArchive(
        zip: ZipOutputStream,
        ownerUserId: String,
        includeMedia: Boolean,
        appVersion: String,
        onProgress: (Int, Int) -> Unit,
    ): ExportResult {
        val conversations = store.getConversations()
        val users = store.getUsers().associateBy { it.id }

        // pass 0: how many messages there are (the manifest, which comes first, says so)
        var total = 0
        var leftOut = 0
        for (c in conversations) {
            forEachMessage(c.id) { m ->
                if (exportableMessage(m)) total++ else if (!m.isSystem && !m.deleted && m.decryptError == null) leftOut++
            }
        }
        zip.putNextEntry(ZipEntry(HistoryEntries.MANIFEST))
        zip.write(
            FamilyChatJson.encodeToString(
                HistoryManifest(
                    createdAt = clock(), appVersion = appVersion, ownerUserId = ownerUserId, includesMedia = includeMedia,
                    conversations = conversations.size, messages = total, leftOut = leftOut,
                ),
            ).toByteArray(),
        )
        zip.closeEntry()

        zip.putNextEntry(ZipEntry(HistoryEntries.CONVERSATIONS))
        for (c in conversations) {
            val record = HistoryConversation(
                c.id, c.type, c.name, c.members.map { HistoryMember(it.userId, users[it.userId]?.displayName.orEmpty()) },
            )
            zip.write(line(FamilyChatJson.encodeToString(record)))
        }
        zip.closeEntry()

        // pass 1: the media files, before the messages that name them (an importer reads the archive once, front to back)
        val mediaOf = HashMap<String, List<String?>>() // message id -> archive entry per attachment
        var mediaCount = 0
        if (includeMedia) {
            for (c in conversations) {
                forEachMessage(c.id) { m ->
                    if (!exportableMessage(m) || m.attachments.isEmpty()) return@forEachMessage
                    mediaOf[m.id] = m.attachments.map { a ->
                        val file = a.localPath?.let(::File)?.takeIf { a.downloadState == DownloadState.DONE && it.isFile }
                        if (file == null) {
                            null
                        } else {
                            val entry = "${HistoryEntries.MEDIA_PREFIX}${mediaCount++}"
                            zip.setLevel(Deflater.NO_COMPRESSION) // photos, video and PDFs are compressed already
                            zip.putNextEntry(ZipEntry(entry))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            entry
                        }
                    }
                }
            }
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        }

        // pass 2: the messages
        zip.putNextEntry(ZipEntry(HistoryEntries.MESSAGES))
        var done = 0
        for (c in conversations) {
            forEachMessage(c.id) { m ->
                if (!exportableMessage(m)) return@forEachMessage
                val media = mediaOf[m.id]
                val reactions = store.getReactions(m.id).mapNotNull { r -> r.emoji?.let { HistoryReaction(r.userId, it) } }
                val record = HistoryMessage(
                    id = m.id, conversationId = m.conversationId, senderUserId = m.senderUserId, clientTs = m.clientTs,
                    serverTs = m.serverTs, seq = m.seq, text = m.text, replyTo = m.replyTo, edited = m.edited, editedAt = m.editedAt,
                    forwarded = m.forwarded, reactions = reactions,
                    attachments = m.attachments.mapIndexed { i, a ->
                        HistoryAttachment(
                            name = a.name, mime = a.mime, kind = a.kind, size = a.size, width = a.width, height = a.height,
                            durationMs = a.durationMs, thumbnail = a.thumbnail, media = media?.getOrNull(i),
                        )
                    },
                )
                zip.write(line(FamilyChatJson.encodeToString(record)))
                onProgress(++done, total)
            }
        }
        zip.closeEntry()
        return ExportResult(conversations.size, total, mediaCount, leftOut)
    }

    /** Messages of a conversation, newest page first, [PAGE] at a time, so a long history is never held in memory. */
    private suspend fun forEachMessage(conversationId: String, action: suspend (StoredMessage) -> Unit) {
        var before: Long? = null
        do {
            val page = store.getMessages(conversationId, PAGE, before)
            for (m in page) action(m)
            // a short page is the last one; unsent messages have no number (they are not exported anyway) and end the walk too
            before = if (page.size < PAGE) null else page.first().seq
        } while (before != null)
    }

    private fun exportableMessage(m: StoredMessage): Boolean =
        !m.isSystem && !m.deleted && m.decryptError == null && m.expiresIn == null && m.seq != null &&
            m.status != MessageStatus.PENDING && m.status != MessageStatus.FAILED &&
            (!m.text.isNullOrEmpty() || m.attachments.isNotEmpty())

    private fun line(json: String): ByteArray = (json + "\n").toByteArray(Charsets.UTF_8)

    private companion object {
        const val PAGE = 500
    }
}
