package se.swahnchat.app.data

import androidx.compose.runtime.Immutable

/** When received attachments of one kind are fetched without a tap. */
enum class AutoDownloadMode { ALWAYS, WIFI_ONLY, NEVER }

/**
 * The auto-download settings: one [AutoDownloadMode] per kind of attachment (`image`, `voice`, `video`, `file`, as in
 * `AttachmentKind`). Defaults: photos and voice messages always, videos and files only on an unmetered network.
 */
@Immutable
data class AutoDownloadSettings(
    val images: AutoDownloadMode = AutoDownloadMode.ALWAYS,
    val voice: AutoDownloadMode = AutoDownloadMode.ALWAYS,
    val video: AutoDownloadMode = AutoDownloadMode.WIFI_ONLY,
    val files: AutoDownloadMode = AutoDownloadMode.WIFI_ONLY,
) {
    /** The mode for an attachment [kind]; anything that is not image, voice or video is a file. */
    fun modeFor(kind: String): AutoDownloadMode = when (kind) {
        "image" -> images
        "voice" -> voice
        "video" -> video
        else -> files
    }

    fun with(kind: String, mode: AutoDownloadMode): AutoDownloadSettings = when (kind) {
        "image" -> copy(images = mode)
        "voice" -> copy(voice = mode)
        "video" -> copy(video = mode)
        else -> copy(files = mode)
    }
}

/**
 * Decides whether an attachment is fetched on its own. Tapping the attachment always downloads it, whatever this says; the
 * size limits only apply to the automatic path (so a huge file never starts by itself on someone's data plan).
 */
object AutoDownloadPolicy {
    private const val MB = 1024L * 1024

    /** Largest attachment of [kind] that is fetched automatically. */
    fun maxBytes(kind: String): Long = when (kind) {
        "image" -> 20 * MB
        "voice" -> 8 * MB
        "video" -> 64 * MB
        else -> 32 * MB
    }

    /**
     * @param metered the active network is metered (mobile data, or a Wi-Fi the user marked as metered); an unknown state
     *   counts as metered. "Endast Wi-Fi" means "only when the network is not metered".
     */
    fun shouldDownload(settings: AutoDownloadSettings, kind: String, size: Long, metered: Boolean): Boolean {
        if (size > maxBytes(kind)) return false
        return when (settings.modeFor(kind)) {
            AutoDownloadMode.ALWAYS -> true
            AutoDownloadMode.WIFI_ONLY -> !metered
            AutoDownloadMode.NEVER -> false
        }
    }
}
