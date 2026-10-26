package se.swahnchat.app.ui.screens

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.swahnchat.app.BuildConfig
import se.swahnchat.app.data.Accent
import se.swahnchat.app.data.AppLanguage
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.ChatBackground
import se.swahnchat.app.data.QuietHoursSettings
import se.swahnchat.app.data.LockTimeout
import se.swahnchat.app.data.NotificationPrivacy
import se.swahnchat.app.platform.security.AppLock
import se.swahnchat.app.data.ChatRepository
import se.swahnchat.app.data.SessionManager
import se.swahnchat.app.data.ThemeMode
import se.swahnchat.app.data.UiError
import se.swahnchat.app.data.toUiError
import se.swahnchat.app.media.AttachmentPreparer
import se.swahnchat.app.platform.push.Distributor
import se.swahnchat.app.platform.push.PushManager
import se.swahnchat.app.platform.push.PushStatus
import se.swahnchat.app.qr.InviteQr
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.model.CreatedInvitation
import javax.inject.Inject

@Immutable
data class DeviceUi(val id: String, val name: String, val lastSeen: Long, val isCurrent: Boolean)

sealed interface DevicesUi {
    data object Loading : DevicesUi
    data object Failed : DevicesUi
    data class Loaded(val devices: List<DeviceUi>) : DevicesUi
}

@Immutable
data class SettingsUiState(
    val displayName: String = "",
    val userId: String = "",
    val username: String = "",
    val avatar: AvatarUi = AvatarUi(""),
    val isAdmin: Boolean = false,
    val serverUrl: String = "",
    val deviceName: String = "",
    val devices: DevicesUi = DevicesUi.Loading,
    val pushStatus: PushStatus = PushStatus.NO_DISTRIBUTOR,
    val distributors: List<Distributor> = emptyList(),
    val selectedDistributor: String? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val notificationPrivacy: NotificationPrivacy = NotificationPrivacy.FULL,
    val blockScreenshots: Boolean = false,
    val callsRelayOnly: Boolean = false,
    val appLockEnabled: Boolean = false,
    val lockTimeout: LockTimeout = LockTimeout.IMMEDIATELY,
    val quietHours: QuietHoursSettings = QuietHoursSettings(),
    val sendReadReceipts: Boolean = true,
    val showTyping: Boolean = true,
    /** The server's word on whether presence is shared; null until it answered (the switch is then off and disabled). */
    val sharePresence: Boolean? = null,
    val highContrast: Boolean = false,
    val accent: Accent = Accent.SYSTEM,
    val chatBackground: ChatBackground = ChatBackground.PLAIN,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val versionName: String = "",
    /** Messages that failed for good (the badge on the outbox row). */
    val failedCount: Int = 0,
    /** The app may run in the background without battery optimisation holding it back. */
    val batteryUnrestricted: Boolean = false,
    val busy: Boolean = false,
    val error: UiError? = null,
    val passwordChanged: Boolean = false,
    val invitation: CreatedInvitation? = null,
    /** The text of the invitation's QR code (`swahnchat://invite?...`), for the new person to scan; null without an invitation. */
    val invitationQr: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    repo: ChatRepository,
    private val engine: SwahnChatEngine,
    private val session: SessionManager,
    private val push: PushManager,
    private val prefs: AppPrefs,
    private val preparer: AttachmentPreparer,
    private val lock: AppLock,
    private val device: se.swahnchat.app.diagnostics.DeviceStatus,
) : ViewModel() {
    private val devices = MutableStateFlow<DevicesUi>(DevicesUi.Loading)
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<UiError?>(null)
    private val passwordChanged = MutableStateFlow(false)
    private val invitation = MutableStateFlow<CreatedInvitation?>(null)
    private val distributorsTick = MutableStateFlow(0)

    private val account = combine(engine.account, repo.userMap()) { a, users -> a to a?.let { users[it.userId] } }
    private val sharePresence = MutableStateFlow<Boolean?>(null)

    private val battery = MutableStateFlow(device.batteryUnrestricted())

    private class Extras(
        val quiet: QuietHoursSettings, val receipts: Boolean, val typing: Boolean, val presence: Boolean?,
        val highContrast: Boolean, val accent: Accent, val background: ChatBackground, val failed: Int, val battery: Boolean,
    )

    private val extras = combine(
        combine(prefs.quietHours, prefs.sendReadReceipts, prefs.showTyping, ::Triple),
        sharePresence,
        combine(prefs.highContrast, prefs.accent, prefs.chatBackground, ::Triple),
        combine(repo.failedCount(), battery, ::Pair),
    ) { (quiet, receipts, typing), presence, (contrast, accent, background), (failed, battery) ->
        Extras(quiet, receipts, typing, presence, contrast, accent, background, failed, battery)
    }

    private val transient = combine(busy, error, passwordChanged, invitation, extras) { b, e, p, i, x -> arrayOf<Any?>(b, e, p, i, x) }

    private class Choices0(
        val theme: ThemeMode, val privacy: NotificationPrivacy, val blockScreenshots: Boolean, val lockEnabled: Boolean, val lockTimeout: LockTimeout,
    )

    private class Choices(
        val theme: ThemeMode, val privacy: NotificationPrivacy, val blockScreenshots: Boolean, val lockEnabled: Boolean, val lockTimeout: LockTimeout,
        val callsRelayOnly: Boolean,
    )

    private val choices = combine(
        combine(prefs.theme, prefs.notificationPrivacy, prefs.blockScreenshots, prefs.appLockEnabled, prefs.lockTimeout, ::Choices0),
        prefs.callsRelayOnly,
    ) { c, relay -> Choices(c.theme, c.privacy, c.blockScreenshots, c.lockEnabled, c.lockTimeout, relay) }

    val state: StateFlow<SettingsUiState> = combine(account, devices, push.status, choices, transient) { (a, user), d, ps, c, t ->
        // distributorsTick makes the list re-read when the screen is shown again (a distributor may have been installed)
        distributorsTick.value
        SettingsUiState(
            displayName = user?.displayName ?: a?.displayName.orEmpty(),
            userId = a?.userId.orEmpty(),
            username = a?.username.orEmpty(),
            avatar = AvatarUi(user?.displayName ?: a?.displayName.orEmpty(), user?.avatarAttachmentId),
            isAdmin = a?.isAdmin == true, serverUrl = a?.serverUrl.orEmpty(), deviceName = a?.deviceName.orEmpty(),
            devices = d, pushStatus = ps, distributors = push.distributors(), selectedDistributor = push.selectedDistributor(),
            theme = c.theme, notificationPrivacy = c.privacy, blockScreenshots = c.blockScreenshots, callsRelayOnly = c.callsRelayOnly,
            appLockEnabled = c.lockEnabled, lockTimeout = c.lockTimeout, versionName = BuildConfig.VERSION_NAME,
            quietHours = (t[4] as Extras).quiet, sendReadReceipts = (t[4] as Extras).receipts, showTyping = (t[4] as Extras).typing,
            sharePresence = (t[4] as Extras).presence, highContrast = (t[4] as Extras).highContrast, accent = (t[4] as Extras).accent,
            chatBackground = (t[4] as Extras).background, failedCount = (t[4] as Extras).failed, batteryUnrestricted = (t[4] as Extras).battery,
            busy = t[0] as Boolean, error = t[1] as UiError?, passwordChanged = t[2] as Boolean, invitation = t[3] as CreatedInvitation?,
            invitationQr = (t[3] as CreatedInvitation?)?.let { inv -> a?.serverUrl?.let { server -> InviteQr.payload(server, inv.code) } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Called each time the screen is shown. */
    fun onShown() {
        distributorsTick.update { it + 1 }
        battery.value = device.batteryUnrestricted()
        push.refreshStatus()
        loadDevices()
        loadSharePresence()
    }

    /** Presence is a setting of the account on the server, not of this phone: read it every time the screen is shown. */
    private fun loadSharePresence() {
        viewModelScope.launch {
            sharePresence.value = try {
                engine.sharePresence()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                sharePresence.value // offline: keep what we last knew (null = unknown)
            }
        }
    }

    private fun loadDevices() {
        viewModelScope.launch {
            val me = engine.account.value ?: return@launch
            devices.value = try {
                DevicesUi.Loaded(engine.myDevices().map { DeviceUi(it.id, it.name, it.lastSeenAt, it.id == me.deviceId) })
            } catch (e: Exception) {
                DevicesUi.Failed
            }
        }
    }

    private fun run(block: suspend () -> Unit) {
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                error.value = e.toUiError()
            } finally {
                busy.value = false
            }
        }
    }

    fun setDisplayName(name: String) = run { engine.updateProfile(displayName = name) }
    fun setAvatar(uri: Uri) = run { engine.updateProfile(avatarAttachmentId = engine.uploadAvatar(preparer.avatarJpeg(uri), "image/jpeg")) }
    fun clearAvatar() = run { engine.updateProfile(clearAvatar = true) }

    fun changePassword(current: String, new: String) = run {
        engine.changePassword(current, new)
        passwordChanged.value = true
        loadDevices() // the server revoked every other device
    }

    fun selectDistributor(packageName: String) {
        push.select(packageName)
    }

    fun setTheme(mode: ThemeMode) = prefs.setTheme(mode)
    fun setNotificationPrivacy(value: NotificationPrivacy) = prefs.setNotificationPrivacy(value)
    fun setBlockScreenshots(value: Boolean) = prefs.setBlockScreenshots(value)
    fun setCallsRelayOnly(value: Boolean) = prefs.setCallsRelayOnly(value)
    fun setLockTimeout(value: LockTimeout) = prefs.setLockTimeout(value)
    fun setQuietHours(value: QuietHoursSettings) = prefs.setQuietHours(value)
    fun setSendReadReceipts(value: Boolean) = prefs.setSendReadReceipts(value)
    fun setShowTyping(value: Boolean) = prefs.setShowTyping(value)
    fun setHighContrast(value: Boolean) = prefs.setHighContrast(value)
    fun setAccent(value: Accent) = prefs.setAccent(value)
    fun setChatBackground(value: ChatBackground) = prefs.setChatBackground(value)

    /** The language is applied by [se.swahnchat.app.AppLocale] (it needs the activity); this keeps the choice. */
    fun applyLanguage(activity: android.app.Activity, value: AppLanguage) = se.swahnchat.app.AppLocale.set(activity, prefs, value)

    /** Switches presence on the server; the switch shows the server's answer, not the wish. A failure is shown like any other. */
    fun setSharePresence(value: Boolean) = run { sharePresence.value = engine.setSharePresence(value) }

    /** Called after the user authenticated (the screen asks first). */
    fun setAppLock(enabled: Boolean) = if (enabled) lock.enable() else lock.disable()

    fun createInvitation(note: String, hours: Int, admin: Boolean) = run {
        invitation.value = engine.createInvitation(note.trim().ifEmpty { null }, hours, admin)
    }

    fun dismissInvitation() { invitation.value = null }
    fun dismissPasswordChanged() { passwordChanged.value = false }
    fun dismissError() { error.value = null }

    fun logout() = session.logoutAsync()
}
