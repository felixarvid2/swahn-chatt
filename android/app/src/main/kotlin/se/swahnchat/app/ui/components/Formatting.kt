package se.swahnchat.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Text formatting in messages, purely presentation: the text stays plain on the wire, and the markers are only interpreted
 * (and hidden) when it is shown. Search and notifications keep using the text as it was written.
 *
 * The rules are WhatsApp's:
 * - `*bold*`, `_italic_`, `~strikethrough~`, `` `code` `` and a ``` ```block``` ``` of code.
 * - A marker opens only where a word could start (not right after a letter or digit, so `snake_case_name` and `2*3*4` stay as
 *   they are) and only when text that is not a space follows it; it closes only after text that is not a space, and not
 *   directly before a letter or digit. This is what "the markers must hug the text" means.
 * - A span never crosses a line break (only a code block does). Spans of different kinds may be nested (`*_both_*`); what
 *   is inside code is shown as it is.
 * - A marker without a partner is shown as typed. A backslash in front of a marker (`\*`) shows the marker and no formatting.
 * - Web addresses, e-mail addresses and phone numbers are not touched: a marker inside one is just a character.
 */
enum class FormatStyle { BOLD, ITALIC, STRIKE, CODE }

/** [start] until [end] (exclusive) of [Formatted.text] is shown in [style]; [block] marks a fenced code block. */
data class FormatSpan(val start: Int, val end: Int, val style: FormatStyle, val block: Boolean = false)

/** The text as it is shown (markers removed) and the styled stretches of it. */
data class Formatted(val text: String, val spans: List<FormatSpan>)

private const val MARKERS = "*_~`"
private const val FENCE = "```"

/** Longer texts and longer lines are not formatted (the scan is quadratic in the worst case, and nobody formats a wall of text). */
private const val MAX_FORMATTED_CHARS = 20_000
private const val MAX_FORMATTED_LINE = 2_000

/** Interprets the markers of [raw]; see [FormatStyle] for the rules. */
@Suppress("LoopWithTooManyJumpStatements") // scanners: every branch consumes input and moves on
fun parseFormatting(raw: String): Formatted {
    if (raw.length > MAX_FORMATTED_CHARS || raw.none { it in MARKERS || it == '\\' }) return Formatted(raw, emptyList())
    val protected = protectedRanges(raw)
    val out = StringBuilder(raw.length)
    val spans = ArrayList<FormatSpan>()
    fun isProtected(i: Int) = protected.any { i >= it.first && i < it.last }

    var pos = 0
    while (pos < raw.length) {
        // a fenced block comes first: nothing inside it is interpreted
        val open = nextFence(raw, pos, ::isProtected)
        val segmentEnd = open ?: raw.length
        var closeAt = -1
        if (open != null) {
            closeAt = nextFence(raw, open + FENCE.length, ::isProtected) ?: -1
            val inner = if (closeAt > 0) raw.substring(open + FENCE.length, closeAt) else ""
            if (closeAt < 0 || inner.isBlank()) {
                // no closing fence, or nothing in it: the three backticks are text; go on after them
                inlineLines(raw, pos, open, ::isProtected, out, spans)
                out.append(FENCE)
                pos = open + FENCE.length
                continue
            }
        }
        inlineLines(raw, pos, segmentEnd, ::isProtected, out, spans)
        if (open == null) break
        val body = raw.substring(open + FENCE.length, closeAt).removePrefix("\n").removeSuffix("\n")
        val start = out.length
        out.append(body)
        spans += FormatSpan(start, out.length, FormatStyle.CODE, block = true)
        pos = closeAt + FENCE.length
    }
    return Formatted(out.toString(), spans)
}

/** The text of [raw] as it is shown, without markers (for screen readers and previews). */
fun plainFormatted(raw: String): String = parseFormatting(raw).text

/** The address ranges (start until end) that markers must leave alone; sentence-ending marker characters do not belong to them. */
private fun protectedRanges(raw: String): List<IntRange> = findLinks(raw).map { l ->
    var end = l.end
    while (end > l.start + 1 && raw[end - 1] in MARKERS) end--
    l.start until end
}

private fun nextFence(raw: String, from: Int, isProtected: (Int) -> Boolean): Int? {
    var i = raw.indexOf(FENCE, from)
    while (i >= 0 && isProtected(i)) i = raw.indexOf(FENCE, i + 1)
    return i.takeIf { it >= 0 }
}

/** Formats [from] until [to] of [raw] line by line (a span never crosses a line). */
private fun inlineLines(
    raw: String,
    from: Int,
    to: Int,
    isProtected: (Int) -> Boolean,
    out: StringBuilder,
    spans: MutableList<FormatSpan>,
) {
    var lineStart = from
    while (lineStart <= to) {
        val nl = raw.indexOf('\n', lineStart).let { if (it < 0 || it >= to) to else it }
        if (nl - lineStart > MAX_FORMATTED_LINE) out.append(raw, lineStart, nl) else inline(raw, lineStart, nl, isProtected, out, spans)
        if (nl < to) out.append('\n')
        lineStart = nl + 1
    }
}

private fun styleOf(marker: Char) = when (marker) {
    '*' -> FormatStyle.BOLD
    '_' -> FormatStyle.ITALIC
    '~' -> FormatStyle.STRIKE
    else -> FormatStyle.CODE
}

@Suppress("LoopWithTooManyJumpStatements") // scanners: every branch consumes input and moves on
private fun inline(raw: String, from: Int, to: Int, isProtected: (Int) -> Boolean, out: StringBuilder, spans: MutableList<FormatSpan>) {
    var i = from
    while (i < to) {
        val c = raw[i]
        if (c == '\\' && i + 1 < to && raw[i + 1] in MARKERS && !isProtected(i)) {
            out.append(raw[i + 1]) // escaped: the marker as a plain character
            i += 2
            continue
        }
        if (c in MARKERS && !isProtected(i) && canOpen(raw, i, from, to)) {
            val j = findClose(raw, i, to, isProtected)
            if (j > 0) {
                val start = out.length
                if (c == '`') out.append(raw, i + 1, j) else inline(raw, i + 1, j, isProtected, out, spans)
                spans += FormatSpan(start, out.length, styleOf(c))
                i = j + 1
                continue
            }
        }
        out.append(c)
        i++
    }
}

private fun canOpen(raw: String, i: Int, from: Int, to: Int): Boolean {
    if (i + 1 >= to) return false
    val next = raw[i + 1]
    if (next.isWhitespace() || next == raw[i]) return false
    return i == from || !raw[i - 1].isLetterOrDigit()
}

/** The index of the marker that closes the one at [i], or -1. */
@Suppress("LoopWithTooManyJumpStatements") // scanners: every branch consumes input and moves on
private fun findClose(raw: String, i: Int, to: Int, isProtected: (Int) -> Boolean): Int {
    val c = raw[i]
    for (j in i + 2 until to) {
        if (raw[j] != c || isProtected(j)) continue
        val before = raw[j - 1]
        if (before.isWhitespace() || before == '\\') continue
        if (j + 1 < to && raw[j + 1].isLetterOrDigit()) continue
        return j
    }
    return -1
}

/** A person named with `@` in a message: [name] is what the picker wrote after the `@`. */
data class MentionName(val userId: String, val name: String, val isMe: Boolean)

/** A stretch of the shown text that names [userId]. */
data class MentionSpan(val start: Int, val end: Int, val userId: String, val isMe: Boolean)

/**
 * Where [mentions] are named in [text] (`@Name`, not part of a longer word; the longest name wins where names overlap, and
 * each stretch is claimed once).
 */
fun findMentions(text: String, mentions: List<MentionName>): List<MentionSpan> {
    if (mentions.isEmpty() || !text.contains('@')) return emptyList()
    val found = ArrayList<MentionSpan>()
    for (m in mentions.filter { it.name.isNotBlank() }.sortedByDescending { it.name.length }) {
        val token = "@" + m.name
        var at = text.indexOf(token)
        while (at >= 0) {
            val end = at + token.length
            val startsWord = at == 0 || !text[at - 1].isLetterOrDigit()
            val endsWord = end >= text.length || !text[end].isLetterOrDigit()
            if (startsWord && endsWord && found.none { at < it.end && it.start < end }) found += MentionSpan(at, end, m.userId, m.isMe)
            at = text.indexOf(token, at + 1)
        }
    }
    return found.sortedBy { it.start }
}

/** Colours and callbacks for [formatMessage]. */
class MessageTextStyle(
    val linkStyle: SpanStyle,
    val codeBackground: Color,
    val mentionStyle: SpanStyle,
    val myMentionStyle: SpanStyle,
)

/**
 * [raw] as it is shown: formatting applied, links and `@` mentions marked. A link only calls [onLink] with its address (the
 * screen asks the user before anything is opened); a tap on a mention calls [onMention] with the user.
 */
fun formatMessage(
    raw: String,
    style: MessageTextStyle,
    mentions: List<MentionName> = emptyList(),
    onLink: (String) -> Unit = {},
    onMention: (String) -> Unit = {},
): AnnotatedString {
    val formatted = parseFormatting(raw)
    val links = findLinks(formatted.text)
    val named = findMentions(formatted.text, mentions)
    if (formatted.spans.isEmpty() && links.isEmpty() && named.isEmpty()) return AnnotatedString(formatted.text)
    return buildAnnotatedString {
        append(formatted.text)
        for (s in formatted.spans) {
            addStyle(
                when (s.style) {
                    FormatStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    FormatStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    FormatStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    FormatStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = style.codeBackground)
                },
                s.start, s.end,
            )
        }
        for (l in links) {
            addLink(LinkAnnotation.Url(l.url, TextLinkStyles(style.linkStyle)) { onLink(l.url) }, l.start, l.end)
        }
        for (m in named) {
            val look = TextLinkStyles(if (m.isMe) style.myMentionStyle else style.mentionStyle)
            addLink(LinkAnnotation.Clickable("mention:${m.userId}", look) { onMention(m.userId) }, m.start, m.end)
        }
    }
}
