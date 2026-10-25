package se.swahnchat.app.ui

import se.swahnchat.app.ui.components.LinkKind
import se.swahnchat.app.ui.components.describeLink
import se.swahnchat.app.ui.components.findLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinksTest {
    private fun urls(text: String) = findLinks(text).map { it.url }
    private fun shown(text: String) = findLinks(text).map { text.substring(it.start, it.end) }

    @Test fun `http and https addresses are links, with sentence punctuation left out`() {
        assertEquals(listOf("https://example.com/a?b=1#c"), urls("Se https://example.com/a?b=1#c."))
        assertEquals(listOf("http://example.com"), urls("(http://example.com)"))
        assertEquals(listOf("https://sv.wikipedia.org/wiki/Foo_(bar)"), urls("https://sv.wikipedia.org/wiki/Foo_(bar), tack"))
        assertEquals(listOf("https://a.se", "http://b.se/x"), urls("https://a.se och http://b.se/x!"))
        assertEquals(listOf("https://exempel.se/s%C3%B6k?q=åäö"), urls("https://exempel.se/s%C3%B6k?q=åäö"))
        assertEquals(listOf("HTTPS://EXAMPLE.COM"), urls("HTTPS://EXAMPLE.COM"))
    }

    @Test fun `only the four kinds are links`() {
        assertTrue(findLinks("javascript:alert(1)").isEmpty())
        assertTrue(findLinks("intent://scan/#Intent;scheme=zxing;end").isEmpty())
        assertTrue(findLinks("file:///etc/passwd and content://x").isEmpty())
        assertTrue(findLinks("ftp://example.com/file").isEmpty())
        assertTrue(findLinks("data:text/html;base64,AAAA").isEmpty())
        assertTrue(findLinks("www.example.com utan schema").isEmpty(), "no guessing: the scheme must be written")
        assertTrue(findLinks("https://").isEmpty())
        assertTrue(findLinks("http://:80/x").isEmpty())
    }

    @Test fun `e-mail addresses and mailto links open as mailto`() {
        assertEquals(listOf("mailto:anna@example.se"), urls("Skriv till anna@example.se."))
        assertEquals(listOf("mailto:anna@example.se"), urls("mailto:anna@example.se"))
        assertEquals(listOf("mailto:a.b+c@sub.example.co.uk"), urls("a.b+c@sub.example.co.uk"))
        assertTrue(findLinks("@anna är kul").isEmpty())
        assertEquals(listOf("https://user@example.com/x"), urls("https://user@example.com/x"), "an address with user info is one web link, not an e-mail")
    }

    @Test fun `phone numbers need a plausible shape`() {
        assertEquals(listOf("tel:0701234567"), urls("Ring 070-123 45 67 nu"))
        assertEquals(listOf("tel:+46701234567"), urls("Mitt nummer är +46 70 123 45 67."))
        assertEquals(listOf("tel:0812345678"), urls("08-123 456 78"))
        assertEquals(listOf("tel:+46701234567"), urls("tel:+46701234567"))
        assertTrue(findLinks("Datum 2026-10-24").isEmpty(), "a date is not a number")
        assertTrue(findLinks("Kostar 12 500 kr").isEmpty())
        assertTrue(findLinks("kl 12:30–14:00").isEmpty())
        assertTrue(findLinks("123456").isEmpty())
        assertTrue(findLinks("order 4711 0815 12 99 34 56 78 90 12").isEmpty(), "too many digits")
    }

    @Test fun `links never overlap and keep their order`() {
        val text = "a@b.se https://c.se tel:+4670123 https://d.se/x?mail=e@f.se"
        val spans = findLinks(text)
        assertEquals(listOf("mailto:a@b.se", "https://c.se", "tel:+4670123", "https://d.se/x?mail=e@f.se"), spans.map { it.url })
        spans.zipWithNext().forEach { (x, y) -> assertTrue(x.end <= y.start) }
    }

    @Test fun `a huge message does not produce a huge list`() {
        val text = (1..100).joinToString(" ") { "https://example.com/$it" }
        assertEquals(20, findLinks(text).size)
    }

    @Test fun `display text is exactly what is read`() {
        assertEquals(listOf("https://a.se/b"), shown("x https://a.se/b, y"))
    }

    @Test fun `the dialog shows the host and warns about hidden parts`() {
        val plain = assertNotNull(describeLink("https://www.example.com:8443/path?q=1"))
        assertEquals("www.example.com", plain.target); assertEquals(LinkKind.WEB, plain.kind); assertEquals(false, plain.suspicious)
        val userinfo = assertNotNull(describeLink("https://www.bank.se@evil.example/login"))
        assertEquals("evil.example", userinfo.target, "the part after the @ is where the link goes")
        assertTrue(userinfo.suspicious)
        val unicode = assertNotNull(describeLink("https://exаmple.com/"))   // Cyrillic 'а'
        assertTrue(unicode.suspicious)
        assertTrue(assertNotNull(describeLink("https://xn--exmple-cua.com/")).suspicious)
        assertEquals("anna@example.se", describeLink("mailto:anna@example.se")?.target)
        assertEquals("+46701234567", describeLink("tel:+46701234567")?.target)
        assertNull(describeLink("javascript:alert(1)"))
        assertNull(describeLink("intent://x"))
        assertNull(describeLink("https:///nohost"))
    }
}
