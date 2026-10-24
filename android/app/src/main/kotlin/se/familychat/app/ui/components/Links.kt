package se.familychat.app.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Links in message text. Only four kinds are ever recognised (`http`, `https`, `mailto`, `tel`); anything else, such as
 * `javascript:` or `intent:`, stays plain text. Nothing is fetched or previewed (a preview would make the phone contact
 * the site before the user decided to, see SECURITY.md); a tap only asks [BubbleActions.onOpenLink] to show the
 * confirmation dialog.
 */
enum class LinkKind { WEB, EMAIL, PHONE }

/** A link found in a text: [start] until [end] (exclusive) is what the reader sees; [url] is what a tap would open. */
data class LinkSpan(val start: Int, val end: Int, val url: String, val kind: LinkKind)

private const val MAX_LINKS = 20

private val WEB = Regex("""\bhttps?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
private val MAILTO = Regex("""\bmailto:[^\s<>"']+""", RegexOption.IGNORE_CASE)
private val TEL = Regex("""\btel:\+?[0-9][0-9\-()]*""", RegexOption.IGNORE_CASE)
private val EMAIL = Regex("""(?<![\w.%+\-@])[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+""")
private val PHONE = Regex("""(?<![\w+/.\-])\+?\d[\d ()\-]{5,18}\d(?![\w])""")

private const val TRAILING = ".,;:!?'\")]}>"

/** Cuts sentence punctuation (and an unbalanced closing bracket) off the end of a match: "see https://a.se/x)." */
private fun trimmedEnd(text: String, start: Int, end: Int): Int {
    var e = end
    while (e > start) {
        val c = text[e - 1]
        val unbalanced = when (c) {
            ')' -> text.substring(start, e).count { it == '(' } < text.substring(start, e).count { it == ')' }
            ']' -> text.substring(start, e).count { it == '[' } < text.substring(start, e).count { it == ']' }
            '}' -> text.substring(start, e).count { it == '{' } < text.substring(start, e).count { it == '}' }
            else -> c in TRAILING
        }
        if (!unbalanced) break
        e--
    }
    return e
}

/** scheme://[userinfo@]host[:port] - parsed by hand because java.net.URI rejects characters people do type in links. */
private val AUTHORITY = Regex("""^https?://(?:([^/?#@\s]*)@)?(\[[0-9A-Fa-f:.]+]|[^/?#:@\s]+)(?::\d*)?(?:[/?#]|$)""", RegexOption.IGNORE_CASE)

private fun isWebUrl(url: String): Boolean = AUTHORITY.find(url)?.let { it.groupValues[2].isNotEmpty() } == true

private fun digitsOf(s: String) = s.count { it.isDigit() }

/** Finds the links of [text], in order, without overlaps (at most 20). */
fun findLinks(text: String): List<LinkSpan> {
    if (text.length < 4) return emptyList()
    val found = ArrayList<LinkSpan>()
    fun free(start: Int, end: Int) = found.none { start < it.end && it.start < end }

    for (m in WEB.findAll(text)) {
        val end = trimmedEnd(text, m.range.first, m.range.last + 1)
        val url = text.substring(m.range.first, end)
        if (isWebUrl(url)) found += LinkSpan(m.range.first, end, url, LinkKind.WEB)
    }
    for (m in MAILTO.findAll(text)) {
        val end = trimmedEnd(text, m.range.first, m.range.last + 1)
        val address = text.substring(m.range.first + "mailto:".length, end)
        if (EMAIL.matches(address) && free(m.range.first, end)) found += LinkSpan(m.range.first, end, "mailto:$address", LinkKind.EMAIL)
    }
    for (m in TEL.findAll(text)) {
        val end = trimmedEnd(text, m.range.first, m.range.last + 1)
        if (digitsOf(text.substring(m.range.first, end)) >= 3 && free(m.range.first, end)) {
            found += LinkSpan(m.range.first, end, text.substring(m.range.first, end).lowercase(), LinkKind.PHONE)
        }
    }
    for (m in EMAIL.findAll(text)) {
        val end = trimmedEnd(text, m.range.first, m.range.last + 1)
        if (free(m.range.first, end)) found += LinkSpan(m.range.first, end, "mailto:" + text.substring(m.range.first, end), LinkKind.EMAIL)
    }
    for (m in PHONE.findAll(text)) {
        val raw = m.value.trimEnd()
        val end = m.range.first + raw.length
        // numbers start with + or 0 (national format) and have a plausible length; this keeps dates and amounts out
        val plausible = (raw.startsWith("+") || raw.startsWith("0")) && digitsOf(raw) in 8..15 && !raw.contains("  ")
        if (plausible && free(m.range.first, end)) {
            found += LinkSpan(m.range.first, end, "tel:" + raw.filter { it.isDigit() || it == '+' }, LinkKind.PHONE)
        }
    }
    return found.sortedBy { it.start }.take(MAX_LINKS)
}

/**
 * [text] with its links as `LinkAnnotation.Url`s. A listener is attached, so a tap never opens anything by itself: the
 * listener gets the address and the screen asks the user first.
 */
fun linkify(text: String, style: SpanStyle, onLink: (String) -> Unit): AnnotatedString {
    val links = findLinks(text)
    if (links.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (l in links) {
            addLink(LinkAnnotation.Url(l.url, TextLinkStyles(style)) { onLink(l.url) }, l.start, l.end)
        }
    }
}

/** What the confirmation dialog shows for an address. */
data class LinkInfo(
    val url: String,
    val kind: LinkKind,
    /** The part a person should check: the host of a web address, the address of an e-mail, the number of a phone link. */
    val target: String,
    /** The address hides something: user info before the host, or characters outside ASCII in the host. */
    val suspicious: Boolean,
)

/** Describes [url] for the dialog; null if it is not one of the four kinds (then nothing may be opened). */
fun describeLink(url: String): LinkInfo? {
    val lower = url.lowercase()
    return when {
        lower.startsWith("http://") || lower.startsWith("https://") -> {
            val m = AUTHORITY.find(url) ?: return null
            val host = m.groupValues[2].takeIf { it.isNotEmpty() } ?: return null
            val hidden = m.groupValues[1].isNotEmpty() || host.any { it.code > 127 } || host.startsWith("xn--", true) || host.contains(".xn--", true)
            LinkInfo(url, LinkKind.WEB, host, hidden)
        }
        lower.startsWith("mailto:") -> LinkInfo(url, LinkKind.EMAIL, url.substring("mailto:".length), false)
        lower.startsWith("tel:") -> LinkInfo(url, LinkKind.PHONE, url.substring("tel:".length), false)
        else -> null
    }
}
