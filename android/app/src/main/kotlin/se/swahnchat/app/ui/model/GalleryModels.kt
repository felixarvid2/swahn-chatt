package se.swahnchat.app.ui.model

import androidx.compose.runtime.Immutable
import se.swahnchat.app.ui.components.LinkKind
import se.swahnchat.app.ui.components.findLinks
import se.swahnchat.app.ui.components.plainFormatted
import se.swahnchat.core.client.StoredMessage
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** One photo or video of the conversation's gallery. [thumbnail] is the small JPEG from the (encrypted) message, if it had one. */
@Immutable
class MediaCell(
    val messageId: String,
    val attachmentId: String,
    val video: Boolean,
    val thumbnail: ByteArray?,
    val time: Long,
    val durationMs: Long?,
)

/** The grid of "Bilder och video": month headers between the cells, newest first. */
sealed interface GalleryItem {
    val key: String

    @Immutable
    data class Header(val month: YearMonth) : GalleryItem {
        override val key get() = "m$month"
    }

    @Immutable
    data class Cell(val cell: MediaCell) : GalleryItem {
        override val key get() = "c${cell.messageId}/${cell.attachmentId}"
    }
}

/** A document of the "Filer" list. */
@Immutable
data class FileItem(val messageId: String, val attachmentId: String, val name: String?, val mime: String, val size: Long, val time: Long) {
    val key get() = "f$messageId/$attachmentId"
}

/** A web address of the "Länkar" list, with the message it was written in. */
@Immutable
data class LinkItem(val messageId: String, val url: String, val time: Long, val outgoing: Boolean) {
    val key get() = "l$messageId/$url"
}

private fun StoredMessage.timeOf() = serverTs ?: clientTs

/** The photos and videos of a message, in the order of the message. */
fun StoredMessage.mediaCells(): List<MediaCell> =
    if (deleted || isSystem) emptyList() else attachments.filter { it.kind == "image" || it.kind == "video" }.map {
        MediaCell(id, it.id, it.kind == "video", decodeThumbnail(it.thumbnail), timeOf(), it.durationMs)
    }

/** The documents of a message (voice messages are not documents here). */
fun StoredMessage.fileItems(): List<FileItem> =
    if (deleted || isSystem) emptyList() else attachments.filter { it.kind == "file" }.map {
        FileItem(id, it.id, it.name, it.mime, it.size, timeOf())
    }

/** The web addresses (http and https only) in the text of a message, each once, in order. Read from the local text; nothing is fetched. */
fun StoredMessage.linkItems(): List<LinkItem> {
    val body = text
    if (deleted || isSystem || body.isNullOrBlank()) return emptyList()
    return findLinks(plainFormatted(body)).filter { it.kind == LinkKind.WEB }.map { it.url }.distinct()
        .map { LinkItem(id, it, timeOf(), outgoing) }
}

/**
 * The header that belongs between two neighbouring items of the gallery, which is ordered newest first and shown top to bottom:
 * a header for the month of [after] when [before] is from another month or there is no [before] (the top of the list).
 */
fun monthHeaderBetween(before: GalleryItem?, after: GalleryItem?, zone: ZoneId): GalleryItem.Header? {
    val next = (after as? GalleryItem.Cell)?.cell ?: return null
    val month = monthOf(next.time, zone)
    val previous = (before as? GalleryItem.Cell)?.cell?.let { monthOf(it.time, zone) }
    return if (previous != month) GalleryItem.Header(month) else null
}

fun monthOf(time: Long, zone: ZoneId): YearMonth = YearMonth.from(Instant.ofEpochMilli(time).atZone(zone))
