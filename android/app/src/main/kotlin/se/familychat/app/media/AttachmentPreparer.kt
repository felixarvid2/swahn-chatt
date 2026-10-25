package se.familychat.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.familychat.core.client.OutgoingAttachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** A picked file is bigger than the 100 MB limit of the server and the engine. */
class AttachmentTooLargeException(val size: Long) : IOException("Attachment is $size bytes")

/** The picked file cannot be read or is not usable (for example a broken image). */
class UnusableAttachmentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Turns what the user picked (content URIs) into files the engine can encrypt and upload.
 *
 * - Photos are decoded with sub-sampling, rotated upright, scaled to at most [MAX_IMAGE_EDGE] pixels on the long edge and
 *   saved as JPEG (quality [JPEG_QUALITY]); a small JPEG thumbnail (at most [MAX_THUMBNAIL_BASE64] characters of base64)
 *   travels inside the encrypted message.
 * - Videos are copied unchanged; width, height and duration come from `MediaMetadataRetriever`, the thumbnail is a frame.
 * - Everything else is copied as a file with its display name.
 *
 * The files are written to app-private storage ([outgoingDir]) and stay until the message is sent.
 */
@Singleton
class AttachmentPreparer @Inject constructor(@ApplicationContext private val context: Context) {
    val outgoingDir: File get() = File(context.filesDir, "outgoing").also { it.mkdirs() }

    suspend fun prepare(uri: Uri): OutgoingAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = (resolver.getType(uri) ?: guessMime(displayName(uri))).lowercase()
        declaredSize(uri)?.let { if (it > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException(it) }
        when {
            mime.startsWith("image/") -> prepareImage(uri)
            mime.startsWith("video/") -> prepareVideo(uri, mime)
            else -> prepareFile(uri, mime)
        }
    }

    // --- photos ---------------------------------------------------------------------------------------------------------

    private fun prepareImage(uri: Uri): OutgoingAttachment {
        val bitmap = decodeUpright(uri, MAX_IMAGE_EDGE) ?: throw UnusableAttachmentException("Cannot decode image")
        try {
            val out = newFile("img", "jpg")
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            return OutgoingAttachment(
                file = out, mime = "image/jpeg", kind = "image", name = baseName(displayName(uri)) + ".jpg",
                width = bitmap.width, height = bitmap.height, thumbnailBase64 = thumbnailOf(bitmap),
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** Decoded, upright, longest edge at most [maxEdge]; transparency is flattened onto white (JPEG has none). */
    internal fun decodeUpright(uri: Uri, maxEdge: Int): Bitmap? {
        val resolver = context.contentResolver
        val decoded: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF orientation itself.
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val edge = max(info.size.width, info.size.height)
                    if (edge > maxEdge) {
                        val scale = maxEdge.toFloat() / edge
                        decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                    }
                }
            } catch (e: Exception) {
                return null
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val raw = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val upright = rotateByExif(uri, raw)
            val edge = max(upright.width, upright.height)
            if (edge > maxEdge) {
                val scale = maxEdge.toFloat() / edge
                Bitmap.createScaledBitmap(upright, (upright.width * scale).roundToInt().coerceAtLeast(1), (upright.height * scale).roundToInt().coerceAtLeast(1), true)
                    .also { if (it !== upright) upright.recycle() }
            } else {
                upright
            }
        }
        return flatten(decoded)
    }

    private fun rotateByExif(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun flatten(src: Bitmap): Bitmap {
        if (!src.hasAlpha() && src.config == Bitmap.Config.ARGB_8888) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        src.recycle()
        return out
    }

    // --- video ----------------------------------------------------------------------------------------------------------

    private fun prepareVideo(uri: Uri, mime: String): OutgoingAttachment {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp4"
        val out = copyToOutgoing(uri, "vid", ext)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(out.absolutePath)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                out.delete()
                throw UnusableAttachmentException("Not a video")
            }
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val frame = try { retriever.getFrameAtTime(0) } catch (e: RuntimeException) { null }
            val thumb = frame?.let { f -> thumbnailOf(f).also { f.recycle() } }
            return OutgoingAttachment(out, mime, "video", displayName(uri) ?: out.name, w, h, duration, thumb)
        } catch (e: RuntimeException) {
            out.delete()
            throw UnusableAttachmentException("Cannot read video", e)
        } finally {
            retriever.release()
        }
    }

    // --- other files ----------------------------------------------------------------------------------------------------

    private fun prepareFile(uri: Uri, mime: String): OutgoingAttachment {
        val name = displayName(uri) ?: "file"
        val ext = name.substringAfterLast('.', "").takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) } ?: "bin"
        val out = copyToOutgoing(uri, "file", ext)
        return OutgoingAttachment(out, mime.ifEmpty { "application/octet-stream" }, "file", name)
    }

    /** A profile or group picture: upright JPEG, longest edge at most [AVATAR_EDGE], always well under the server's 2 MiB. */
    suspend fun avatarJpeg(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = decodeUpright(uri, AVATAR_EDGE) ?: throw UnusableAttachmentException("Cannot decode image")
        try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** A recorded voice message (already in app storage). */
    fun voice(file: File, durationMs: Long): OutgoingAttachment =
        OutgoingAttachment(file, "audio/mp4", "voice", file.name, durationMs = durationMs)

    // --- helpers --------------------------------------------------------------------------------------------------------

    /** JPEG preview of at most [MAX_THUMBNAIL_BASE64] base64 characters, or null if even the smallest is too big. */
    internal fun thumbnailOf(src: Bitmap): String? {
        var edge = THUMBNAIL_EDGE
        while (edge >= 32) {
            val scale = edge.toFloat() / max(src.width, src.height)
            val t = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt().coerceAtLeast(1), (src.height * scale).roundToInt().coerceAtLeast(1), true) else src
            var quality = 70
            while (quality >= 30) {
                val bytes = ByteArrayOutputStream().also { t.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                if (encoded.length <= MAX_THUMBNAIL_BASE64) {
                    if (t !== src) t.recycle()
                    return encoded
                }
                quality -= 10
            }
            if (t !== src) t.recycle()
            edge = edge * 3 / 4
        }
        return null
    }

    private fun copyToOutgoing(uri: Uri, prefix: String, ext: String): File {
        val out = newFile(prefix, ext)
        var total = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException(total)
                        output.write(buf, 0, n)
                    }
                }
            } ?: throw UnusableAttachmentException("Cannot open the file")
        } catch (e: IOException) {
            out.delete()
            throw e
        }
        return out
    }

    private fun newFile(prefix: String, ext: String) = File(outgoingDir, "$prefix-${UUID.randomUUID()}.$ext")

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    private fun declaredSize(uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun guessMime(name: String?): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun baseName(name: String?): String = (name ?: "photo").substringBeforeLast('.').take(60).ifEmpty { "photo" }

    /** Logout: everything prepared for sending and everything shared out of the app goes. */
    fun deleteAll() {
        outgoingDir.deleteRecursively()
        File(context.cacheDir, "share").deleteRecursively()
    }

    /** Deletes the copy of a sent attachment at [path] if it is one of ours (in [outgoingDir]); used when a message is deleted on this phone. */
    fun deleteOutgoingCopy(path: String?) {
        val f = File(path ?: return)
        if (f.isFile && f.canonicalFile.parentFile == outgoingDir.canonicalFile) f.delete()
    }

    /** Deletes files in [outgoingDir] that nothing refers to ([keep]) and that are older than a week. */
    fun cleanup(keep: Set<String>) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        outgoingDir.listFiles()?.forEach { f -> if (f.absolutePath !in keep && f.lastModified() < cutoff) f.delete() }
    }

    companion object {
        const val MAX_IMAGE_EDGE = 2048
        const val AVATAR_EDGE = 512
        const val JPEG_QUALITY = 85
        const val THUMBNAIL_EDGE = 160
        const val MAX_THUMBNAIL_BASE64 = 10 * 1024
        const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
    }
}
