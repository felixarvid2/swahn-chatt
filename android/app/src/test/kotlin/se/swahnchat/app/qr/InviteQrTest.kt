package se.swahnchat.app.qr

import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InviteQrTest {
    private val code = "K7QXr93MD_-abcdEFGH"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun invite(server: String, c: String = code, extra: String = "") = "swahnchat://invite?v=1&server=${enc(server)}&code=${enc(c)}$extra"

    private fun ok(text: String, lan: Boolean = false): InviteLink = (InviteQr.parse(text, lan) as? InviteParse.Ok)?.invite
        ?: error("expected a valid invitation: $text -> ${InviteQr.parse(text, lan)}")

    private fun rejected(text: String, lan: Boolean = false): InviteRejection =
        assertIs<InviteParse.Rejected>(InviteQr.parse(text, lan), text).reason

    @Test fun `a valid invitation gives the server, the host and the code`() {
        val i = ok(invite("https://hemma.tail1234.ts.net"))
        assertEquals("https://hemma.tail1234.ts.net", i.server)
        assertEquals("hemma.tail1234.ts.net", i.host)
        assertEquals(code, i.code)
        assertFalse(i.suspiciousHost)
    }

    @Test fun `the server address is normalised like the login form does`() {
        assertEquals("https://chat.example.org", ok(invite("https://chat.example.org/")).server)
        assertEquals("https://chat.example.org", ok(invite("https://chat.example.org/api/v1/")).server)
        assertEquals("https://chat.example.org:8443/familj", ok(invite("https://Chat.Example.org:8443/familj/api/v1")).server)
        assertEquals("chat.example.org", ok(invite("HTTPS://CHAT.example.org")).host)
    }

    @Test fun `the code the admin screen shows reads back unchanged`() {
        for (server in listOf("https://hemma.tail1234.ts.net/api/v1/", "https://chat.example.org:8443/familj/api/v1/")) {
            val text = InviteQr.payload(server, code)
            assertTrue(text.startsWith("swahnchat://invite?v=1&server=https%3A%2F%2F"), text)
            val i = ok(text)
            assertEquals(InviteQr.displayServer(server), i.server)
            assertEquals(code, i.code)
        }
    }

    @Test fun `plain http is refused in release builds and allowed only for local addresses in debug builds`() {
        assertEquals(InviteRejection.INSECURE_SERVER, rejected(invite("http://hemma.example.org"), lan = false))
        assertEquals(InviteRejection.INSECURE_SERVER, rejected(invite("http://192.168.1.20:8000"), lan = false))
        assertEquals(InviteRejection.INSECURE_SERVER, rejected(invite("http://localhost:8000"), lan = false))
        assertEquals("http://192.168.1.20:8000", ok(invite("http://192.168.1.20:8000"), lan = true).server)
        assertEquals(InviteRejection.INSECURE_SERVER, rejected(invite("http://hemma.example.org"), lan = true), "debug builds allow LAN hosts only")
    }

    @Test fun `javascript and other schemes are not servers, in the server parameter or as the whole code`() {
        assertEquals(InviteRejection.INVALID_SERVER, rejected(invite("javascript:alert(1)")))
        assertEquals(InviteRejection.INVALID_SERVER, rejected(invite("data:text/html,x")))
        assertEquals(InviteRejection.INVALID_SERVER, rejected(invite("file:///etc/passwd")))
        assertEquals(InviteRejection.INVALID_SERVER, rejected(invite("ftp://hemma.example.org")))
        assertEquals(InviteRejection.INVALID_SERVER, rejected(invite("hemma.example.org")), "a scheme is required")
        assertEquals(InviteRejection.INVALID_SERVER, rejected(invite("")))
        assertEquals(InviteRejection.NOT_AN_INVITE, rejected("javascript:alert(1)"))
        assertEquals(InviteRejection.NOT_AN_INVITE, rejected("https://hemma.example.org/?code=$code"))
        assertEquals(InviteRejection.NOT_AN_INVITE, rejected("swahnchat://conversation/abc"))
        assertEquals(InviteRejection.NOT_AN_INVITE, rejected("fcsn1:AAAA"))
        assertEquals(InviteRejection.NOT_AN_INVITE, rejected(""))
    }

    @Test fun `the server may not carry credentials, a query or a fragment`() {
        assertEquals(InviteRejection.INVALID_SERVER, rejected(invite("https://anna:pw@hemma.example.org")))
        assertEquals(InviteRejection.INVALID_SERVER, rejected(invite("https://hemma.example.org/?x=1")))
        assertEquals(InviteRejection.INVALID_SERVER, rejected(invite("https://hemma.example.org/#top")))
    }

    @Test fun `extra, repeated or missing parameters refuse the whole code`() {
        assertEquals(InviteRejection.MALFORMED, rejected(invite("https://hemma.example.org", extra = "&next=https%3A%2F%2Fevil.example")))
        assertEquals(InviteRejection.MALFORMED, rejected(invite("https://hemma.example.org", extra = "&password=hunter2")))
        assertEquals(InviteRejection.MALFORMED, rejected(invite("https://hemma.example.org") + "&code=OTHERCODE"))
        assertEquals(InviteRejection.MALFORMED, rejected(invite("https://hemma.example.org") + "&server=https%3A%2F%2Fevil.example"))
        assertEquals(InviteRejection.MALFORMED, rejected("swahnchat://invite?v=1&server=${enc("https://hemma.example.org")}"))
        assertEquals(InviteRejection.MALFORMED, rejected("swahnchat://invite?v=1&code=$code"))
        assertEquals(InviteRejection.MALFORMED, rejected("swahnchat://invite"))
        assertEquals(InviteRejection.MALFORMED, rejected("swahnchat://invite?"))
        assertEquals(InviteRejection.MALFORMED, rejected("swahnchat://invite?v=1&server&code=$code"))
        assertEquals(InviteRejection.MALFORMED, rejected(invite("https://hemma.example.org") + "#frag"))
        assertEquals(InviteRejection.MALFORMED, rejected("swahnchat://invite/more?v=1&server=${enc("https://hemma.example.org")}&code=$code"))
    }

    @Test fun `another version or host or scheme is not accepted`() {
        assertEquals(InviteRejection.UNSUPPORTED_VERSION, rejected("swahnchat://invite?v=2&server=${enc("https://hemma.example.org")}&code=$code"))
        assertEquals(InviteRejection.MALFORMED, rejected("swahnchat://invite?server=${enc("https://hemma.example.org")}&code=$code"))
        assertEquals(InviteRejection.NOT_AN_INVITE, rejected(invite("https://hemma.example.org").replace("//invite", "//login")))
        assertEquals(InviteRejection.NOT_AN_INVITE, rejected(invite("https://hemma.example.org").replace("swahnchat", "SwahnChat")))
        assertEquals(InviteRejection.NOT_AN_INVITE, rejected(invite("https://hemma.example.org").replace("//invite", "//evil@invite")))
    }

    @Test fun `an oversized or oddly encoded code is refused`() {
        val long = invite("https://hemma.example.org", extra = "&pad=" + "a".repeat(InviteQr.MAX_LENGTH))
        assertEquals(InviteRejection.TOO_LONG, rejected(long))
        assertEquals(InviteRejection.TOO_LONG, rejected("x".repeat(100_000)))
        val atLimit = invite("https://hemma.example.org")
        assertTrue(atLimit.length < InviteQr.MAX_LENGTH)
        assertEquals(InviteRejection.INVALID_CODE, rejected(invite("https://hemma.example.org", c = "abc"))) // too short
        assertEquals(InviteRejection.INVALID_CODE, rejected(invite("https://hemma.example.org", c = "a".repeat(129))))
        assertEquals(InviteRejection.INVALID_CODE, rejected(invite("https://hemma.example.org", c = "abcd efgh")))
        assertEquals(InviteRejection.INVALID_CODE, rejected(invite("https://hemma.example.org", c = "abcd\nefgh")))
        assertEquals(InviteRejection.INVALID_CODE, rejected(invite("https://hemma.example.org", c = "abcd<script>")))
        assertEquals(InviteRejection.NOT_AN_INVITE, rejected(invite("https://hemma.example.org") + "\u0000"))
        assertEquals(InviteRejection.NOT_AN_INVITE, rejected(invite("https://hemma.example.org") + " "))
        assertEquals(InviteRejection.NOT_AN_INVITE, rejected(invite("https://hemma.example.org", extra = "&x=å")))
    }

    @Test fun `a host with international characters is flagged so the screen can warn`() {
        val i = ok(invite("https://xn--hmma-ra4a.example.org"))
        assertEquals("xn--hmma-ra4a.example.org", i.host)
        assertTrue(i.suspiciousHost)
        assertTrue(ok(invite("https://chat.xn--80ak6aa92e.com")).suspiciousHost)
        assertFalse(ok(invite("https://chat.example.com")).suspiciousHost)
    }
}
