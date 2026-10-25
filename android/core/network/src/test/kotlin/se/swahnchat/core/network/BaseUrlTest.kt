package se.swahnchat.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BaseUrlTest {
    private fun n(s: String, lan: Boolean = false) = BaseUrl.normalize(s, lan).toString()

    @Test
    fun `normalises typical input`() {
        assertEquals("https://pi.tailnet.ts.net/api/v1/", n("https://pi.tailnet.ts.net"))
        assertEquals("https://pi.tailnet.ts.net/api/v1/", n("  https://pi.tailnet.ts.net/  "))
        assertEquals("https://pi.tailnet.ts.net/api/v1/", n("pi.tailnet.ts.net"))
        assertEquals("https://pi.tailnet.ts.net/api/v1/", n("https://pi.tailnet.ts.net/api/v1"))
        assertEquals("https://pi.tailnet.ts.net/api/v1/", n("https://pi.tailnet.ts.net/api/v1/"))
        assertEquals("https://chat.example.org:8443/api/v1/", n("https://chat.example.org:8443"))
        assertEquals("https://example.org/chat/api/v1/", n("https://example.org/chat/"))
    }

    @Test
    fun `websocket url`() {
        assertEquals("https://h/api/v1/ws", BaseUrl.webSocketUrl(BaseUrl.normalize("h")).toString())
    }

    @Test
    fun `plain http refused by default`() {
        assertFailsWith<InvalidBaseUrlException> { n("http://pi.local") }
        assertFailsWith<InvalidBaseUrlException> { n("http://192.168.1.5:8000") }
        assertFailsWith<InvalidBaseUrlException> { n("http://localhost:8000") }
    }

    @Test
    fun `plain http only for local addresses with the flag`() {
        for (host in listOf("localhost:8000", "127.0.0.1:8000", "10.0.2.2:8000", "192.168.1.20", "100.64.0.1", "100.127.255.254")) {
            val url = BaseUrl.normalize("http://$host", true)
            assertEquals("http", url.scheme)
            assertEquals("/api/v1/", url.encodedPath)
        }
    }

    @Test
    fun `plain http refused for public or non-cgnat addresses even with the flag`() {
        for (u in listOf("http://example.org", "http://8.8.8.8", "http://100.128.0.1", "http://100.63.0.1", "http://172.16.0.1", "http://192.169.0.1", "http://10.example.org")) {
            assertFailsWith<InvalidBaseUrlException>(u) { n(u, true) }
        }
    }

    @Test
    fun `https still fine with the flag and garbage rejected`() {
        assertTrue(n("https://x.org", true).startsWith("https://"))
        assertFailsWith<InvalidBaseUrlException> { n("") }
        assertFailsWith<InvalidBaseUrlException> { n("https://user:pw@x.org") }
        assertFailsWith<InvalidBaseUrlException> { n("https://x.org/?a=b") }
        assertFailsWith<InvalidBaseUrlException> { n("https://") }
        assertFailsWith<InvalidBaseUrlException> { n("ftp://x.org") }
    }
}
