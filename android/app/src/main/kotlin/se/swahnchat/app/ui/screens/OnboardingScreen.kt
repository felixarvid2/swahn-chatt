package se.swahnchat.app.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.swahnchat.app.R
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.diagnostics.BatteryOptimization
import se.swahnchat.app.diagnostics.DeviceStatus
import se.swahnchat.app.diagnostics.PushTestResult
import se.swahnchat.app.diagnostics.PushTester
import se.swahnchat.app.platform.push.Distributor
import se.swahnchat.app.platform.push.PushManager
import se.swahnchat.app.platform.push.PushStatus
import se.swahnchat.core.client.SwahnChatEngine
import javax.inject.Inject

/** The four steps of the guide, in order. */
enum class OnboardingStep { NOTIFICATIONS, PUSH, BATTERY, SAFETY }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.NOTIFICATIONS,
    /** Android 13+ and the permission is not granted yet: the button asks for it. */
    val needsNotificationPermission: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val distributors: List<Distributor> = emptyList(),
    val selectedDistributor: String? = null,
    val pushStatus: PushStatus = PushStatus.NO_DISTRIBUTOR,
    val testing: Boolean = false,
    val testResult: PushTestResult? = null,
    val batteryUnrestricted: Boolean = false,
    val userId: String = "",
) {
    val stepCount: Int get() = OnboardingStep.entries.size
    val isFirst: Boolean get() = step == OnboardingStep.entries.first()
    val isLast: Boolean get() = step == OnboardingStep.entries.last()
}

class OnboardingCallbacks(
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
    /** Leaves the guide without going through the rest; it can be opened again from the settings. */
    val onSkip: () -> Unit = {},
    val onFinish: () -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
    val onSelectDistributor: (String) -> Unit = {},
    val onTestPush: () -> Unit = {},
    val onBattery: () -> Unit = {},
    val onOpenSafety: () -> Unit = {},
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: AppPrefs,
    private val push: PushManager,
    private val device: DeviceStatus,
    private val engine: SwahnChatEngine,
    testers: PushTester.Factory,
) : ViewModel() {
    private val tester = testers.create()
    private val _state = MutableStateFlow(OnboardingUiState(userId = engine.account.value?.userId.orEmpty()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { push.status.collect { s -> _state.update { it.copy(pushStatus = s) } } }
    }

    /** Reads what the phone says again; called each time the screen comes back (from a system page). */
    fun refresh() {
        _state.update {
            it.copy(
                needsNotificationPermission = device.needsNotificationPermission(), notificationsAllowed = device.notificationsAllowed(),
                distributors = runCatching { push.distributors() }.getOrDefault(emptyList()),
                selectedDistributor = runCatching { push.selectedDistributor() }.getOrNull(),
                batteryUnrestricted = device.batteryUnrestricted(),
            )
        }
        push.refreshStatus()
    }

    fun next() = _state.update { s -> OnboardingStep.entries.getOrNull(s.step.ordinal + 1)?.let { s.copy(step = it) } ?: s }
    fun back() = _state.update { s -> OnboardingStep.entries.getOrNull(s.step.ordinal - 1)?.let { s.copy(step = it) } ?: s }

    /** The permission dialog was answered (the guide asked, so the conversation list does not ask a second time). */
    fun onNotificationPermissionAnswered() {
        prefs.notificationPermissionAsked = true
        refresh()
    }

    fun selectDistributor(packageName: String) {
        push.select(packageName)
        refresh()
    }

    fun testPush() {
        if (_state.value.testing) return
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val r = tester.run()
            _state.update { it.copy(testing = false, testResult = r) }
        }
    }

    /** Shown or skipped: either way it does not appear by itself again. */
    fun markDone() {
        prefs.onboardingDone = true
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit, onOpenSafety: (userId: String) -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onNotificationPermissionAnswered() }
    LifecycleResumeEffect(vm) { vm.refresh(); onPauseOrDispose {} }
    val finish = { vm.markDone(); onDone() }
    OnboardingContent(
        state,
        OnboardingCallbacks(
            onNext = vm::next, onBack = vm::back, onSkip = finish, onFinish = finish,
            onAllowNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onOpenNotificationSettings = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            },
            onSelectDistributor = vm::selectDistributor, onTestPush = vm::testPush,
            onBattery = { BatteryOptimization.open(context) },
            onOpenSafety = { vm.markDone(); onOpenSafety(state.userId) },
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingContent(state: OnboardingUiState, cb: OnboardingCallbacks, modifier: Modifier = Modifier) {
    var pickDistributor by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_title)) },
                actions = { TextButton(onClick = cb.onSkip, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_skip)) } },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (state.isFirst) Text("") else OutlinedButton(onClick = cb.onBack,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_back)) }
                Button(onClick = if (state.isLast) cb.onFinish else cb.onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (state.isLast) R.string.onboarding_done else R.string.onboarding_next))
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.onboarding_step_of, state.step.ordinal + 1, state.stepCount), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(progress = { (state.step.ordinal + 1f) / state.stepCount }, modifier = Modifier.fillMaxWidth())
            when (state.step) {
                OnboardingStep.NOTIFICATIONS -> NotificationsStep(state, cb)
                OnboardingStep.PUSH -> PushStep(state, cb, onPick = { pickDistributor = true })
                OnboardingStep.BATTERY -> BatteryStep(state.batteryUnrestricted, cb.onBattery)
                OnboardingStep.SAFETY -> SafetyStep(cb.onOpenSafety)
            }
        }
    }
    if (pickDistributor) {
        DistributorDialog(state.distributors, state.selectedDistributor, onDismiss = { pickDistributor = false },
            onSelect = { cb.onSelectDistributor(it); pickDistributor = false })
    }
}

@Composable
private fun StepHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp).semantics { heading() })
    }
}

/** A line with a check or a warning in front: what the phone says now. */
@Composable
internal fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber, contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun NotificationsStep(state: OnboardingUiState, cb: OnboardingCallbacks) {
    StepHeader(Icons.Filled.Notifications, R.string.onboarding_notifications_title)
    Text(stringResource(R.string.onboarding_notifications_text), style = MaterialTheme.typography.bodyLarge)
    StatusLine(state.notificationsAllowed, stringResource(if (state.notificationsAllowed) R.string.notifications_on else R.string.notifications_off))
    if (!state.notificationsAllowed) {
        Button(
            onClick = if (state.needsNotificationPermission) cb.onAllowNotifications else cb.onOpenNotificationSettings,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                stringResource(
                    if (state.needsNotificationPermission) R.string.onboarding_allow_notifications else R.string.onboarding_open_notification_settings,
                ),
            )
        }
    }
}

@Composable
private fun PushStep(state: OnboardingUiState, cb: OnboardingCallbacks, onPick: () -> Unit) {
    StepHeader(Icons.Filled.Apps, R.string.onboarding_push_title)
    Text(stringResource(R.string.onboarding_push_text), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.onboarding_push_ntfy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val installed = state.distributors.isNotEmpty()
    StatusLine(
        installed,
        if (installed) stringResource(R.string.onboarding_push_installed, state.distributors.joinToString { it.label })
        else stringResource(R.string.onboarding_push_none_installed),
    )
    if (installed) {
        StatusLine(
            state.pushStatus == PushStatus.ACTIVE,
            stringResource(
                when (state.pushStatus) {
                    PushStatus.ACTIVE -> R.string.onboarding_push_active
                    PushStatus.REGISTERING -> R.string.onboarding_push_registering
                    PushStatus.FAILED -> R.string.push_failed
                    else -> R.string.onboarding_push_not_chosen
                },
            ),
        )
        OutlinedButton(onClick = onPick, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_push_choose)) }
        PushTestControls(state.testing, state.testResult, enabled = state.pushStatus == PushStatus.ACTIVE, onTest = cb.onTestPush)
    }
}

/** "Skicka testnotis" with what came of it. */
@Composable
internal fun PushTestControls(testing: Boolean, result: PushTestResult?, enabled: Boolean, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onTest, enabled = enabled && !testing, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.push_test_button)) }
        if (testing) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(24.dp), strokeWidth = 2.dp)
    }
    result?.let { StatusLine(it is PushTestResult.Delivered, pushTestText(it)) }
}

@Composable
internal fun pushTestText(result: PushTestResult): String = when (result) {
    is PushTestResult.Delivered -> stringResource(R.string.push_test_delivered, result.status)
    is PushTestResult.Refused -> stringResource(R.string.push_test_refused, result.status)
    PushTestResult.NoAnswer -> stringResource(R.string.push_test_no_answer)
    PushTestResult.Waiting -> stringResource(R.string.push_test_waiting)
    PushTestResult.NotConfigured -> stringResource(R.string.push_test_not_configured)
    PushTestResult.RateLimited -> stringResource(R.string.push_test_rate_limited)
    is PushTestResult.Failed -> errorTexts()(result.error)
}

@Composable
private fun BatteryStep(unrestricted: Boolean, onRequest: () -> Unit) {
    StepHeader(Icons.Filled.BatteryChargingFull, R.string.onboarding_battery_title)
    BatteryExplanation(unrestricted, onRequest)
}

/** Why the exemption matters, what it is now, and the button. Used by the guide and by the diagnostics. */
@Composable
internal fun BatteryExplanation(unrestricted: Boolean, onRequest: () -> Unit) {
    Text(stringResource(R.string.battery_text), style = MaterialTheme.typography.bodyLarge)
    StatusLine(unrestricted, stringResource(if (unrestricted) R.string.battery_status_ok else R.string.battery_status_restricted))
    if (!unrestricted) {
        Button(onClick = onRequest, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.battery_allow)) }
    }
}

@Composable
private fun SafetyStep(onOpenSafety: () -> Unit) {
    StepHeader(Icons.Filled.Security, R.string.onboarding_safety_title)
    Text(stringResource(R.string.onboarding_safety_text), style = MaterialTheme.typography.bodyLarge)
    OutlinedButton(onClick = onOpenSafety, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_safety_open)) }
}
