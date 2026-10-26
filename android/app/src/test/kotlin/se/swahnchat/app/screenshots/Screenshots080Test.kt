@file:Suppress("MaxLineLength") // fixtures

package se.swahnchat.app.screenshots

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.swahnchat.app.data.UiError
import se.swahnchat.app.diagnostics.DiagnosticsReport
import se.swahnchat.app.diagnostics.PushTestResult
import se.swahnchat.app.diagnostics.Reachability
import se.swahnchat.app.platform.push.Distributor
import se.swahnchat.app.platform.push.PushStatus
import se.swahnchat.app.ui.screens.AdminCallbacks
import se.swahnchat.app.ui.screens.AdminContent
import se.swahnchat.app.ui.screens.AdminTab
import se.swahnchat.app.ui.screens.AdminUiState
import se.swahnchat.app.ui.screens.DeleteAccountCallbacks
import se.swahnchat.app.ui.screens.DeleteAccountContent
import se.swahnchat.app.ui.screens.DeleteAccountUiState
import se.swahnchat.app.ui.screens.DeleteStep
import se.swahnchat.app.ui.screens.DevicesContent
import se.swahnchat.app.ui.screens.DevicesUiState
import se.swahnchat.app.ui.screens.DiagnosticsCallbacks
import se.swahnchat.app.ui.screens.DiagnosticsContent
import se.swahnchat.app.ui.screens.DiagnosticsUiState
import se.swahnchat.app.ui.screens.Loadable
import se.swahnchat.app.ui.screens.OnboardingCallbacks
import se.swahnchat.app.ui.screens.OnboardingContent
import se.swahnchat.app.ui.screens.OnboardingStep
import se.swahnchat.app.ui.screens.OnboardingUiState
import se.swahnchat.app.ui.screens.OutboxContent
import se.swahnchat.app.ui.screens.OutboxItemUi
import se.swahnchat.app.ui.screens.OutboxProblem
import se.swahnchat.app.ui.screens.OutboxUiState
import se.swahnchat.app.ui.screens.OwnDeviceUi
import se.swahnchat.app.ui.screens.ResetCodeUi
import se.swahnchat.app.ui.screens.ResetCodePreview
import se.swahnchat.app.ui.screens.ResetPasswordCallbacks
import se.swahnchat.app.ui.screens.ResetPasswordContent
import se.swahnchat.app.ui.screens.ResetPasswordUiState
import se.swahnchat.app.ui.screens.ScheduledItemUi
import se.swahnchat.app.ui.screens.SettingsCallbacks
import se.swahnchat.app.ui.screens.SettingsContent
import se.swahnchat.core.model.AdminDevice
import se.swahnchat.core.model.AdminStats
import se.swahnchat.core.model.AdminUser
import se.swahnchat.core.model.AuditEvent
import se.swahnchat.core.model.DbMaintenanceInfo
import se.swahnchat.core.model.Invitation
import se.swahnchat.core.model.MigrationInfo
import se.swahnchat.core.network.RealtimeState

/** Batch 8: the new screens in light and dark (names start with `80`-`89`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class Screenshots080Test : ScreenshotBase070() {
    private val ntfy = Distributor("io.heckel.ntfy", "ntfy")
    private val now = Fixtures.NOW

    @Test fun onboardingNotifications() = both("80_onboarding_notifications") {
        OnboardingContent(OnboardingUiState(notificationsAllowed = false, needsNotificationPermission = true), OnboardingCallbacks())
    }
    @Test fun onboardingPush() = both("80_onboarding_push") {
        OnboardingContent(
            OnboardingUiState(
                step = OnboardingStep.PUSH, distributors = listOf(ntfy), selectedDistributor = "io.heckel.ntfy", pushStatus = PushStatus.ACTIVE,
                testResult = PushTestResult.Delivered(201, now),
            ),
            OnboardingCallbacks(),
        )
    }
    @Test fun onboardingBattery() = both("80_onboarding_battery") { OnboardingContent(OnboardingUiState(step = OnboardingStep.BATTERY), OnboardingCallbacks()) }
    @Test fun onboardingSafety() = both("80_onboarding_safety") { OnboardingContent(OnboardingUiState(step = OnboardingStep.SAFETY), OnboardingCallbacks()) }

    @Test fun outbox() = both("82_outbox") {
        OutboxContent(
            OutboxUiState(
                true,
                listOf(
                    OutboxItemUi("1", "c1", "Bertil Ek", "Jag är där om en kvart", 0, now - 120_000, false, OutboxProblem.OFFLINE, 3),
                    OutboxItemUi("2", "c2", "Familjen", "Bild från midsommar", 1, now - 3_600_000, true, OutboxProblem.IDENTITY_CHANGED, 0),
                ),
                listOf(ScheduledItemUi("s1", "c1", "Bertil Ek", "Grattis på födelsedagen!", 0, now + 86_400_000)),
            ),
            {}, { _, _ -> }, {}, {}, {}, {},
        )
    }

    @Test fun devices() = both("83_devices") {
        DevicesContent(
            DevicesUiState(
                loading = false, pendingApproval = 1,
                devices = listOf(
                    OwnDeviceUi("d1", "Google Pixel 8", now, true, pushRegistered = true, lastPushStatus = 201),
                    OwnDeviceUi("d2", "Surfplatta i köket", Fixtures.at(10, 20, 8, 0), false),
                ),
            ),
            {}, { _, _ -> }, {}, {},
        )
    }

    @Test fun deleteAccountExplanation() = both("84_delete_account_step1") { DeleteAccountContent(DeleteAccountUiState(), DeleteAccountCallbacks()) }
    @Test fun deleteAccountPassword() = both("84_delete_account_step2") {
        DeleteAccountContent(DeleteAccountUiState(step = DeleteStep.PASSWORD, password = "hemligt", error = UiError.WRONG_PASSWORD), DeleteAccountCallbacks())
    }

    @Test fun resetPassword() = both("85_reset_password") {
        ResetPasswordContent(
            ResetPasswordUiState(serverUrl = "https://hemma.tail1234.ts.net", code = "K7QX-4MPD-2WTR", deviceName = "Google Pixel 8", error = UiError.INVALID_RESET_CODE),
            ResetPasswordCallbacks(),
        )
    }

    private val me = AdminUser("me", "anna", "Anna Lindqvist", isAdmin = true, createdAt = 1, lastSeenAt = now, activeDevices = 1, attachmentBytes = 12_000_000)
    private val users = Loadable.Ready(
        listOf(
            me,
            AdminUser(
                "u2", "bertil", "Bertil Ek", createdAt = 1, lastSeenAt = Fixtures.at(10, 23, 9, 0), activeDevices = 2, attachmentBytes = 340_000_000,
                devices = listOf(AdminDevice("a", 1, "Pixel 7", now, true, now, 201, 0), AdminDevice("b", 2, "Surfplatta", Fixtures.at(10, 2, 8, 0), true, now, 0, 4)),
            ),
            AdminUser("u3", "cecilia", "Cecilia Berg", suspendedAt = 5, createdAt = 1, lastSeenAt = Fixtures.at(9, 30, 18, 0), activeDevices = 1, attachmentBytes = 2_000_000),
            AdminUser("u4", "dan", "Dan Holm", disabledAt = 7, createdAt = 1),
        ),
    )

    @Test fun adminUsers() = both("86_admin_users") { AdminContent(AdminUiState(tab = AdminTab.USERS, me = "me", users = users), AdminCallbacks()) }

    @Test fun adminInvitations() = both("86_admin_invitations") {
        AdminContent(
            AdminUiState(
                tab = AdminTab.INVITATIONS, me = "me",
                invitations = Loadable.Ready(
                    listOf(
                        Invitation("i1", "Mormor", false, now + 86_400_000, 1, null, null, null, 1, 0, 1),
                        Invitation("i2", "Kusinen", false, now + 86_400_000, 1, now, "u9", null, 1, 1, 0),
                        Invitation("i3", "Gammal", false, now - 1000, 1, null, null, null, 1, 0, 0),
                    ),
                ),
            ),
            AdminCallbacks(),
        )
    }

    @Test fun adminStatus() = both("86_admin_status") {
        AdminContent(
            AdminUiState(
                tab = AdminTab.STATUS, me = "me",
                stats = Loadable.Ready(
                    AdminStats(
                        users = 5, activeDevices = 7, conversations = 6, messages = 12_345, undeliveredEnvelopes = 3, attachments = 210, dbBytes = 41_000_000,
                        attachmentsBytes = 380_000_000, diskFreeBytes = 21_000_000_000, version = "0.5.0", migration = MigrationInfo("e3a1", "e3a1"),
                        uptimeSeconds = 9 * 86_400L + 3_600, dbMaintenance = DbMaintenanceInfo(at = now - 86_400_000, dialect = "sqlite", ok = true, vacuum = "not_needed"),
                    ),
                ),
            ),
            AdminCallbacks(),
        )
    }

    @Test fun adminLog() = both("86_admin_log") {
        AdminContent(
            AdminUiState(
                tab = AdminTab.LOG, me = "me", users = users, auditHasMore = true,
                audit = Loadable.Ready(
                    listOf(
                        AuditEvent(9, now, "me", "password_reset_issued", "u2"),
                        AuditEvent(8, now - 3_600_000, "me", "user_suspended", "u3"),
                        AuditEvent(7, now - 7_200_000, "u2", "device_renamed", "u2"),
                        AuditEvent(6, now - 86_400_000, "me", "invitation_created"),
                    ),
                ),
            ),
            AdminCallbacks(),
        )
    }

    @Test fun adminResetCode() = both("86_admin_reset_code") { ResetCodePreview(ResetCodeUi("Bertil Ek", "K7QX-4MPD-2WTR", now + 86_400_000)) }
}

/** The settings with the new rows and the diagnostics, whole pages (tall window). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2000dp-xhdpi")
class Screenshots081Test : ScreenshotBase070() {
    private val now = Fixtures.NOW

    @Test fun diagnostics() = both("81_diagnostics") {
        DiagnosticsContent(
            DiagnosticsUiState(
                DiagnosticsReport(
                    appVersion = "0.5.0", buildType = "release", androidSdk = 34, serverHost = "hemma.tail1234.ts.net", reachability = Reachability.REACHABLE,
                    latencyMs = 38, clockOffsetMs = 420, realtime = RealtimeState.CONNECTED, lastSyncAt = now, distributorInstalled = true,
                    distributorLabel = "ntfy", endpointRegistered = true, lastPushAt = now, lastPushStatus = 201, notificationsAllowed = true,
                    batteryUnrestricted = false, outboxSize = 2, databaseBytes = 3_400_000, now = now,
                ),
                logEntries = 412,
            ),
            DiagnosticsCallbacks(),
        )
    }

    @Test fun settingsNewRows() = both("87_settings_new_rows") {
        SettingsContent(settings.copy(isAdmin = true, failedCount = 2, batteryUnrestricted = false), SettingsCallbacks())
    }
}
