package se.familychat.app.ui

import se.familychat.app.qr.InviteQr
import se.familychat.app.qr.InviteRejection
import se.familychat.app.ui.screens.AuthMode
import se.familychat.app.ui.screens.LoginUiState
import se.familychat.app.ui.screens.LoginViewModel
import se.familychat.app.ui.screens.withScannedInvite
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a scanned invitation may do to the login screen: pre-fill the invitation form, nothing else, and never submit. */
class LoginScanTest {
    private fun qr(server: String, code: String = "K7QXr93MD_-abcd") =
        "familychat://invite?v=1&server=${URLEncoder.encode(server, "UTF-8")}&code=${URLEncoder.encode(code, "UTF-8")}"

    private val typed = LoginUiState(
        serverUrl = "https://mitt-hem.example.org", username = "anna", password = "hemligt", deviceName = "Pixel 8", displayName = "Anna",
    )

    @Test fun `a valid code fills the invitation form only`() {
        val s = typed.withScannedInvite(qr("https://chat.example.org"), allowInsecureLan = false)
        assertEquals(AuthMode.REDEEM, s.mode)
        assertEquals("K7QXr93MD_-abcd", s.code)
        assertEquals("https://chat.example.org", s.effectiveServer)
        assertEquals("chat.example.org", s.invite!!.host)
        assertNull(s.scanRejection)
    }

    @Test fun `the login form keeps what was typed - nothing from the code reaches it`() {
        val s = typed.withScannedInvite(qr("https://chat.example.org"), allowInsecureLan = false)
        assertEquals("https://mitt-hem.example.org", s.serverUrl, "the saved login address is not replaced")
        assertEquals("anna", s.username)
        assertEquals("hemligt", s.password)
        // back on the login tab the form is the typed one
        val login = s.copy(mode = AuthMode.LOGIN)
        assertEquals("https://mitt-hem.example.org", login.effectiveServer)
        assertEquals(emptySet(), LoginViewModel.validate(login.copy(password = "x")))
    }

    @Test fun `scanning never submits`() {
        val s = typed.withScannedInvite(qr("https://chat.example.org"), allowInsecureLan = false)
        assertFalse(s.busy)
        assertNull(s.error)
    }

    @Test fun `a code that is not a valid invitation changes nothing but the message`() {
        val before = typed.copy(mode = AuthMode.LOGIN)
        for (bad in listOf(
            "javascript:alert(1)", "https://evil.example.org", qr("http://chat.example.org"), qr("javascript:alert(1)"),
            qr("https://chat.example.org") + "&password=x", "x".repeat(1000),
        )) {
            val s = before.withScannedInvite(bad, allowInsecureLan = false)
            assertEquals(before.copy(scanRejection = s.scanRejection), s, bad.take(40))
            assertTrue(s.scanRejection != null)
        }
        assertEquals(InviteRejection.INSECURE_SERVER, before.withScannedInvite(qr("http://chat.example.org"), false).scanRejection)
    }

    @Test fun `http is only taken in debug builds and only for local addresses`() {
        val lan = qr("http://192.168.1.20:8000")
        assertEquals(InviteRejection.INSECURE_SERVER, typed.withScannedInvite(lan, allowInsecureLan = false).scanRejection)
        assertEquals("http://192.168.1.20:8000", typed.withScannedInvite(lan, allowInsecureLan = true).effectiveServer)
        assertEquals(InviteRejection.INSECURE_SERVER, typed.withScannedInvite(qr("http://chat.example.org"), allowInsecureLan = true).scanRejection)
    }

    @Test fun `a second scan replaces the first, a bad one keeps the good one`() {
        val first = typed.withScannedInvite(qr("https://one.example.org", "AAAA1111"), false)
        val second = first.withScannedInvite(qr("https://two.example.org", "BBBB2222"), false)
        assertEquals("https://two.example.org", second.effectiveServer)
        assertEquals("BBBB2222", second.code)
        val bad = second.withScannedInvite("nonsense", false)
        assertEquals("https://two.example.org", bad.effectiveServer)
        assertEquals(InviteRejection.NOT_AN_INVITE, bad.scanRejection)
    }

    @Test fun `the invitation form validates the scanned server like a typed one`() {
        val password = "a-long-enough-password"
        val s = LoginUiState(username = "anna", password = password, confirmPassword = password, displayName = "Anna", deviceName = "Pixel")
            .withScannedInvite(qr("https://chat.example.org"), false)
        assertEquals(emptySet(), LoginViewModel.validate(s), "server and code come from the invitation")
        assertEquals("chat.example.org", InviteQr.hostOf(s.effectiveServer))
    }
}
