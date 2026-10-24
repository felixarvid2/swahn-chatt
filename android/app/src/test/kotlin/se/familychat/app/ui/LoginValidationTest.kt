package se.familychat.app.ui

import se.familychat.app.data.UiError
import se.familychat.app.data.toUiError
import se.familychat.app.ui.screens.AuthMode
import se.familychat.app.ui.screens.FieldError
import se.familychat.app.ui.screens.LoginUiState
import se.familychat.app.ui.screens.LoginViewModel
import se.familychat.core.model.DeviceRef
import se.familychat.core.network.ApiException
import se.familychat.core.network.InvalidBaseUrlException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoginValidationTest {
    private val ok = LoginUiState(serverUrl = "https://pi.example.ts.net", username = "anna.b", password = "secret", deviceName = "Pixel 8")

    @Test fun `a complete login form is valid`() = assertTrue(LoginViewModel.validate(ok).isEmpty())

    @Test fun `login needs a server a username a password and a device name`() {
        val e = LoginViewModel.validate(LoginUiState())
        assertEquals(setOf(FieldError.URL, FieldError.USERNAME, FieldError.DEVICE_NAME, FieldError.PASSWORD_EMPTY), e)
    }

    @Test fun `usernames follow the server's rule`() {
        for (bad in listOf("ab", "has space", "UPPER", "a".repeat(33), "ä-ö", "")) {
            assertTrue(FieldError.USERNAME in LoginViewModel.validate(ok.copy(username = bad)), "'$bad' should be rejected")
        }
        for (good in listOf("abc", "a_b-c.9", "a".repeat(32))) {
            assertTrue(FieldError.USERNAME !in LoginViewModel.validate(ok.copy(username = good)), "'$good' should be accepted")
        }
    }

    @Test fun `redeeming checks code display name password length and confirmation`() {
        val redeem = ok.copy(mode = AuthMode.REDEEM, code = "ABC", displayName = "Anna", password = "0123456789", confirmPassword = "0123456789")
        assertTrue(LoginViewModel.validate(redeem).isEmpty())
        val e = LoginViewModel.validate(redeem.copy(code = " ", displayName = "", password = "short", confirmPassword = "other"))
        assertEquals(setOf(FieldError.CODE, FieldError.DISPLAY_NAME, FieldError.PASSWORD_SHORT, FieldError.PASSWORD_MISMATCH), e)
        assertTrue(FieldError.DISPLAY_NAME in LoginViewModel.validate(redeem.copy(displayName = "x".repeat(65))))
    }

    @Test fun `exceptions map to the messages shown to the user`() {
        assertEquals(UiError.INVALID_CREDENTIALS, ApiException.InvalidCredentials("x").toUiError())
        assertEquals(UiError.INVALID_INVITE, ApiException.NotFound("invalid_invite", "x").toUiError())
        assertEquals(UiError.USERNAME_TAKEN, ApiException.Conflict("username_taken", "x").toUiError())
        assertEquals(UiError.RATE_LIMITED, ApiException.RateLimited("x", 30).toUiError())
        assertEquals(UiError.TOO_LARGE, ApiException.PayloadTooLarge("payload_too_large", "x").toUiError())
        assertEquals(UiError.SESSION_ENDED, ApiException.SessionEnded("unauthorized", "x").toUiError())
        assertEquals(UiError.SERVER, ApiException.Server(503, "x", "x").toUiError())
        assertEquals(UiError.NETWORK, java.net.UnknownHostException("pi").toUiError())
        assertEquals(UiError.INSECURE_URL, InvalidBaseUrlException("http").toUiError())
        assertEquals(UiError.UNKNOWN, RuntimeException("x").toUiError())
        assertEquals(UiError.NETWORK, IOException().toUiError())
        assertEquals(UiError.UNKNOWN, ApiException.MismatchedDevices("x", listOf(DeviceRef("a", "b")), emptyList()).toUiError())
    }
}
