package se.swahnchat.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import se.swahnchat.app.ui.components.FormatStyle
import se.swahnchat.app.ui.components.MentionName
import se.swahnchat.app.ui.components.MessageTextStyle
import se.swahnchat.app.ui.components.findLinks
import se.swahnchat.app.ui.components.findMentions
import se.swahnchat.app.ui.components.formatMessage
import se.swahnchat.app.ui.components.parseFormatting
import se.swahnchat.app.ui.components.plainFormatted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormattingTest {
    private fun shown(raw: String) = parseFormatting(raw).text
    private fun styled(raw: String): List<Pair<String, FormatStyle>> =
        parseFormatting(raw).let { f -> f.spans.sortedBy { it.start }.map { f.text.substring(it.start, it.end) to it.style } }

    @Test fun `the four markers format and disappear`() {
        assertEquals("fet text", shown("*fet* text"))
        assertEquals(listOf("fet" to FormatStyle.BOLD), styled("*fet* text"))
        assertEquals(listOf("kursiv" to FormatStyle.ITALIC), styled("en _kursiv_ ord"))
        assertEquals(listOf("borta" to FormatStyle.STRIKE), styled("~borta~"))
        assertEquals(listOf("kod()" to FormatStyle.CODE), styled("kör `kod()` nu"))
        assertEquals("kör kod() nu", shown("kör `kod()` nu"))
    }

    @Test fun `the markers must hug the text`() {
        assertEquals("* inte fet *", shown("* inte fet *"))
        assertEquals("*inte fet *", shown("*inte fet *"))
        assertEquals("* inte fet*", shown("* inte fet*"))
        assertTrue(parseFormatting("* a *").spans.isEmpty())
        assertEquals("a * b * c", shown("a * b * c"), "multiplication with spaces")
        assertEquals(listOf("a b" to FormatStyle.BOLD), styled("*a b*"))
    }

    @Test fun `a marker inside a word does not open one`() {
        assertEquals("snake_case_name", shown("snake_case_name"))
        assertEquals("2*3*4", shown("2*3*4"))
        assertTrue(parseFormatting("snake_case_name och 2*3*4").spans.isEmpty())
        assertEquals("*fet*text", shown("*fet*text"), "a closing marker directly before a letter is not a closing marker")
        assertEquals(listOf("fet" to FormatStyle.BOLD), styled("(*fet*)"))
        assertEquals(listOf("fet" to FormatStyle.BOLD), styled("*fet*, sa hon"))
    }

    @Test fun `unpaired markers are shown as typed`() {
        assertEquals("*hej", shown("*hej"))
        assertEquals("hej_", shown("hej_"))
        assertEquals("~ett ~två", shown("~ett ~två"))
        assertEquals("a ** b", shown("a ** b"))
        assertEquals("****", shown("****"))
        assertEquals("*", shown("*"))
        assertEquals("", shown(""))
        assertTrue(parseFormatting("*hej och _hopp").spans.isEmpty())
        // the first opener pairs with the first valid closer; a star behind a space cannot close, so it is text inside the span
        assertEquals(listOf("a *b" to FormatStyle.BOLD), styled("*a *b*"))
        assertEquals("a *b", shown("*a *b*"))
    }

    @Test fun `a backslash shows the marker and keeps it from formatting`() {
        assertEquals("*inte fet*", shown("\\*inte fet\\*"))
        assertTrue(parseFormatting("\\*inte fet\\*").spans.isEmpty())
        assertEquals("*fet* och fet", shown("\\*fet\\* och *fet*"))
        assertEquals("a\\b", shown("a\\b"), "a backslash before an ordinary character stays")
        assertEquals("slut\\", shown("slut\\"))
    }

    @Test fun `spans do not cross a line break`() {
        assertEquals("*en\ntvå*", shown("*en\ntvå*"))
        assertTrue(parseFormatting("*en\ntvå*").spans.isEmpty())
        assertEquals(listOf("a" to FormatStyle.BOLD, "b" to FormatStyle.ITALIC), styled("*a*\n_b_"))
        assertEquals("a\nb", shown("*a*\n_b_"))
    }

    @Test fun `different kinds nest and the same kind does not`() {
        assertEquals("båda", shown("*_båda_*"))
        assertEquals(setOf("båda" to FormatStyle.BOLD, "båda" to FormatStyle.ITALIC), styled("*_båda_*").toSet())
        assertEquals(setOf("en två" to FormatStyle.BOLD, "två" to FormatStyle.ITALIC), styled("*en _två_*").toSet())
        assertEquals(setOf("en" to FormatStyle.BOLD, "två" to FormatStyle.STRIKE), styled("*en* ~två~").toSet())
    }

    @Test fun `what is inside code is shown as it is`() {
        assertEquals("a *b* c", shown("`a *b* c`"))
        assertEquals(listOf("a *b* c" to FormatStyle.CODE), styled("`a *b* c`"))
        assertEquals("x _y_", shown("`x _y_`"))
        assertEquals("`", shown("`"))
        assertEquals("`` tom", shown("`` tom"))
    }

    @Test fun `a fenced block is code, across lines`() {
        val f = parseFormatting("före\n```\nval a = *2*\nval b = _3_\n```\nefter *fet*")
        assertEquals("före\nval a = *2*\nval b = _3_\nefter fet", f.text)
        val block = f.spans.single { it.block }
        assertEquals("val a = *2*\nval b = _3_", f.text.substring(block.start, block.end))
        assertEquals(FormatStyle.CODE, block.style)
        assertEquals(listOf("fet"), f.spans.filter { it.style == FormatStyle.BOLD }.map { f.text.substring(it.start, it.end) })
        assertEquals("```oavslutad", shown("```oavslutad"))
        assertEquals("``````", shown("``````"), "an empty block is not a block")
        assertEquals("en rad kod", shown("en ```rad``` kod"))
        assertEquals(listOf("rad" to FormatStyle.CODE), styled("en ```rad``` kod"))
    }

    @Test fun `web addresses are left alone`() {
        assertEquals("https://exempel.se/a_b_c/d*e*f", shown("https://exempel.se/a_b_c/d*e*f"))
        assertTrue(parseFormatting("https://exempel.se/a_b_c/d*e*f").spans.isEmpty())
        assertEquals("se https://exempel.se/_x_ nu", shown("se https://exempel.se/_x_ nu"))
        // a marker right after the address belongs to the text, not to the address
        assertEquals(listOf("https://exempel.se" to FormatStyle.BOLD), styled("*https://exempel.se*"))
        assertEquals(listOf("https://exempel.se"), findLinks(shown("*https://exempel.se*")).map { it.url })
        assertEquals("mejla anna_b_c@exempel.se", shown("mejla anna_b_c@exempel.se"))
        // text around an address still formats
        assertEquals(listOf("hej" to FormatStyle.BOLD), styled("*hej* https://exempel.se/a_b_"))
    }

    @Test fun `formatting is switched off for huge texts and lines`() {
        val huge = "*a* ".repeat(6_000)
        assertEquals(huge, shown(huge))
        val longLine = "*" + "a".repeat(2_500) + "*"
        assertEquals(longLine, shown(longLine))
        assertEquals(listOf("kort" to FormatStyle.BOLD), styled("$longLine\n*kort*").filter { it.first == "kort" })
    }

    @Test fun `pathological input stays fast`() {
        val text = "*a ".repeat(5_000) // every star is unpaired and would scan to the end of the line
        val t0 = System.nanoTime()
        parseFormatting(text.take(1_900))
        parseFormatting("_ ".repeat(900) + "x")
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test fun `plain text without markers is returned unchanged and spans are consistent`() {
        assertEquals("Hej, hur mår du?", shown("Hej, hur mår du?"))
        assertEquals("Hej, hur mår du?", plainFormatted("Hej, hur mår du?"))
        for (raw in listOf("*a* _b_ ~c~ `d`", "\\*x* *y*", "```\n*q*\n``` *z*", "*_a_ *b* ~`c`~*")) {
            val f = parseFormatting(raw)
            assertTrue(f.spans.all { it.start in 0..f.text.length && it.end in it.start..f.text.length }, raw)
        }
    }

    // --- the text shown in a bubble: formatting + links + mentions ----------------------------------------------------------

    private val style = MessageTextStyle(SpanStyle(color = Color.Blue), Color.LightGray, SpanStyle(color = Color.Red), SpanStyle(color = Color.Magenta))

    @Test fun `links and formatting combine, the link is the clean address`() {
        val clicked = ArrayList<String>()
        val text = formatMessage("*se* https://exempel.se/a_b_ nu _så_", style, onLink = { clicked += it })
        assertEquals("se https://exempel.se/a_b_ nu så", text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        assertEquals("https://exempel.se/a_b_", text.text.substring(links.single().start, links.single().end))
        assertEquals(2, text.spanStyles.size)
    }

    @Test fun `mentions are found as whole at-names and mark the own mention`() {
        val anna = MentionName("u1", "Anna", false)
        val annaS = MentionName("u2", "Anna Svensson", true)
        assertEquals(listOf(0 to 14), findMentions("@Anna Svensson, hej", listOf(anna, annaS)).map { it.start to it.end })
        assertEquals(listOf("u2"), findMentions("@Anna Svensson, hej", listOf(anna, annaS)).map { it.userId })
        assertEquals(listOf("u1", "u1"), findMentions("hej @Anna och @Anna!", listOf(anna)).map { it.userId })
        assertTrue(findMentions("hej @Annabelle", listOf(anna)).isEmpty(), "not a prefix of a longer name")
        assertTrue(findMentions("mejl@Anna", listOf(anna)).isEmpty(), "not inside a word")
        assertTrue(findMentions("hej Anna", listOf(anna)).isEmpty())
        assertTrue(findMentions("hej @Anna", emptyList()).isEmpty())
        val tapped = ArrayList<String>()
        val text = formatMessage("hej @Anna Svensson *nu*", style, listOf(annaS), onMention = { tapped += it })
        assertEquals("hej @Anna Svensson nu", text.text)
        val ann = text.getLinkAnnotations(0, text.length).single()
        assertEquals("@Anna Svensson", text.text.substring(ann.start, ann.end))
        // a tap reports the person
        val link = ann.item as androidx.compose.ui.text.LinkAnnotation.Clickable
        link.linkInteractionListener!!.onClick(link)
        assertEquals(listOf("u2"), tapped)
    }
}
