@file:Suppress("MaxLineLength")

package se.swahnchat.app.ui

import se.swahnchat.app.data.UiError
import se.swahnchat.app.data.db.UnsentRow
import se.swahnchat.app.data.toUiError
import se.swahnchat.app.ui.screens.AdminAction
import se.swahnchat.app.ui.screens.FieldError
import se.swahnchat.app.ui.screens.InvitationState
import se.swahnchat.app.ui.screens.OutboxProblem
import se.swahnchat.app.ui.screens.ResetPasswordUiState
import se.swahnchat.app.ui.screens.ResetPasswordViewModel
import se.swahnchat.app.ui.screens.adminActions
import se.swahnchat.app.ui.screens.auditName
import se.swahnchat.app.ui.screens.invitationState
import se.swahnchat.app.ui.screens.unsentToUi
import se.swahnchat.app.ui.screens.uptimeText
import se.swahnchat.app.ui.screens.validDeviceName
import se.swahnchat.core.model.AdminUser
import se.swahnchat.core.model.Invitation
import se.swahnchat.core.network.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorMappingTest {
    @Test fun `the errors of batch 8 have their own words`() {
        assertEquals(UiError.INVALID_RESET_CODE, ApiException.NotFound("invalid_reset_code", "").toUiError())
        assertEquals(UiError.INVALID_INVITE, ApiException.NotFound("invalid_invite", "").toUiError())
        assertEquals(UiError.UNKNOWN, ApiException.NotFound("other", "").toUiError())
        assertEquals(UiError.LAST_ADMIN, ApiException.Conflict("last_admin", "").toUiError())
        assertEquals(UiError.PUSH_NOT_CONFIGURED, ApiException.Conflict("push_not_configured", "").toUiError())
        assertEquals(UiError.DEVICE_LIMIT, ApiException.Conflict("device_limit", "").toUiError())
        assertEquals(UiError.WRONG_PASSWORD, ApiException.Forbidden("invalid_credentials", "").toUiError())
        assertEquals(UiError.ACCOUNT_SUSPENDED, ApiException.Forbidden("account_suspended", "").toUiError())
        assertEquals(UiError.FORBIDDEN, ApiException.Forbidden("forbidden", "").toUiError())
        assertEquals(UiError.RATE_LIMITED, ApiException.RateLimited("slow", 3).toUiError())
        assertEquals(UiError.INVALID_INPUT, ApiException.Invalid("invalid_request", "weak").toUiError())
    }
}

class ResetPasswordValidationTest {
    private val ok = ResetPasswordUiState(serverUrl = "https://hemma.example", code = "ABCD-1234", password = "a-long-password", confirmPassword = "a-long-password", deviceName = "Pixel")

    @Test fun `a complete form is accepted`() = assertTrue(ResetPasswordViewModel.validate(ok).isEmpty())

    @Test fun `each rule of the registration form applies`() {
        assertEquals(setOf(FieldError.URL), ResetPasswordViewModel.validate(ok.copy(serverUrl = " ")))
        assertEquals(setOf(FieldError.CODE), ResetPasswordViewModel.validate(ok.copy(code = "")))
        assertEquals(setOf(FieldError.PASSWORD_SHORT), ResetPasswordViewModel.validate(ok.copy(password = "short", confirmPassword = "short")))
        assertEquals(setOf(FieldError.PASSWORD_SHORT), ResetPasswordViewModel.validate(ok.copy(password = "x".repeat(257), confirmPassword = "x".repeat(257))))
        assertEquals(setOf(FieldError.PASSWORD_MISMATCH), ResetPasswordViewModel.validate(ok.copy(confirmPassword = "another-long-password")))
        assertEquals(setOf(FieldError.DEVICE_NAME), ResetPasswordViewModel.validate(ok.copy(deviceName = "x".repeat(65))))
        assertEquals(setOf(FieldError.DEVICE_NAME), ResetPasswordViewModel.validate(ok.copy(deviceName = "  ")))
    }
}

class DeviceNameTest {
    @Test fun `one to 64 characters after trimming`() {
        assertEquals("Köket", validDeviceName("  Köket "))
        assertNull(validDeviceName(""))
        assertNull(validDeviceName("   "))
        assertEquals(64, validDeviceName("x".repeat(64))?.length)
        assertNull(validDeviceName("x".repeat(65)))
    }
}

class OutboxLogicTest {
    @Test fun `a failed message is explained by its reason`() {
        assertEquals(OutboxProblem.IDENTITY_CHANGED, OutboxProblem.of(true, "IDENTITY_CHANGED", null))
        assertEquals(OutboxProblem.TOO_LARGE, OutboxProblem.of(true, "TOO_LARGE", null))
        assertEquals(OutboxProblem.NOT_A_MEMBER, OutboxProblem.of(true, "NOT_A_MEMBER", null))
        assertEquals(OutboxProblem.ATTACHMENT_MISSING, OutboxProblem.of(true, "ATTACHMENT_MISSING", null))
        assertEquals(OutboxProblem.TOO_MANY_RETRIES, OutboxProblem.of(true, "TOO_MANY_RETRIES", null))
        assertEquals(OutboxProblem.REJECTED, OutboxProblem.of(true, "REJECTED", null))
        assertEquals(OutboxProblem.OTHER, OutboxProblem.of(true, null, null))
        assertEquals(OutboxProblem.OTHER, OutboxProblem.of(true, "no such reason", null))
    }

    @Test fun `a waiting message is explained by the engine's token, never by an error text`() {
        assertEquals(OutboxProblem.NONE, OutboxProblem.of(false, null, null))
        assertEquals(OutboxProblem.RATE_LIMITED, OutboxProblem.of(false, null, "rate_limited"))
        assertEquals(OutboxProblem.SERVER, OutboxProblem.of(false, null, "server_error 503"))
        assertEquals(OutboxProblem.DEVICES_CHANGED, OutboxProblem.of(false, null, "mismatched_devices"))
        assertEquals(OutboxProblem.IDENTITY_CHANGED, OutboxProblem.of(false, null, "identity_changed"))
        assertEquals(OutboxProblem.REJECTED, OutboxProblem.of(false, null, "invalid_request: bad"))
        // the text of a network error can hold an address: it only ever means "no connection"
        assertEquals(OutboxProblem.OFFLINE, OutboxProblem.of(false, null, "Unable to resolve host \"family.example.org\": No address associated with hostname"))
        assertEquals(OutboxProblem.OFFLINE, OutboxProblem.of(false, null, "network"))
    }

    @Test fun `rows become items with the chat name, the state and the number of files`() {
        val rows = listOf(
            UnsentRow("m1", "c1", "Hej", "[]", 100, "PENDING", null, 0, null),
            UnsentRow("m2", "c2", null, "[]", 200, "FAILED", "REJECTED", 3, "x"),
            UnsentRow("m3", "gone", "  ", "[]", 300, "PENDING", null, 1, "network"),
        )
        val items = unsentToUi(rows, mapOf("c1" to "Bertil", "c2" to "Familjen"), "Okänd")
        assertEquals(listOf("Bertil", "Familjen", "Okänd"), items.map { it.chat })
        assertEquals(listOf(false, true, false), items.map { it.failed })
        assertEquals(listOf(OutboxProblem.NONE, OutboxProblem.REJECTED, OutboxProblem.OFFLINE), items.map { it.problem })
        assertNull(items[2].text, "a blank text is no text")
    }
}

class AdminLogicTest {
    private val me = "me"
    private fun user(id: String, admin: Boolean = false, suspended: Boolean = false, removed: Boolean = false) =
        AdminUser(id, "u$id", "User $id", admin, 1, if (removed) 5 else null, if (suspended) 5 else null)

    @Test fun `no actions on yourself or a removed account`() {
        assertTrue(adminActions(user(me, admin = true), me).isEmpty())
        assertTrue(adminActions(user("x", removed = true), me).isEmpty())
    }

    @Test fun `the menu follows the state of the account`() {
        assertEquals(
            listOf(AdminAction.SUSPEND, AdminAction.MAKE_ADMIN, AdminAction.RESET_CODE, AdminAction.REMOVE),
            adminActions(user("x"), me),
        )
        assertEquals(
            listOf(AdminAction.UNSUSPEND, AdminAction.REMOVE_ADMIN, AdminAction.RESET_CODE, AdminAction.REMOVE),
            adminActions(user("y", admin = true, suspended = true), me),
        )
    }

    @Test fun `an invitation is valid, used, revoked or expired`() {
        val now = 1000L
        fun inv(expires: Long = 5000, revoked: Long? = null, uses: Int = 0, max: Int = 1, remaining: Int = 1, usedAt: Long? = null) =
            Invitation("i", null, false, expires, 0, usedAt, null, revoked, max, uses, remaining)
        assertEquals(InvitationState.ACTIVE, invitationState(inv(), now))
        assertEquals(InvitationState.USED, invitationState(inv(uses = 1, remaining = 0, usedAt = 5), now))
        assertEquals(InvitationState.REVOKED, invitationState(inv(revoked = 3, remaining = 0), now))
        assertEquals(InvitationState.EXPIRED, invitationState(inv(expires = 500, remaining = 0), now))
        assertEquals(InvitationState.ACTIVE, invitationState(inv(uses = 2, max = 5, remaining = 3), now))
    }

    @Test fun `the log names people it knows and shortens ids it does not`() {
        val users = listOf(user("a1b2c3d4-0000"))
        assertEquals("User a1b2c3d4-0000 (@ua1b2c3d4-0000)", auditName("a1b2c3d4-0000", users, "?"))
        assertEquals("zzzzzzzz", auditName("zzzzzzzz-1111-2222", users, "?"))
        assertEquals("okänd", auditName(null, users, "okänd"))
    }

    @Test fun `uptime in days, hours and minutes`() {
        assertEquals("2 min", uptimeText(150))
        assertEquals("3 h 5 min", uptimeText(3 * 3600 + 5 * 60))
        assertEquals("1 d 1 h", uptimeText(90_000))
    }
}
