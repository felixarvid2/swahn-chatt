@file:Suppress("MaxLineLength") // fixtures and one-line assertions

package se.swahnchat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
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
import se.swahnchat.app.screenshots.Fixtures
import se.swahnchat.app.ui.components.LocalNowMillis
import se.swahnchat.app.ui.screens.AdminCallbacks
import se.swahnchat.app.ui.screens.AdminContent
import se.swahnchat.app.ui.screens.AdminTab
import se.swahnchat.app.ui.screens.RemoveUserForm
import se.swahnchat.app.ui.screens.RenameDeviceForm
import se.swahnchat.app.ui.screens.AdminAction
import se.swahnchat.app.ui.screens.AdminPasswordForm
import se.swahnchat.app.ui.screens.canConfirmRemoval
import se.swahnchat.app.ui.screens.canConfirmReset
import se.swahnchat.app.ui.screens.canRemoveUser
import se.swahnchat.app.ui.screens.runAdminAction
import se.swahnchat.app.ui.screens.AdminUiState
import se.swahnchat.app.ui.screens.DeleteAccountCallbacks
import se.swahnchat.app.ui.screens.DeleteAccountContent
import se.swahnchat.app.ui.screens.DeleteAccountUiState
import se.swahnchat.app.ui.screens.DeleteStep
import se.swahnchat.app.ui.screens.DevicesContent
import se.swahnchat.app.ui.screens.DevicesUi
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
import se.swahnchat.app.ui.screens.ResetPasswordCallbacks
import se.swahnchat.app.ui.screens.ResetPasswordContent
import se.swahnchat.app.ui.screens.ResetPasswordUiState
import se.swahnchat.app.ui.screens.ScheduledItemUi
import se.swahnchat.app.ui.screens.SettingsCallbacks
import se.swahnchat.app.ui.screens.SettingsContent
import se.swahnchat.app.ui.screens.SettingsUiState
import se.swahnchat.app.ui.theme.SwahnChatTheme
import se.swahnchat.core.model.AdminDevice
import se.swahnchat.core.model.AdminUser
import se.swahnchat.core.model.AuditEvent
import se.swahnchat.core.model.Invitation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val NOW = Fixtures.NOW

/** The shared harness: the app's theme, Swedish, a fixed clock. */
abstract class BatchEightUiBase {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    protected fun content(block: @Composable () -> Unit) = compose.setContent {
        SwahnChatTheme(darkTheme = false, dynamicColor = false) {
            CompositionLocalProvider(LocalNowMillis provides NOW) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { block() }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2400dp-xhdpi")
class BatchEightSettingsUiTest : BatchEightUiBase() {
    @Test fun `the new rows open their screens`() {
        val opened = ArrayList<String>()
        content {
            SettingsContent(
                SettingsUiState(devices = DevicesUi.Loaded(listOf(se.swahnchat.app.ui.screens.DeviceUi("d", "Pixel", NOW, true), se.swahnchat.app.ui.screens.DeviceUi("e", "Surfplatta", NOW, false)))),
                SettingsCallbacks(
                    onOpenDevices = { opened += "devices" }, onOpenOnboarding = { opened += "onboarding" }, onOpenDiagnostics = { opened += "diagnostics" },
                    onOpenOutbox = { opened += "outbox" }, onOpenDeleteAccount = { opened += "delete" }, onBattery = { opened += "battery" },
                ),
            )
        }
        compose.onNodeWithText("2 enheter").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mina enheter").performScrollTo().performClick()
        compose.onNodeWithText("Kom igång").performScrollTo().performClick()
        compose.onNodeWithText("Felsökning").performScrollTo().performClick()
        compose.onNodeWithText("Utkorg").performScrollTo().performClick()
        compose.onNodeWithText("Radera mitt konto").performScrollTo().performClick()
        compose.onNodeWithText("Batterioptimering").performScrollTo().performClick()
        assertEquals(listOf("devices", "onboarding", "diagnostics", "outbox", "delete", "battery"), opened)
    }

    @Test fun `the administration row is only there for administrators`() {
        var admin by mutableStateOf(false)
        val opened = ArrayList<String>()
        content { SettingsContent(SettingsUiState(isAdmin = admin), SettingsCallbacks(onOpenAdmin = { opened += "admin" })) }
        compose.onAllNodesWithText("Administration").assertCountEquals(0)
        admin = true
        compose.waitForIdle()
        compose.onNodeWithText("Administration").performScrollTo().performClick()
        assertEquals(listOf("admin"), opened)
    }

    @Test fun `the outbox row says how many messages failed`() {
        content { SettingsContent(SettingsUiState(failedCount = 3), SettingsCallbacks()) }
        compose.onNodeWithText("3 meddelanden kunde inte skickas").performScrollTo().assertIsDisplayed()
    }

    @Test fun `the battery row tells whether the app is held back`() {
        var unrestricted by mutableStateOf(false)
        content { SettingsContent(SettingsUiState(batteryUnrestricted = unrestricted), SettingsCallbacks()) }
        compose.onNodeWithText("Kan hållas tillbaka", substring = true).performScrollTo().assertIsDisplayed()
        unrestricted = true
        compose.waitForIdle()
        compose.onNodeWithText("Appen är undantagen från batterioptimering").performScrollTo().assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2400dp-xhdpi")
class OnboardingUiTest : BatchEightUiBase() {
    private val ntfy = Distributor("io.heckel.ntfy", "ntfy")

    @Test fun `four steps with a counter, back, next and done`() {
        var state by mutableStateOf(OnboardingUiState())
        val cb = OnboardingCallbacks(
            onNext = { state = state.copy(step = OnboardingStep.entries[state.step.ordinal + 1]) },
            onBack = { state = state.copy(step = OnboardingStep.entries[state.step.ordinal - 1]) },
        )
        content { OnboardingContent(state, cb) }
        compose.onNodeWithText("Steg 1 av 4").assertIsDisplayed()
        compose.onAllNodesWithText("Tillbaka").assertCountEquals(0)
        compose.onNodeWithText("Nästa").performClick()
        compose.onNodeWithText("Steg 2 av 4").assertIsDisplayed()
        compose.onNodeWithText("Nästa").performClick()
        compose.onNodeWithText("Steg 3 av 4").assertIsDisplayed()
        compose.onNodeWithText("Nästa").performClick()
        compose.onNodeWithText("Steg 4 av 4").assertIsDisplayed()
        compose.onNodeWithText("Klar").assertIsDisplayed()
        compose.onNodeWithText("Tillbaka").performClick()
        compose.onNodeWithText("Steg 3 av 4").assertIsDisplayed()
    }

    @Test fun `it can be skipped and finished`() {
        val seen = ArrayList<String>()
        content { OnboardingContent(OnboardingUiState(step = OnboardingStep.SAFETY), OnboardingCallbacks(onSkip = { seen += "skip" }, onFinish = { seen += "finish" })) }
        compose.onNodeWithText("Hoppa över").performClick()
        compose.onNodeWithText("Klar").performClick()
        assertEquals(listOf("skip", "finish"), seen)
    }

    @Test fun `the notification step asks for the permission, or opens the settings when it cannot ask`() {
        val seen = ArrayList<String>()
        val cb = OnboardingCallbacks(onAllowNotifications = { seen += "ask" }, onOpenNotificationSettings = { seen += "settings" })
        var state by mutableStateOf(OnboardingUiState(notificationsAllowed = false, needsNotificationPermission = true))
        content { OnboardingContent(state, cb) }
        compose.onNode(hasText("Tillåt notiser") and hasClickAction()).performClick()
        state = state.copy(needsNotificationPermission = false)
        compose.waitForIdle()
        compose.onNodeWithText("Öppna notisinställningar").performClick()
        assertEquals(listOf("ask", "settings"), seen)
        state = state.copy(notificationsAllowed = true)
        compose.waitForIdle()
        compose.onAllNodesWithText("Öppna notisinställningar").assertCountEquals(0)
    }

    @Test fun `the push step tells whether a distributor is installed and offers the test only when push is active`() {
        var state by mutableStateOf(OnboardingUiState(step = OnboardingStep.PUSH))
        val seen = ArrayList<String>()
        content { OnboardingContent(state, OnboardingCallbacks(onTestPush = { seen += "test" }, onSelectDistributor = { seen += it })) }
        compose.onNodeWithText("Ingen push-app är installerad ännu.").assertIsDisplayed()
        compose.onAllNodesWithText("Välj push-app").assertCountEquals(0)
        state = state.copy(distributors = listOf(ntfy), pushStatus = PushStatus.NOT_CHOSEN)
        compose.waitForIdle()
        compose.onNodeWithText("Installerad push-app: ntfy").assertIsDisplayed()
        compose.onNodeWithText("Skicka testnotis").assertIsNotEnabled()
        compose.onNodeWithText("Välj push-app").performClick()
        compose.onNodeWithText("io.heckel.ntfy").performClick()
        state = state.copy(selectedDistributor = "io.heckel.ntfy", pushStatus = PushStatus.ACTIVE)
        compose.waitForIdle()
        compose.onNodeWithText("Push är på och servern känner till den här enheten.").assertIsDisplayed()
        compose.onNodeWithText("Skicka testnotis").assertIsEnabled().performClick()
        assertEquals(listOf("io.heckel.ntfy", "test"), seen)
    }

    @Test fun `the result of the test is shown`() {
        var state by mutableStateOf(OnboardingUiState(step = OnboardingStep.PUSH, distributors = listOf(ntfy), pushStatus = PushStatus.ACTIVE, testResult = PushTestResult.Delivered(201, NOW)))
        content { OnboardingContent(state, OnboardingCallbacks()) }
        compose.onNodeWithText("Push-servern tog emot testnotisen (svar 201)", substring = true).assertIsDisplayed()
        state = state.copy(testResult = PushTestResult.Refused(404))
        compose.waitForIdle()
        compose.onNodeWithText("Push-servern avvisade testet (svar 404)", substring = true).assertIsDisplayed()
        state = state.copy(testResult = PushTestResult.NoAnswer)
        compose.waitForIdle()
        compose.onNodeWithText("Push-servern svarade inte", substring = true).assertIsDisplayed()
    }

    @Test fun `the battery step explains why and offers the exemption only while the app is held back`() {
        val seen = ArrayList<String>()
        var state by mutableStateOf(OnboardingUiState(step = OnboardingStep.BATTERY, batteryUnrestricted = false))
        content { OnboardingContent(state, OnboardingCallbacks(onBattery = { seen += "battery" })) }
        compose.onNodeWithText("push och samtal komma sent", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Tillåt i bakgrunden").performClick()
        assertEquals(listOf("battery"), seen)
        state = state.copy(batteryUnrestricted = true)
        compose.waitForIdle()
        compose.onAllNodesWithText("Tillåt i bakgrunden").assertCountEquals(0)
    }

    @Test fun `the last step links to the safety numbers`() {
        val seen = ArrayList<String>()
        content { OnboardingContent(OnboardingUiState(step = OnboardingStep.SAFETY), OnboardingCallbacks(onOpenSafety = { seen += "safety" })) }
        compose.onNodeWithText("Jämför säkerhetsnummer").assertIsDisplayed()
        compose.onNodeWithText("Öppna mina säkerhetsnummer").performClick()
        assertEquals(listOf("safety"), seen)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h3000dp-xhdpi")
class DiagnosticsUiTest : BatchEightUiBase() {
    private val report = DiagnosticsReport(
        appVersion = "0.5.0", buildType = "debug", androidSdk = 34, serverHost = "hemma.example.net", reachability = Reachability.REACHABLE, latencyMs = 42,
        clockOffsetMs = 1200, lastSyncAt = NOW, distributorInstalled = true, distributorLabel = "ntfy", endpointRegistered = true, lastPushAt = NOW,
        lastPushStatus = 201, notificationsAllowed = true, batteryUnrestricted = false, outboxSize = 2, databaseBytes = 2_500_000, now = NOW,
    )

    @Test fun `it shows the host, the latency, the push state and the sizes`() {
        content { DiagnosticsContent(DiagnosticsUiState(report), DiagnosticsCallbacks()) }
        compose.onNodeWithText("hemma.example.net").assertIsDisplayed()
        compose.onNodeWithText("Ja (42 ms)").assertIsDisplayed()
        compose.onNodeWithText("ntfy").assertIsDisplayed()
        compose.onNodeWithText("Exakta alarm").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Behövs inte, appen använder dem inte").performScrollTo().assertIsDisplayed()
    }

    @Test fun `a server that does not answer is shown as a problem`() {
        content { DiagnosticsContent(DiagnosticsUiState(report.copy(reachability = Reachability.UNREACHABLE, latencyMs = null)), DiagnosticsCallbacks()) }
        compose.onNodeWithText("Nej, ingen kontakt").assertIsDisplayed()
    }

    @Test fun `the buttons copy, test the push and open the outbox`() {
        val seen = ArrayList<String>()
        content {
            DiagnosticsContent(
                DiagnosticsUiState(report, testResult = PushTestResult.Delivered(201, NOW)),
                DiagnosticsCallbacks(onCopy = { seen += "copy" }, onTestPush = { seen += "test" }, onOpenOutbox = { seen += "outbox" }, onBattery = { seen += "battery" }),
            )
        }
        compose.onNodeWithText("Skicka testnotis").performScrollTo().performClick()
        compose.onNodeWithText("Kopiera").performScrollTo().performClick()
        compose.onNodeWithText("Öppna").performScrollTo().performClick()
        compose.onNodeWithText("Tillåt i bakgrunden").performScrollTo().performClick()
        assertEquals(listOf("test", "copy", "outbox", "battery"), seen)
        compose.onNodeWithText("Push-servern tog emot testnotisen", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `the test button is off while no push address is registered`() {
        content { DiagnosticsContent(DiagnosticsUiState(report.copy(endpointRegistered = false)), DiagnosticsCallbacks()) }
        compose.onNodeWithText("Skicka testnotis").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `the export explains what it contains before the file is chosen`() {
        val seen = ArrayList<String>()
        content { DiagnosticsContent(DiagnosticsUiState(report, logEntries = 321), DiagnosticsCallbacks(onExport = { seen += "export" })) }
        compose.onNodeWithText("Exportera logg").performScrollTo().performClick()
        compose.onNodeWithText("Filen innehåller:").assertIsDisplayed()
        compose.onNodeWithText("321 rader", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Filen innehåller aldrig:").assertIsDisplayed()
        compose.onNodeWithText("lösenord, nycklar, tokens eller push-adressen", substring = true).assertIsDisplayed()
        assertTrue(seen.isEmpty(), "nothing is written before the person confirms")
        compose.onNodeWithText("Välj var den sparas").performClick()
        assertEquals(listOf("export"), seen)
    }

    @Test fun `cancelling the export writes nothing`() {
        val seen = ArrayList<String>()
        content { DiagnosticsContent(DiagnosticsUiState(report), DiagnosticsCallbacks(onExport = { seen += "export" })) }
        compose.onNodeWithText("Exportera logg").performScrollTo().performClick()
        compose.onNodeWithText("Avbryt").performClick()
        assertTrue(seen.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2400dp-xhdpi")
class OutboxUiTest : BatchEightUiBase() {
    private fun item(id: String, failed: Boolean, problem: OutboxProblem, text: String? = "Hej då") =
        OutboxItemUi(id, "c-$id", "Bertil Ek", text, 0, NOW, failed, problem, 2)

    @Test fun `it lists the waiting and failed messages with their chat, state and problem`() {
        content {
            OutboxContent(
                OutboxUiState(true, listOf(item("1", false, OutboxProblem.OFFLINE), item("2", true, OutboxProblem.NOT_A_MEMBER, "Hallå")), emptyList()),
                {}, { _, _ -> }, {}, {}, {}, {},
            )
        }
        compose.onNodeWithText("Hej då").assertIsDisplayed()
        compose.onNodeWithText("Väntar på att skickas").assertIsDisplayed()
        compose.onNodeWithText("Ingen kontakt med servern", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Hallå").assertIsDisplayed()
        compose.onNodeWithText("Kunde inte skickas").assertIsDisplayed()
        compose.onNodeWithText("Du är inte längre med i chatten.").assertIsDisplayed()
    }

    @Test fun `try again passes whether the message had failed, and the top button retries all`() {
        val retried = ArrayList<Pair<String, Boolean>>()
        var all = 0
        content {
            OutboxContent(
                OutboxUiState(true, listOf(item("1", false, OutboxProblem.NONE), item("2", true, OutboxProblem.REJECTED)), emptyList()),
                {}, { id, failed -> retried += id to failed }, { all++ }, {}, {}, {},
            )
        }
        compose.onAllNodesWithText("Försök igen nu")[1].performClick() // first is the "retry all" button
        compose.onAllNodesWithText("Försök igen nu")[2].performClick()
        assertEquals(listOf("1" to false, "2" to true), retried)
        compose.onAllNodesWithText("Försök igen nu")[0].performClick()
        assertEquals(1, all)
    }

    @Test fun `removing asks first and only then removes`() {
        val removed = ArrayList<String>()
        content { OutboxContent(OutboxUiState(true, listOf(item("1", true, OutboxProblem.REJECTED)), emptyList()), {}, { _, _ -> }, {}, { removed += it }, {}, {}) }
        compose.onNodeWithContentDescription("Ta bort").performClick()
        compose.onNodeWithText("Ta bort meddelandet?").assertIsDisplayed()
        compose.onNodeWithText("Avbryt").performClick()
        assertTrue(removed.isEmpty())
        compose.onNodeWithContentDescription("Ta bort").performClick()
        compose.onAllNodesWithText("Ta bort")[0].performClick()
        assertEquals(listOf("1"), removed)
    }

    @Test fun `scheduled messages have their own section and open their list`() {
        val opened = ArrayList<String>()
        content {
            OutboxContent(
                OutboxUiState(true, emptyList(), listOf(ScheduledItemUi("s1", "c9", "Familjen", "God morgon!", 0, NOW + 3_600_000))),
                {}, { _, _ -> }, {}, {}, { opened += it }, {},
            )
        }
        compose.onNodeWithText("Schemalagda").assertIsDisplayed()
        compose.onNodeWithText("God morgon!").performClick()
        assertEquals(listOf("c9"), opened)
    }

    @Test fun `an empty outbox says so`() {
        content { OutboxContent(OutboxUiState(true), {}, { _, _ -> }, {}, {}, {}, {}) }
        compose.onNodeWithText("Utkorgen är tom").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2400dp-xhdpi")
class DevicesUiTest : BatchEightUiBase() {
    private val devices = listOf(
        OwnDeviceUi("d1", "Google Pixel 8", NOW, true, pushRegistered = true, lastPushStatus = 201),
        OwnDeviceUi("d2", "Surfplatta", Fixtures.at(10, 20, 8, 0), false),
    )

    @Test fun `it lists the devices, marks this one and shows the push state where it is known`() {
        content { DevicesContent(DevicesUiState(loading = false, devices = devices), {}, { _, _ -> }, {}, {}) }
        compose.onNodeWithText("Google Pixel 8").assertIsDisplayed()
        compose.onNodeWithText("Den här enheten").assertIsDisplayed()
        compose.onNodeWithText("Push: registrerad, senaste försöket lyckades").assertIsDisplayed()
        compose.onNodeWithText("Pushstatus visas bara på själva enheten").assertIsDisplayed()
    }

    // (The text field is tested outside the dialog: a text field in a dialog window never becomes idle under Robolectric.)
    @Test fun `the rename field shows how long the name is and flags an empty or a too long one`() {
        var text by mutableStateOf("Surfplatta")
        content { RenameDeviceForm(text, onChange = { text = it }) }
        compose.onNodeWithText("10/64").assertIsDisplayed()
        text = "   "
        compose.waitForIdle()
        compose.onNodeWithText("Skriv ett namn på 1–64 tecken").assertIsDisplayed()
        text = "x".repeat(65)
        compose.waitForIdle()
        compose.onNodeWithText("Skriv ett namn på 1–64 tecken").assertIsDisplayed()
        text = "x".repeat(64)
        compose.waitForIdle()
        compose.onNodeWithText("64/64").assertIsDisplayed()
    }

    @Test fun `only other devices can be removed and the removal is confirmed`() {
        val revoked = ArrayList<String>()
        content { DevicesContent(DevicesUiState(loading = false, devices = devices), {}, { _, _ -> }, { revoked += it }, {}) }
        compose.onAllNodesWithContentDescription("Återkalla Google Pixel 8").assertCountEquals(0)
        compose.onNodeWithContentDescription("Återkalla Surfplatta").performClick()
        compose.onNodeWithText("Återkalla Surfplatta?").assertIsDisplayed()
        compose.onNodeWithText("Avbryt").performClick()
        assertTrue(revoked.isEmpty())
        compose.onNodeWithContentDescription("Återkalla Surfplatta").performClick()
        compose.onNode(hasText("Återkalla") and hasClickAction()).performClick()
        assertEquals(listOf("d2"), revoked)
    }

    @Test fun `new devices that wait for approval link to the safety screen`() {
        var opened = 0
        content { DevicesContent(DevicesUiState(loading = false, devices = devices, pendingApproval = 1), {}, { _, _ -> }, {}, { opened++ }) }
        compose.onNodeWithText("Nya enheter väntar på ditt godkännande (1)").assertIsDisplayed()
        compose.onNodeWithText("Granska och godkänn").performClick()
        assertEquals(1, opened)
    }

    @Test fun `an error from the server is shown`() {
        content { DevicesContent(DevicesUiState(loading = false, devices = devices, error = UiError.RATE_LIMITED), {}, { _, _ -> }, {}, {}) }
        compose.onNodeWithText("För många försök", substring = true).assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2400dp-xhdpi")
class DeleteAccountUiTest : BatchEightUiBase() {
    @Test fun `step one says exactly what happens and nothing is asked yet`() {
        val seen = ArrayList<String>()
        content { DeleteAccountContent(DeleteAccountUiState(), DeleteAccountCallbacks(onContinue = { seen += "continue" })) }
        compose.onNodeWithText("Steg 1 av 2", substring = true).assertIsDisplayed()
        for (part in listOf("enheter och nycklar tas bort", "lämnar alla chattar", "finns kvar för de andra", "raderas från servern", "raderas från den här telefonen", "enda administratören")) {
            compose.onNodeWithText(part, substring = true).assertIsDisplayed()
        }
        compose.onNodeWithText("Det går inte att ångra.").assertIsDisplayed()
        compose.onAllNodesWithText("Lösenord").assertCountEquals(0)
        compose.onNodeWithText("Fortsätt").performClick()
        assertEquals(listOf("continue"), seen)
    }

    @Test fun `step two needs the password before the button works`() {
        var state by mutableStateOf(DeleteAccountUiState(step = DeleteStep.PASSWORD))
        val seen = ArrayList<String>()
        content { DeleteAccountContent(state, DeleteAccountCallbacks(onDelete = { seen += "delete" }, onPassword = { state = state.copy(password = it) })) }
        compose.onNodeWithText("Steg 2 av 2", substring = true).assertIsDisplayed()
        compose.onNode(hasText("Radera mitt konto") and hasClickAction()).assertIsNotEnabled()
        state = state.copy(password = "hemligt-lösen")
        compose.waitForIdle()
        compose.onNode(hasText("Radera mitt konto") and hasClickAction()).assertIsEnabled().performClick()
        assertEquals(listOf("delete"), seen)
    }

    @Test fun `the errors are worded for a wrong password, the last administrator and the rate limit`() {
        var state by mutableStateOf(DeleteAccountUiState(step = DeleteStep.PASSWORD, password = "x", error = UiError.WRONG_PASSWORD))
        content { DeleteAccountContent(state, DeleteAccountCallbacks()) }
        compose.onNodeWithText("Fel lösenord.").assertIsDisplayed()
        state = state.copy(error = UiError.LAST_ADMIN)
        compose.waitForIdle()
        compose.onNodeWithText("Utse en annan administratör först", substring = true).assertIsDisplayed()
        state = state.copy(error = UiError.RATE_LIMITED)
        compose.waitForIdle()
        compose.onNodeWithText("För många försök", substring = true).assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2400dp-xhdpi")
class ResetPasswordUiTest : BatchEightUiBase() {
    @Test fun `it explains where the code comes from and what the reset does`() {
        content { ResetPasswordContent(ResetPasswordUiState(), ResetPasswordCallbacks()) }
        compose.onNodeWithText("Be en administratör", substring = true).assertIsDisplayed()
        compose.onNodeWithText("alla dina andra enheter loggas ut", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Dina kontakter får en notis om en ny enhet", substring = true).assertIsDisplayed()
    }

    @Test fun `the error states are worded`() {
        var state by mutableStateOf(ResetPasswordUiState(error = UiError.INVALID_RESET_CODE))
        content { ResetPasswordContent(state, ResetPasswordCallbacks()) }
        compose.onNodeWithText("Koden är ogiltig, använd, ersatt av en nyare eller utgången", substring = true).assertIsDisplayed()
        state = state.copy(error = UiError.RATE_LIMITED)
        compose.waitForIdle()
        compose.onNodeWithText("För många försök", substring = true).assertIsDisplayed()
        state = state.copy(error = UiError.NETWORK)
        compose.waitForIdle()
        compose.onNodeWithText("Det gick inte att nå servern", substring = true).assertIsDisplayed()
    }

    @Test fun `the button submits`() {
        var submitted = 0
        content { ResetPasswordContent(ResetPasswordUiState(), ResetPasswordCallbacks(onSubmit = { submitted++ })) }
        compose.onNodeWithText("Byt lösenord och logga in").performScrollTo().performClick()
        assertEquals(1, submitted)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2400dp-xhdpi")
class AdminUiTest : BatchEightUiBase() {
    private val me = AdminUser("me", "anna", "Anna Lindqvist", isAdmin = true, createdAt = 1, lastSeenAt = NOW, activeDevices = 1, attachmentBytes = 1000)
    private val bertil = AdminUser(
        "u2", "bertil", "Bertil Ek", createdAt = 1, lastSeenAt = Fixtures.at(10, 23, 9, 0), activeDevices = 2, attachmentBytes = 3_000_000,
        devices = listOf(
            AdminDevice("dev1", 1, "Pixel", NOW, true, NOW, 201, 0),
            AdminDevice("dev2", 2, "Surfplatta", NOW, true, NOW, 0, 3),
            AdminDevice("dev3", 3, "Gammal telefon", 0, false, null, null, 0),
        ),
    )
    private val cecilia = AdminUser("u3", "cecilia", "Cecilia Berg", suspendedAt = 5, createdAt = 1, activeDevices = 0)
    private val dan = AdminUser("u4", "dan", "Dan Holm", disabledAt = 7, createdAt = 1)
    private fun users(vararg u: AdminUser) = Loadable.Ready(u.toList())

    private fun state(tab: AdminTab = AdminTab.USERS, users: Loadable<List<AdminUser>> = users(me, bertil, cecilia, dan)) = AdminUiState(tab = tab, me = "me", users = users)

    @Test fun `users show their badges, last seen and device count`() {
        content { AdminContent(state(), AdminCallbacks()) }
        compose.onNodeWithText("Administratör").assertIsDisplayed()
        compose.onNodeWithText("Avstängd").assertIsDisplayed()
        compose.onNodeWithText("Borttagen").assertIsDisplayed()
        compose.onNodeWithText("@anna · du").assertIsDisplayed()
    }

    @Test fun `there are no actions on yourself or on a removed account`() {
        content { AdminContent(state(), AdminCallbacks()) }
        compose.onAllNodesWithContentDescription("Åtgärder för Anna Lindqvist").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Åtgärder för Dan Holm").assertCountEquals(0)
        compose.onNodeWithContentDescription("Åtgärder för Bertil Ek").assertIsDisplayed()
    }

    @Test fun `suspending asks first, cancelling does nothing and confirming calls the server`() {
        val seen = ArrayList<String>()
        content { AdminContent(state(), AdminCallbacks(onSuspend = { seen += "suspend:$it" })) }
        compose.onNodeWithContentDescription("Åtgärder för Bertil Ek").performClick()
        compose.onNodeWithText("Stäng av kontot").performClick()
        compose.onNodeWithText("Stänga av Bertil Ek?").assertIsDisplayed()
        compose.onNodeWithText("Avbryt").performClick()
        assertTrue(seen.isEmpty())
        compose.onNodeWithContentDescription("Åtgärder för Bertil Ek").performClick()
        compose.onNodeWithText("Stäng av kontot").performClick()
        compose.onAllNodesWithText("Stäng av kontot")[0].performClick()
        assertEquals(listOf("suspend:u2"), seen)
    }

    @Test fun `a suspended account is offered reactivation, and admin rights switch both ways`() {
        val seen = ArrayList<String>()
        content { AdminContent(state(), AdminCallbacks(onUnsuspend = { seen += "unsuspend:$it" }, onSetAdmin = { id, a -> seen += "admin:$id:$a" })) }
        compose.onNodeWithContentDescription("Åtgärder för Cecilia Berg").performClick()
        compose.onNodeWithText("Återaktivera kontot").performClick()
        compose.onAllNodesWithText("Återaktivera kontot")[0].performClick()
        compose.onNodeWithContentDescription("Åtgärder för Bertil Ek").performClick()
        compose.onNodeWithText("Gör till administratör").performClick()
        compose.onAllNodesWithText("Gör till administratör")[0].performClick()
        assertEquals(listOf("unsuspend:u3", "admin:u2:true"), seen)
    }

    @Test fun `the username has to match exactly`() {
        assertTrue(canConfirmRemoval("bertil", "bertil"))
        assertTrue(canConfirmRemoval(" bertil ", "bertil"))
        assertFalse(canConfirmRemoval("Bertil", "bertil"))
        assertFalse(canConfirmRemoval("", "bertil"))
        assertFalse(canConfirmRemoval("bert", "bertil"))
    }

    @Test fun `the removal form names the username to type`() {
        var typed by mutableStateOf("")
        content { RemoveUserForm("bertil", typed, onTyped = { typed = it }) }
        compose.onNodeWithText("Skriv användarnamnet bertil för att bekräfta.").assertIsDisplayed()
        compose.onNodeWithText("Användarnamn").performTextInput("bertil")
        assertEquals("bertil", typed)
    }

    @Test fun `removing needs the username and the administrators own password, a reset code the password`() {
        assertFalse(canRemoveUser("bertil", "bertil", ""))
        assertFalse(canRemoveUser("bert", "bertil", "pw"))
        assertTrue(canRemoveUser(" bertil ", "bertil", "pw"))
        assertFalse(canConfirmReset(""))
        assertTrue(canConfirmReset("pw"))
    }

    @Test fun `the password goes only to the two calls the server protects with it`() {
        val seen = ArrayList<String>()
        val cb = AdminCallbacks(
            onResetCode = { u, pw -> seen += "reset:${u.id}:$pw" }, onRemoveUser = { id, pw -> seen += "remove:$id:$pw" },
            onSuspend = { seen += "suspend:$it" }, onSetAdmin = { id, a -> seen += "admin:$id:$a" },
        )
        runAdminAction(AdminAction.RESET_CODE, bertil, "admins-pw", cb)
        runAdminAction(AdminAction.REMOVE, bertil, "admins-pw", cb)
        runAdminAction(AdminAction.SUSPEND, bertil, "admins-pw", cb)
        runAdminAction(AdminAction.MAKE_ADMIN, bertil, "admins-pw", cb)
        assertEquals(listOf("reset:u2:admins-pw", "remove:u2:admins-pw", "suspend:u2", "admin:u2:true"), seen)
    }

    @Test fun `the password field hides what is typed and reports it`() {
        var typed by mutableStateOf("")
        content { AdminPasswordForm(typed, onPassword = { typed = it }) }
        compose.onNodeWithText("Bekräfta med ditt eget lösenord", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Ditt lösenord").performTextInput("admins-pw")
        assertEquals("admins-pw", typed)
        compose.onNodeWithText("Ditt lösenord").assert(
            androidx.compose.ui.test.SemanticsMatcher("shown as dots only") {
                it.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.let { shown -> shown.isNotEmpty() && shown.all { c -> c == '\u2022' } }
            },
        )
    }

    @Test fun `the devices show the push health and a device can be revoked after a confirmation`() {
        val seen = ArrayList<String>()
        content { AdminContent(state(), AdminCallbacks(onRevokeDevice = { u, d -> seen += "$u/$d" })) }
        compose.onNodeWithContentDescription("Visa enheter för Bertil Ek").performClick()
        compose.onNodeWithText("Push: fungerar").assertIsDisplayed()
        compose.onNodeWithText("Push: push-servern svarade inte (3 misslyckade i rad)").assertIsDisplayed()
        compose.onNodeWithText("Push: ingen adress registrerad").assertIsDisplayed()
        compose.onNodeWithContentDescription("Återkalla Surfplatta").performClick()
        compose.onNodeWithText("Enheten loggas ut direkt", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Avbryt").performClick()
        assertTrue(seen.isEmpty())
        compose.onNodeWithContentDescription("Återkalla Surfplatta").performClick()
        compose.onNode(hasText("Återkalla") and hasClickAction()).performClick()
        assertEquals(listOf("u2/dev2"), seen)
    }

    @Test fun `a reset code is shown once with copy and expiry`() {
        val copied = ArrayList<String>()
        var s by mutableStateOf(state().copy(resetCode = ResetCodeUi("Bertil Ek", "RESET-AB12CD34", NOW + 86_400_000)))
        content { AdminContent(s, AdminCallbacks(onCopy = { copied += it }, onDismissResetCode = { s = s.copy(resetCode = null) })) }
        compose.onNodeWithText("RESET-AB12CD34").assertIsDisplayed()
        compose.onNodeWithText("Gäller en gång, till", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Koden visas bara nu", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Kopiera").performClick()
        assertEquals(listOf("RESET-AB12CD34"), copied)
        compose.onNodeWithText("Klar").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("RESET-AB12CD34").assertCountEquals(0)
    }

    @Test fun `the last administrator error is worded`() {
        content { AdminContent(state().copy(error = UiError.LAST_ADMIN), AdminCallbacks()) }
        compose.onNodeWithText("Utse en annan administratör först", substring = true).assertIsDisplayed()
    }

    @Test fun `invitations are listed with their state and an unused one can be revoked after a confirmation`() {
        val seen = ArrayList<String>()
        val invites = listOf(
            Invitation("i1", "Mormor", false, NOW + 86_400_000, 1, null, null, null, 1, 0, 1),
            Invitation("i2", null, false, NOW + 86_400_000, 1, NOW, "u9", null, 1, 1, 0),
            Invitation("i3", "Gammal", false, NOW - 1000, 1, null, null, null, 1, 0, 0),
        )
        content { AdminContent(AdminUiState(tab = AdminTab.INVITATIONS, me = "me", invitations = Loadable.Ready(invites)), AdminCallbacks(onRevokeInvitation = { seen += it })) }
        compose.onNodeWithText("Mormor").assertIsDisplayed()
        compose.onNodeWithText("Giltig").assertIsDisplayed()
        compose.onNodeWithText("Använd").assertIsDisplayed()
        compose.onNodeWithText("Utgången").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Återkalla inbjudan?").assertCountEquals(1)
        compose.onNodeWithContentDescription("Återkalla inbjudan?").performClick()
        compose.onNodeWithText("Koden slutar fungera", substring = true).assertIsDisplayed()
        assertTrue(seen.isEmpty())
        compose.onNodeWithText("Återkalla").performClick()
        assertEquals(listOf("i1"), seen)
    }

    @Test fun `the status tab shows the server's figures`() {
        val stats = se.swahnchat.core.model.AdminStats(
            users = 5, activeDevices = 7, conversations = 4, messages = 1234, attachments = 12, dbBytes = 5_000_000, attachmentsBytes = 9_000_000,
            diskFreeBytes = 20_000_000_000, version = "0.5.0", migration = se.swahnchat.core.model.MigrationInfo("abc", "abc"), uptimeSeconds = 90_000,
            dbMaintenance = se.swahnchat.core.model.DbMaintenanceInfo(at = NOW, dialect = "sqlite", ok = true, vacuum = "not_needed"),
        )
        content { AdminContent(AdminUiState(tab = AdminTab.STATUS, me = "me", stats = Loadable.Ready(stats)), AdminCallbacks()) }
        compose.onNodeWithText("0.5.0").assertIsDisplayed()
        compose.onNodeWithText("Uppdaterat (abc)").assertIsDisplayed()
        compose.onNodeWithText("1234").assertIsDisplayed()
        compose.onNodeWithText("1 d 1 h").performScrollTo().assertIsDisplayed()
    }

    @Test fun `the log shows the event, the time and who did it, and pages on request`() {
        val seen = ArrayList<String>()
        val events = listOf(
            AuditEvent(9, NOW, "me", "user_suspended", "u3"),
            AuditEvent(8, NOW - 1000, "me", "password_reset_issued", "u2"),
            AuditEvent(7, NOW - 2000, null, "something_new", null),
        )
        content {
            AdminContent(
                AdminUiState(tab = AdminTab.LOG, me = "me", users = users(me, bertil, cecilia), audit = Loadable.Ready(events), auditHasMore = true),
                AdminCallbacks(onLoadMoreAudit = { seen += "more" }),
            )
        }
        compose.onNodeWithText("Konto avstängt").assertIsDisplayed()
        compose.onNodeWithText("Av Anna Lindqvist (@anna) → Cecilia Berg (@cecilia)").assertIsDisplayed()
        compose.onNodeWithText("Återställningskod skapad").assertIsDisplayed()
        compose.onNodeWithText("something_new").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Visa äldre").performScrollTo().performClick()
        assertEquals(listOf("more"), seen)
    }

    @Test fun `tabs switch`() {
        val seen = ArrayList<AdminTab>()
        content { AdminContent(state(), AdminCallbacks(onTab = { seen += it })) }
        compose.onNodeWithText("Inbjudningar").performClick()
        compose.onNodeWithText("Status").performClick()
        compose.onNodeWithText("Logg").performClick()
        assertEquals(listOf(AdminTab.INVITATIONS, AdminTab.STATUS, AdminTab.LOG), seen)
    }

    @Test fun `a part that could not be read offers to try again`() {
        var retried = 0
        content { AdminContent(AdminUiState(tab = AdminTab.USERS, me = "me", users = Loadable.Failed), AdminCallbacks(onReload = { retried++ })) }
        compose.onNodeWithText("Det gick inte att hämta från servern.").assertIsDisplayed()
        compose.onNodeWithText("Försök igen").performClick()
        assertEquals(1, retried)
    }
}
