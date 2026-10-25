package se.swahnchat.app.media

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What another app handed to the share target: some text, some files, and how many things were refused on the way in. */
class ShareRequest(val text: String?, val uris: List<Uri>, val refused: Int, val truncated: Boolean) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && uris.isEmpty()
}

/** A share that has been copied into the app's own storage: nothing refers to the other app any more. */
class PendingShare(val text: String?, val files: List<File>)

/**
 * Shares waiting for the user to pick the conversation they go to. Kept in memory only: a share that outlives the process
 * is dropped (its files are cleaned up with the staging folder).
 */
@Singleton
class PendingShares @Inject constructor() {
    private val shares = HashMap<String, PendingShare>()
    private val _arrivals = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** The conversation a share was just handed to, for a conversation screen that is open already. */
    val arrivals: kotlinx.coroutines.flow.SharedFlow<String> = _arrivals

    @Synchronized fun put(conversationId: String, share: PendingShare) {
        shares[conversationId] = share
        _arrivals.tryEmit(conversationId)
    }

    /** The share for [conversationId], once. */
    @Synchronized fun take(conversationId: String): PendingShare? = shares.remove(conversationId)
}

/** A shared file could not be taken over. */
class ShareRefusedException(message: String) : IOException(message)

/**
 * Receiving from other apps (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`). Everything in the intent is the sending app's word:
 *
 * - Only `content://` URIs are read. `file://` (the sender's private paths, or ours), `android.resource:`, `http:` and the
 *   rest are refused, and so are URIs of this app's own providers (another app must not be able to make us read, and send on,
 *   what only we can open).
 * - At most [MAX_FILES] files; text is cut at [MAX_TEXT_BYTES].
 * - Files are copied through the [ContentResolver] with a hard byte limit (never reading more than the limit plus one
 *   buffer), under a sanitised name inside a folder of ours, before the activity ends: the access the sender granted ends
 *   with it. The copies then go through the same preparation as files the user picked.
 */
object ShareIntake {
    const val MAX_FILES = 10
    const val MAX_TEXT_BYTES = 40_000
    private const val BUFFER = 64 * 1024

    /** Reads the intent without touching any file. */
    fun parse(intent: Intent?, ownPackage: String): ShareRequest {
        if (intent == null) return ShareRequest(null, emptyList(), 0, false)
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return ShareRequest(null, emptyList(), 0, false)
        val candidates = LinkedHashSet<Uri>()
        if (action == Intent.ACTION_SEND) {
            streamExtra(intent)?.let { candidates += it }
        } else {
            candidates += streamListExtra(intent)
        }
        intent.clipData?.let { clip -> candidates += clipUris(clip) }
        var refused = 0
        val accepted = ArrayList<Uri>()
        for (u in candidates) {
            if (acceptable(u, ownPackage)) accepted += u else refused++
        }
        val truncated = accepted.size > MAX_FILES
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { limitBytes(it, MAX_TEXT_BYTES) }
        return ShareRequest(text, accepted.take(MAX_FILES), refused + (accepted.size - accepted.take(MAX_FILES).size), truncated)
    }

    /**
     * True for `content://` URIs that are not served by this app. The authority may carry a user id in front
     * (`content://0@se.swahnchat.app.fileprovider/...` reaches the same provider), so only what follows the last `@` is compared.
     */
    fun acceptable(uri: Uri, ownPackage: String): Boolean {
        if (!"content".equals(uri.scheme, ignoreCase = true)) return false
        val authority = uri.authority?.substringAfterLast('@')?.lowercase() ?: return false
        return authority.isNotEmpty() && authority != ownPackage.lowercase() && !authority.startsWith("${ownPackage.lowercase()}.")
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()

    private fun clipUris(clip: ClipData): List<Uri> = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }

    private fun limitBytes(text: String, max: Int): String {
        if (text.toByteArray().size <= max) return text
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray().size
            if (bytes + n > max) break
            bytes += n
            i += Character.charCount(cp)
        }
        return text.substring(0, i)
    }

    /**
     * Copies [uri] into a fresh folder under [stagingDir] and returns the file. The name is [ShareFiles.safeName] of the
     * display name the source reports (with an extension from the MIME type if it has none).
     *
     * @throws ShareRefusedException if the source is bigger than [maxBytes] (also when it lies about its size) or cannot be read
     */
    @Suppress("ThrowsCount") // each refusal is a different reason
    fun stage(resolver: ContentResolver, uri: Uri, stagingDir: File, maxBytes: Long): File {
        val declared = declaredSize(resolver, uri)
        if (declared != null && declared > maxBytes) throw ShareRefusedException("The file is larger than the limit")
        val mime = resolver.getType(uri)
        val dir = File(stagingDir, UUID.randomUUID().toString()).also { it.mkdirs() }
        val name = nameWithExtension(ShareFiles.safeName(displayName(resolver, uri), fallback = "file"), mime)
        val out = File(dir, name)
        if (out.canonicalFile.parentFile != dir.canonicalFile) {
            dir.deleteRecursively()
            throw ShareRefusedException("Unsafe file name")
        }
        try {
            val input = resolver.openInputStream(uri) ?: throw ShareRefusedException("The file cannot be opened")
            input.use { copyLimited(it, out, maxBytes) }
        } catch (e: ShareRefusedException) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            // a provider of another app may fail in any way (no access, a vanished file, a broken stream): all of them are a refusal
            dir.deleteRecursively()
            throw ShareRefusedException("The file cannot be read: ${e.javaClass.simpleName}")
        }
        return out
    }

    internal fun copyLimited(input: InputStream, out: File, maxBytes: Long) {
        var total = 0L
        out.outputStream().use { sink ->
            val buf = ByteArray(BUFFER)
            var n = readAtMost(input, buf, maxBytes + 1 - total)
            while (n > 0) {
                total += n
                if (total > maxBytes) throw ShareRefusedException("The file is larger than the limit")
                sink.write(buf, 0, n)
                n = readAtMost(input, buf, maxBytes + 1 - total)
            }
        }
    }

    /** Reads at most [remaining] bytes (never more than the buffer holds); -1 at the end or when nothing may be read any more. */
    private fun readAtMost(input: InputStream, buf: ByteArray, remaining: Long): Int =
        if (remaining <= 0) -1 else input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())

    internal fun nameWithExtension(name: String, mime: String?): String {
        if (name.substringAfterLast('.', "").let { it.isNotEmpty() && it.length <= 8 }) return name
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.lowercase()) } ?: return name
        return ShareFiles.safeName("$name.$ext")
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    private fun declaredSize(resolver: ContentResolver, uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    } catch (e: Exception) {
        null
    }
}
