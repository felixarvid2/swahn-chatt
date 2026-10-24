package se.familychat.app.media

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Checks a decrypted attachment before it is rendered. The server cannot validate message attachments (they are
 * ciphertext to it) and the sender is another person's phone, so what a message claims ("image/jpeg") is verified here:
 * images must really decode as an image of a known type and sane size, videos and voice messages must carry the track the
 * message says. Nothing is rendered or played when this returns false. Generic files are never rendered, only handed to
 * other apps by the user.
 */
object MediaValidator {
    private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "image/avif")
    private const val MAX_PIXELS = 120_000_000L

    fun isValid(file: File, kind: String): Boolean = try {
        if (!file.isFile || file.length() == 0L) false
        else when (kind) {
            "image" -> validImage(file)
            "video" -> hasTrack(file, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            "voice" -> hasTrack(file, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            else -> true
        }
    } catch (e: Exception) {
        false
    }

    private fun validImage(file: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        val type = o.outMimeType?.lowercase() ?: return false
        return type in IMAGE_TYPES && o.outWidth > 0 && o.outHeight > 0 && o.outWidth.toLong() * o.outHeight <= MAX_PIXELS
    }

    private fun hasTrack(file: File, key: Int): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(key) == "yes"
        } finally {
            r.release()
        }
    }
}
