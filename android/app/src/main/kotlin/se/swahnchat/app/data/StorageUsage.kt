package se.swahnchat.app.data

import se.swahnchat.core.client.DownloadState
import se.swahnchat.core.client.LocalStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The folders and the database file the storage screen measures; the engine and the app are configured from the same one. */
data class StorageDirs(
    /** Decrypted downloads (the engine's `attachmentDir`); avatars live in a subfolder. */
    val attachments: File,
    /** Resumable partial downloads and encrypted uploads in progress (the engine's `tempDir`). */
    val transfer: File,
    /** `Context.cacheDir`: image cache, copies made for sharing. */
    val cache: File,
    /** The SQLCipher database file; `-wal`, `-shm` and `-journal` next to it count too. */
    val database: File,
    /** Copies of files waiting to be sent or already sent from this phone (`AttachmentPreparer.outgoingDir`); also where forwards are copied. */
    val outgoing: File? = null,
)

/** Downloaded media of one conversation. */
data class ConversationStorage(val conversationId: String, val mediaBytes: Long, val fileCount: Int)

data class StorageReport(
    /** Conversations that have downloaded media, the biggest first. */
    val conversations: List<ConversationStorage>,
    /** All downloaded media; the sum of [conversations]. */
    val mediaBytes: Long,
    /** In the download folder but belonging to no message: profile pictures, leftovers of conversations that are gone. */
    val otherBytes: Long,
    val databaseBytes: Long,
    /** What "clear cache" can free. */
    val cacheBytes: Long,
    /** Media of imported messages: not in [conversations] or [mediaBytes], and never cleared (it cannot be downloaded again). */
    val importedBytes: Long = 0,
) {
    /** Everything the app keeps on the phone that this screen knows about. */
    val totalBytes: Long get() = mediaBytes + importedBytes + otherBytes + databaseBytes + cacheBytes
}

/**
 * Measures and clears what the app keeps on the phone. Media is counted per conversation from the messages (an attachment
 * counts when it is downloaded and its file is inside the download folder: the files of messages you sent are your own
 * copies elsewhere and are not the app's cache); the folder itself is measured separately, so nothing is missed or counted twice.
 */
@Singleton
class StorageUsage @Inject constructor(private val store: LocalStore, private val dirs: StorageDirs) {
    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun report(): StorageReport {
        val seen = HashSet<String>()
        val perConversation = LinkedHashMap<String, LongArray>() // bytes, files
        var imported = 0L
        for (m in store.messagesWithAttachments()) for (a in m.attachments) {
            val file = a.localPath?.let(::File) ?: continue
            if (a.downloadState != DownloadState.DONE || !file.isFile || !isInside(file, dirs.attachments)) continue
            if (!seen.add(file.canonicalPath)) continue // one file, one count
            if (m.imported) {
                imported += file.length()
                continue
            }
            val acc = perConversation.getOrPut(m.conversationId) { LongArray(2) }
            acc[0] += file.length(); acc[1]++
        }
        val conversations = perConversation.map { (id, v) -> ConversationStorage(id, v[0], v[1].toInt()) }.sortedByDescending { it.mediaBytes }
        val media = conversations.sumOf { it.mediaBytes }
        return StorageReport(
            conversations = conversations,
            mediaBytes = media,
            otherBytes = (sizeOf(dirs.attachments) - media - imported).coerceAtLeast(0),
            databaseBytes = DB_SUFFIXES.sumOf { File(dirs.database.path + it).let { f -> if (f.isFile) f.length() else 0L } },
            cacheBytes = sizeOf(dirs.cache) + stalePartials().sumOf { it.length() },
            importedBytes = imported,
        )
    }

    /**
     * Empties the cache folder and removes partial downloads nobody has touched for [PARTIAL_GRACE_MS] (a running transfer
     * keeps its file fresh, so it is left alone). Returns the bytes freed.
     */
    fun clearCache(): Long {
        var freed = 0L
        dirs.cache.listFiles()?.forEach { child ->
            freed += sizeOf(child)
            child.deleteRecursively()
        }
        stalePartials().forEach { f ->
            val size = f.length()
            if (f.delete()) freed += size
        }
        return freed
    }

    private fun stalePartials(): List<File> {
        val cutoff = System.currentTimeMillis() - PARTIAL_GRACE_MS
        return dirs.transfer.listFiles { f -> f.isFile && f.name.endsWith(".part") && f.lastModified() < cutoff }.orEmpty().toList()
    }

    private fun isInside(file: File, dir: File) = file.canonicalPath.startsWith(dir.canonicalPath + File.separator)

    companion object {
        private val DB_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
        const val PARTIAL_GRACE_MS = 10 * 60 * 1000L

        /** Size of a file or of everything below a folder (0 if it does not exist). */
        fun sizeOf(file: File): Long = if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
