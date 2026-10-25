package se.familychat.app.data

import kotlinx.coroutines.runBlocking
import se.familychat.core.client.DownloadState
import se.familychat.core.client.InMemoryLocalStore
import se.familychat.core.client.LocalAttachment
import se.familychat.core.client.MessageStatus
import se.familychat.core.client.StoredMessage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageUsageTest {
    private val root: File = Files.createTempDirectory("fc-storage").toFile()
    private val dirs = StorageDirs(
        attachments = File(root, "attachments").apply { mkdirs() },
        transfer = File(root, "transfer").apply { mkdirs() },
        cache = File(root, "cache").apply { mkdirs() },
        database = File(root, "databases/familychat.db").apply { parentFile!!.mkdirs() },
    )
    private val store = InMemoryLocalStore()
    private val usage = StorageUsage(store, dirs)

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(dir: File, name: String, size: Int, ageMs: Long = 0): File =
        File(dir, name).also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(size)); if (ageMs > 0) it.setLastModified(System.currentTimeMillis() - ageMs) }

    private suspend fun message(id: String, conv: String, vararg atts: LocalAttachment) = store.upsertMessage(
        StoredMessage(id, conv, "u2", "d", false, MessageStatus.RECEIVED, 1, 1, 1, null, null, atts.toList()),
    )

    private fun att(id: String, path: File?, state: DownloadState = DownloadState.DONE) =
        LocalAttachment(id, "k", "s", 1, "image/jpeg", null, "image", null, null, null, null, path?.absolutePath, state)

    @Test fun `media is counted per conversation, biggest first, and adds up to the total`() = runBlocking {
        message("m1", "c1", att("a1", file(dirs.attachments, "a1", 1000)), att("a2", file(dirs.attachments, "a2", 500)))
        message("m2", "c2", att("a3", file(dirs.attachments, "a3", 4000)))
        message("m3", "c3", att("a4", null, DownloadState.NONE)) // not downloaded: nothing on disk
        val r = usage.report()
        assertEquals(listOf("c2", "c1"), r.conversations.map { it.conversationId })
        assertEquals(listOf(4000L, 1500L), r.conversations.map { it.mediaBytes })
        assertEquals(listOf(1, 2), r.conversations.map { it.fileCount })
        assertEquals(5500L, r.mediaBytes)
        assertEquals(0L, r.otherBytes)
    }

    @Test fun `media of imported messages is shown on its own and is not part of what can be cleared`() = runBlocking {
        message("m1", "c1", att("a1", file(dirs.attachments, "a1", 1000)))
        val importedFile = att("a2", file(dirs.attachments, "a2", 300))
        store.upsertMessage(
            StoredMessage("imp", "c1", "u2", null, false, MessageStatus.RECEIVED, 1, 1, 1, null, null, listOf(importedFile), imported = true),
        )
        val r = usage.report()
        assertEquals(listOf(ConversationStorage("c1", 1000, 1)), r.conversations, "the conversation's row (what clearing frees) has no imported media")
        assertEquals(1000L, r.mediaBytes)
        assertEquals(300L, r.importedBytes)
        assertEquals(0L, r.otherBytes, "and it is not 'other' either")
        assertEquals(1300L, r.totalBytes)
    }

    @Test fun `files outside the download folder, missing files and unfinished downloads are not counted`() = runBlocking {
        val own = file(File(root, "outgoing"), "sent.jpg", 9999) // the phone's own copy of something it sent
        val busy = att("busy", file(dirs.attachments, "busy", 70), DownloadState.DOWNLOADING)
        message("m1", "c1", att("own", own), att("gone", File(dirs.attachments, "deleted-file")), busy)
        assertEquals(emptyList(), usage.report().conversations)
        assertEquals(70L, usage.report().otherBytes, "the folder is measured as it is: the unfinished file shows up as other")
    }

    @Test fun `one file referenced twice is counted once`() = runBlocking {
        val f = file(dirs.attachments, "shared", 300)
        message("m1", "c1", att("a", f)); message("m2", "c2", att("b", f))
        assertEquals(300L, usage.report().mediaBytes)
    }

    @Test fun `profile pictures and leftovers are other, the database and the cache are measured`() = runBlocking {
        message("m1", "c1", att("a1", file(dirs.attachments, "a1", 1000)))
        file(dirs.attachments, "avatars/u1", 250)
        file(dirs.attachments, "orphan", 150)
        file(File(dirs.database.parentFile, ""), "familychat.db", 8000)
        file(File(dirs.database.parentFile, ""), "familychat.db-wal", 2000)
        file(File(dirs.database.parentFile, ""), "other.db", 99999) // another file is not ours
        file(dirs.cache, "image_cache/x", 600)
        file(dirs.cache, "share/photo.jpg", 400)
        val r = usage.report()
        assertEquals(1000L, r.mediaBytes)
        assertEquals(400L, r.otherBytes)
        assertEquals(10_000L, r.databaseBytes)
        assertEquals(1000L, r.cacheBytes)
        assertEquals(1000L + 400 + 10_000 + 1000, r.totalBytes)
    }

    @Test fun `an empty phone reports zeros`() = runBlocking {
        val r = usage.report()
        assertEquals(StorageReport(emptyList(), 0, 0, 0, 0), r)
    }

    @Test fun `clearing the cache empties it, removes old partial downloads and keeps running transfers and media`() = runBlocking {
        val media = file(dirs.attachments, "a1", 1000)
        message("m1", "c1", att("a1", media))
        file(dirs.cache, "image_cache/x", 600)
        file(dirs.cache, "share/photo.jpg", 400)
        val stale = file(dirs.transfer, "dl-old.part", 300, ageMs = StorageUsage.PARTIAL_GRACE_MS + 60_000)
        val running = file(dirs.transfer, "dl-now.part", 200)
        val upload = file(dirs.transfer, "up-1.enc", 100, ageMs = 24 * 3_600_000L) // an upload is not a cache, whatever its age

        assertEquals(1300L, usage.report().cacheBytes)
        assertEquals(1300L, usage.clearCache())

        assertTrue(dirs.cache.isDirectory)
        assertEquals(0, dirs.cache.listFiles()!!.size)
        assertFalse(stale.exists())
        assertTrue(running.exists())
        assertTrue(upload.exists())
        assertTrue(media.exists())
        assertEquals(0L, usage.report().cacheBytes)
        assertEquals(0L, usage.clearCache())
    }
}
