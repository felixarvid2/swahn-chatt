package se.swahnchat.app.data.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import se.swahnchat.app.media.ShareFiles
import se.swahnchat.core.client.DownloadState
import se.swahnchat.core.client.LocalAttachment
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.client.MessageStatus
import se.swahnchat.core.client.Reaction
import se.swahnchat.core.client.StoredMessage
import se.swahnchat.core.model.Conversation
import se.swahnchat.core.model.Emoji
import se.swahnchat.core.model.SwahnChatJson
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** What an import did. */
data class ImportResult(
    val imported: Int,
    /** Already on this phone (the same message id), or removed here with "delete for me" or by a timer. */
    val alreadyHere: Int,
    /** In a conversation this phone does not have (yet): not created from a file. */
    val unknownConversation: Int,
    val invalid: Int,
    val mediaFiles: Int,
)

/** Limits an import keeps to. The defaults are for the app; tests pass small ones. */
data class HistoryLimits(
    /** The file as stored (checked against its length up front, and again while reading). */
    val maxFileBytes: Long = 4L * 1024 * 1024 * 1024,
    /** Everything the archive unpacks to, summed. */
    val maxPayloadBytes: Long = 8L * 1024 * 1024 * 1024,
    /** One archive entry (a media file is at most this). */
    val maxEntryBytes: Long = 101L * 1024 * 1024,
    /** One line of a `.jsonl` entry. */
    val maxLineBytes: Int = 1024 * 1024,
    val maxEntries: Int = 200_000,
    /** Lines of `messages.jsonl`, valid or not: an archive cannot make the importer parse an unbounded number of records. */
    val maxMessageLines: Int = 200_000,
    /** What the validated messages take in the temporary spool file, and the most `messages.jsonl` itself may unpack to. */
    val maxSpoolBytes: Long = 1024L * 1024 * 1024,
)

/**
 * Reads a history file (see [HistoryFormat]) and merges its messages into this phone's history.
 *
 * - It only adds. Messages already here (same id) or removed here are left alone; conversations are never created from a
 *   file, only messages of conversations this phone already knows. Imported messages are marked "Importerad", are never
 *   sent anywhere, and touch no session, key, token or read mark.
 * - A file is not trusted about who said what or when: a message whose sender is not a member of the conversation here is
 *   dropped, its `seq` is clamped to the conversation's current last sequence number (so it can neither silence later real
 *   messages nor reorder anything past the present; imported rows are also left out of every unread count and read mark),
 *   and of the reactions only this account's own are taken.
 * - The file is authenticated all the way to its last byte before anything is added: the archive is unpacked into a spool
 *   (validated messages in a temporary file, media files in a folder of their own), and only when the stream has ended
 *   cleanly are the messages inserted. A wrong passphrase, a changed header or ciphertext, or a file that was cut off
 *   therefore changes nothing and leaves no files behind.
 * - Everything inside is validated again (ids, lengths, attachment count, names via [ShareFiles.safeName], the MIME type's
 *   shape, the media entry names) and bounded by [HistoryLimits].
 */
class HistoryImporter(
    private val store: LocalStore,
    /** Where imported media files are written; must be inside the engine's attachment directory, which then owns them. */
    private val mediaDir: File,
    private val limits: HistoryLimits = HistoryLimits(),
    /** Where the spool of validated messages lives while the file is read (temporary; removed afterwards). */
    private val spoolDir: File = mediaDir,
) {
    suspend fun import(
        input: InputStream,
        fileLength: Long?,
        passphrase: CharSequence,
        ownerUserId: String,
        onProgress: (phase: Phase, done: Int) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        if (fileLength != null && fileLength > limits.maxFileBytes) throw HistoryException.TooLarge()
        val bounded = LimitedInput(input, limits.maxFileBytes)
        val (header, headerBytes) = HistoryFormat.readHeader(bounded)
        val key = HistoryFormat.deriveKey(passphrase, header)
        val runDir = File(mediaDir, "import-${UUID.randomUUID()}")
        val spool = File.createTempFile("history-spool", ".jsonl", spoolDir.also { it.mkdirs() })
        try {
            // 1) unpack and authenticate; nothing in the database yet
            val staged = unpack(bounded, headerBytes, key, ownerUserId, runDir, spool, onProgress)
            // 2) the stream ended cleanly: now merge
            merge(spool, runDir, staged, onProgress)
        } catch (e: Throwable) {
            runDir.deleteRecursively()
            throw e
        } finally {
            key.fill(0)
            spool.delete()
        }
    }

    enum class Phase { READING, MERGING }

    private class Staged(val media: Set<String>, var invalid: Int, var messages: Int)

    @Suppress("ThrowsCount") // each way the file can be wrong has its own refusal
    private fun unpack(
        file: InputStream,
        headerBytes: ByteArray,
        key: ByteArray,
        ownerUserId: String,
        runDir: File,
        spool: File,
        onProgress: (Phase, Int) -> Unit,
    ): Staged {
        // The first block decides between "wrong passphrase / changed header" and "damaged later".
        val buffered = try {
            val decrypting = HistoryFormat.streaming(key).newDecryptingStream(file, headerBytes)
            BufferedInputStream(decrypting, 64 * 1024).also {
                it.mark(1)
                it.read()
                it.reset()
            }
        } catch (e: IOException) {
            throw if (hasLimit(e)) HistoryException.TooLarge() else HistoryException.WrongPassphraseOrDamaged()
        } catch (e: java.security.GeneralSecurityException) {
            throw HistoryException.WrongPassphraseOrDamaged()
        }
        // what the archive INFLATES to is counted (per entry and in total), not the bytes it was stored in: a bomb is small
        val zip = Inflated(ZipInputStream(buffered), Budget(limits.maxPayloadBytes))
        val reading = Unpacking(ownerUserId, runDir, onProgress)
        try {
            spool.outputStream().buffered().use { reading.run(zip, it) }
            // read to the very end of the ciphertext: the last segment authenticates that nothing was cut off
            drain(buffered)
        } catch (e: HistoryException) {
            throw e
        } catch (e: ZipException) {
            throw HistoryException.Damaged()
        } catch (e: IOException) {
            throw if (hasLimit(e)) HistoryException.TooLarge() else HistoryException.Damaged()
        } catch (e: java.security.GeneralSecurityException) {
            throw HistoryException.Damaged()
        }
        return Staged(reading.media, reading.invalid, reading.messages)
    }

    /** One pass over the archive: the manifest first, then the media and the lists, in whatever order the exporter wrote them. */
    private inner class Unpacking(private val ownerUserId: String, private val runDir: File, private val onProgress: (Phase, Int) -> Unit) {
        val media = HashSet<String>()
        var invalid = 0
        var messages = 0
        private var lines = 0
        private var spooled = 0L

        fun run(zip: Inflated, spoolOut: OutputStream) {
            zip.startEntry(limits.maxLineBytes.toLong())
            val first = zip.nextEntry() ?: throw HistoryException.BadContent("empty archive")
            if (first.name != HistoryEntries.MANIFEST) throw HistoryException.BadContent("no manifest")
            val manifest = decodeManifest(readEntry(zip, limits.maxLineBytes.toLong()))
            if (manifest.ownerUserId != ownerUserId) throw HistoryException.WrongAccount()
            var entries = 1
            while (true) {
                val entry = zip.nextEntry() ?: break
                if (++entries > limits.maxEntries) throw HistoryException.TooLarge()
                zip.startEntry(if (entry.name == HistoryEntries.MESSAGES) limits.maxSpoolBytes else limits.maxEntryBytes)
                entry(entry.name, zip, spoolOut)
            }
        }

        private fun entry(name: String, zip: InputStream, spoolOut: OutputStream) {
            when {
                name == HistoryEntries.MANIFEST -> throw HistoryException.BadContent("manifest twice")
                // the names in the list are for people reading the file; nothing is created from them
                name == HistoryEntries.CONVERSATIONS -> drainLines(zip) { }
                name == HistoryEntries.MESSAGES -> drainLines(zip) { line -> message(line, spoolOut) }
                MEDIA_NAME.matches(name) -> {
                    runDir.mkdirs()
                    copyLimited(zip, File(runDir, name.removePrefix(HistoryEntries.MEDIA_PREFIX)), limits.maxEntryBytes)
                    media += name
                }
                else -> drain(zip) // unknown entries of a newer exporter are skipped, but still counted against the limits
            }
        }

        private fun message(line: String, spoolOut: OutputStream) {
            if (++lines > limits.maxMessageLines) throw HistoryException.TooLarge()
            val record = try {
                SwahnChatJson.decodeFromString(HistoryMessage.serializer(), line)
            } catch (e: SerializationException) {
                null
            }
            val checked = record?.let { validate(it, media, runDir) }
            if (checked == null) {
                invalid++
                return
            }
            val bytes = (SwahnChatJson.encodeToString(HistoryMessage.serializer(), checked) + "\n").toByteArray()
            spooled += bytes.size
            if (spooled > limits.maxSpoolBytes) throw HistoryException.TooLarge()
            spoolOut.write(bytes)
            if (++messages % 500 == 0) onProgress(Phase.READING, messages)
        }
    }

    private fun hasLimit(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is LimitExceeded }

    private fun decodeManifest(bytes: ByteArray): HistoryManifest {
        val m = try {
            SwahnChatJson.decodeFromString(HistoryManifest.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw HistoryException.BadContent("manifest")
        }
        if (m.format != HistoryManifest.FORMAT) throw HistoryException.BadContent("format ${m.format}")
        if (m.version != HistoryFormat.VERSION) throw HistoryException.UnsupportedVersion(m.version)
        return m
    }

    /** True for a record that is shaped like a message this app could have stored. */
    private fun acceptable(r: HistoryMessage): Boolean =
        safeId(r.id) && safeId(r.conversationId) && safeId(r.senderUserId) && (r.replyTo == null || safeId(r.replyTo)) &&
            (r.text?.length ?: 0) <= MAX_TEXT_CHARS && r.attachments.size <= MAX_ATTACHMENTS && !(r.text.isNullOrEmpty() && r.attachments.isEmpty())

    /** A record in the shape the app stores, or null when it is not acceptable. [media] lists the entries seen so far. */
    private fun validate(r: HistoryMessage, media: Set<String>, runDir: File): HistoryMessage? {
        if (!acceptable(r)) return null
        val attachments = r.attachments.map { a ->
            val entry = a.media?.takeIf { it in media && MEDIA_NAME.matches(it) }
            a.copy(
                name = a.name?.let { ShareFiles.safeName(it) },
                mime = a.mime.takeIf { MIME.matches(it) } ?: "application/octet-stream",
                kind = a.kind.takeIf { it in KINDS } ?: "file",
                size = a.size.coerceAtLeast(0),
                thumbnail = a.thumbnail?.takeIf { it.length <= MAX_THUMBNAIL_CHARS },
                media = entry?.let { File(runDir, it.removePrefix(HistoryEntries.MEDIA_PREFIX)).path },
            )
        }
        val reactions = r.reactions.filter { safeId(it.userId) && Emoji.isValid(it.emoji) }.take(MAX_REACTIONS)
        return r.copy(attachments = attachments, reactions = reactions)
    }

    private suspend fun merge(spool: File, runDir: File, staged: Staged, onProgress: (Phase, Int) -> Unit): ImportResult {
        var imported = 0
        var already = 0
        var unknown = 0
        var done = 0
        val used = HashSet<String>()
        var rejected = 0
        val conversations = HashMap<String, Conversation?>() // a null value is cached too (a conversation this phone does not have)
        val batch = ArrayList<HistoryMessage>(BATCH)
        val me = store.getAccount()?.userId
        suspend fun flush() {
            if (batch.isEmpty()) return
            store.transaction {
                for (r in batch) {
                    val conversation = if (r.conversationId in conversations) {
                        conversations[r.conversationId]
                    } else {
                        store.getConversation(r.conversationId).also { conversations[r.conversationId] = it }
                    }
                    when {
                        conversation == null -> unknown++
                        // only what a member of the conversation (here, as the list stands now) could have said
                        conversation.members.none { it.userId == r.senderUserId } -> rejected++
                        store.getMessage(r.id) != null || store.isLocallyDeleted(r.id) -> already++
                        else -> {
                            insert(r, me, conversation)
                            r.attachments.forEach { a -> a.media?.let(used::add) }
                            imported++
                        }
                    }
                }
            }
            done += batch.size
            batch.clear()
            onProgress(Phase.MERGING, done)
        }
        spool.useLines { lines ->
            for (line in lines) {
                batch += SwahnChatJson.decodeFromString(HistoryMessage.serializer(), line)
                if (batch.size >= BATCH) flush()
            }
        }
        flush()
        // media of messages that were not inserted are not wanted
        runDir.listFiles()?.forEach { f -> if (f.path !in used) f.delete() }
        if (runDir.listFiles().isNullOrEmpty()) runDir.delete()
        return ImportResult(imported, already, unknown, staged.invalid + rejected, used.size)
    }

    private suspend fun insert(r: HistoryMessage, me: String?, conversation: Conversation) {
        val outgoing = r.senderUserId == me
        // never past what this phone has seen of the conversation: a huge seq must not mark later real messages as read
        val seq = (r.seq ?: 0L).coerceIn(0L, (conversation.lastMessageSeq ?: 0L).coerceAtLeast(0L))
        store.upsertMessage(
            StoredMessage(
                id = r.id, conversationId = r.conversationId, senderUserId = r.senderUserId, senderDeviceId = null,
                outgoing = outgoing, status = if (outgoing) MessageStatus.SENT else MessageStatus.RECEIVED,
                seq = seq, clientTs = r.clientTs, serverTs = r.serverTs, text = r.text, replyTo = r.replyTo,
                attachments = r.attachments.map { a ->
                    LocalAttachment(
                        id = "import-${UUID.randomUUID()}", key = null, sha256 = null, size = a.size, mime = a.mime, name = a.name,
                        kind = a.kind, width = a.width, height = a.height, durationMs = a.durationMs, thumbnail = a.thumbnail,
                        localPath = a.media, downloadState = if (a.media != null) DownloadState.DONE else DownloadState.NONE,
                    )
                },
                edited = r.edited, editedAt = r.editedAt, forwarded = r.forwarded, imported = true,
            ),
        )
        for (reaction in r.reactions) {
            // only this account's own: a file must not put reactions in other members' names
            if (reaction.userId != me) continue
            // sequence 0: any real reaction that arrives later, from any phone, replaces it
            store.putReaction(Reaction(r.id, r.conversationId, reaction.userId, reaction.emoji, 0, r.serverTs ?: r.clientTs))
        }
    }

    // --- bounded reading ------------------------------------------------------------------------------------------------

    private class LimitExceeded : IOException("limit exceeded")

    private class Budget(var left: Long)

    /**
     * The archive as it is read: counts INFLATED bytes (what [read] returns) against one total budget and against a limit for
     * the current entry. Reading goes only through this class, also when an entry is skipped, so a bomb cannot unpack unseen.
     */
    private class Inflated(private val zip: ZipInputStream, private val budget: Budget) : FilterInputStream(zip) {
        private var entryLeft = Long.MAX_VALUE

        fun nextEntry(): java.util.zip.ZipEntry? = zip.nextEntry

        fun startEntry(limit: Long) {
            entryLeft = limit
        }

        private fun count(n: Long) {
            budget.left -= n
            entryLeft -= n
            if (budget.left < 0 || entryLeft < 0) throw LimitExceeded()
        }

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            val n = super.read(buf, off, len)
            if (n > 0) count(n.toLong())
            return n
        }

        override fun skip(n: Long): Long {
            val buf = ByteArray(8192)
            var left = n
            while (left > 0) {
                val r = read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }
    }

    /** Refuses to deliver more than [max] bytes of the file. */
    private class LimitedInput(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0 && ++count > max) throw LimitExceeded() }
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            val want = minOf(len.toLong(), max - count + 1).toInt().coerceAtLeast(1)
            val n = super.read(buf, off, want)
            if (n > 0) {
                count += n
                if (count > max) throw LimitExceeded()
            }
            return n
        }
    }

    private fun readEntry(zip: InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            if (out.size() + n > max) throw HistoryException.BadContent("entry too long")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Calls [action] for every line (UTF-8) of the entry; a line longer than [HistoryLimits.maxLineBytes] fails the import. */
    private fun drainLines(zip: InputStream, action: (String) -> Unit) {
        val line = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val b = buf[i]
                if (b == '\n'.code.toByte()) {
                    if (line.size() > 0) action(line.toString(Charsets.UTF_8.name()))
                    line.reset()
                } else {
                    line.write(b.toInt())
                    if (line.size() > limits.maxLineBytes) throw HistoryException.BadContent("line too long")
                }
            }
        }
        if (line.size() > 0) action(line.toString(Charsets.UTF_8.name()))
    }

    private fun drain(input: InputStream) {
        val buf = ByteArray(64 * 1024)
        while (input.read(buf) >= 0) Unit
    }

    private fun copyLimited(input: InputStream, target: File, max: Long) {
        var total = 0L
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw HistoryException.TooLarge()
                out.write(buf, 0, n)
            }
        }
    }

    private fun safeId(id: String): Boolean = id.length in 1..MAX_ID && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private companion object {
        const val BATCH = 500
        const val MAX_ID = 128
        const val MAX_TEXT_CHARS = 100_000
        const val MAX_ATTACHMENTS = 10
        const val MAX_REACTIONS = 200
        const val MAX_THUMBNAIL_CHARS = 16 * 1024
        val KINDS = setOf("image", "video", "voice", "file")
        val MEDIA_NAME = Regex("media/[0-9]{1,9}")
        val MIME = Regex("[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,96}")
    }
}
