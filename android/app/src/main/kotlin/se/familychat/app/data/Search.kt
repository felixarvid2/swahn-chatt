package se.familychat.app.data

import se.familychat.app.data.db.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** What the user typed, turned into something safe for the FTS4 index and for highlighting. */
object SearchQuery {
    private val word = Regex("[\\p{L}\\p{N}]+")
    private const val MAX_TERMS = 8
    private const val MAX_TERM_LENGTH = 64

    /** The words of [input] (the tokenizer of the index splits on everything that is not a letter or digit as well). */
    fun terms(input: String): List<String> =
        word.findAll(input).map { it.value.take(MAX_TERM_LENGTH) }.take(MAX_TERMS).toList()

    /**
     * The FTS4 MATCH expression: every word is a quoted prefix phrase (`"hej*"`), all must occur. Quoting keeps `OR`, `NOT`,
     * `-` and unbalanced quotes in the input from being operators or syntax errors. Null when there is nothing to search for.
     */
    fun toMatch(input: String): String? =
        terms(input).takeIf { it.isNotEmpty() }?.joinToString(" ") { "\"$it*\"" }
}

/** A piece of message text to show, with the parts to emphasise (ranges into [text]). */
data class Snippet(val text: String, val highlights: List<IntRange>)

object Snippets {
    private const val ELLIPSIS = "…"

    /**
     * About [maxLength] characters of [text] around the first word that starts with one of [terms] (case-insensitive, like
     * the index), with every such word start marked. Line breaks become spaces.
     */
    fun of(text: String, terms: List<String>, maxLength: Int = 120, lead: Int = 30): Snippet {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        val matches = mutableListOf<IntRange>()
        for (w in Regex("[\\p{L}\\p{N}]+").findAll(flat)) {
            val term = terms.firstOrNull { w.value.startsWith(it, ignoreCase = true) } ?: continue
            matches += w.range.first until (w.range.first + term.length)
        }
        var start = 0
        if (matches.isNotEmpty() && matches.first().first > lead) {
            start = matches.first().first - lead
            // begin at a word boundary rather than in the middle of a word
            val space = flat.indexOf(' ', start)
            if (space in start until matches.first().first) start = space + 1
        }
        val end = minOf(flat.length, start + maxLength)
        val prefix = if (start > 0) ELLIPSIS else ""
        val body = flat.substring(start, end) + if (end < flat.length) ELLIPSIS else ""
        val shift = prefix.length - start
        val ranges = matches.filter { it.first >= start && it.last < end }.map { (it.first + shift)..(it.last + shift) }
        return Snippet(prefix + body, ranges)
    }
}

/** One search result. */
data class SearchHit(val messageId: String, val conversationId: String, val time: Long, val outgoing: Boolean, val senderUserId: String, val snippet: Snippet)

/** Full-text search over the decrypted messages (the index lives in the encrypted database). */
@Singleton
class SearchRepository @Inject constructor(private val db: AppDatabase) {
    /** Newest first; [conversationId] null searches all conversations. Blank input finds nothing. */
    suspend fun search(input: String, conversationId: String? = null, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        val match = SearchQuery.toMatch(input) ?: return emptyList()
        val terms = SearchQuery.terms(input)
        return db.uiDao().search(match, conversationId, limit).map {
            SearchHit(it.messageId, it.conversationId, it.time, it.outgoing, it.senderUserId, Snippets.of(it.text, terms))
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 200
    }
}
