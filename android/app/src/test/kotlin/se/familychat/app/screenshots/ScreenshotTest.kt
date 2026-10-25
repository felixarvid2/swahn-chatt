package se.familychat.app.screenshots

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.familychat.app.data.ThemeMode
import se.familychat.app.data.ServerCheck
import se.familychat.app.ui.components.BubbleActions
import se.familychat.app.ui.components.LocalNowMillis
import se.familychat.app.ui.nav.AppFrame
import se.familychat.app.ui.screens.AuthMode
import se.familychat.app.ui.screens.ContactContent
import se.familychat.app.ui.screens.ContactUiState
import se.familychat.app.ui.screens.ConversationCallbacks
import se.familychat.app.ui.screens.ConversationContent
import se.familychat.app.ui.screens.ConversationUiState
import se.familychat.app.ui.screens.ConversationsContent
import se.familychat.app.ui.screens.DeviceSafetyUi
import se.familychat.app.ui.screens.DeviceUi
import se.familychat.app.ui.screens.DevicesUi
import se.familychat.app.ui.screens.FieldError
import se.familychat.app.ui.screens.GroupCreateContent
import se.familychat.app.ui.screens.GroupCreateState
import se.familychat.app.ui.screens.GroupInfoCallbacks
import se.familychat.app.ui.screens.GroupInfoContent
import se.familychat.app.ui.screens.GroupInfoState
import se.familychat.app.ui.screens.LoginCallbacks
import se.familychat.app.ui.screens.LoginContent
import se.familychat.app.ui.screens.LoginUiState
import se.familychat.app.ui.screens.NewConversationContent
import se.familychat.app.ui.screens.NewConversationState
import se.familychat.app.ui.screens.PendingKeyUi
import se.familychat.app.ui.screens.RecordingUi
import se.familychat.app.ui.screens.AutoDownloadContent
import se.familychat.app.ui.screens.InvitationCreatedPreview
import se.familychat.app.ui.screens.MessageInfoContent
import se.familychat.app.ui.screens.MessageInfoState
import se.familychat.app.ui.screens.RecipientInfo
import se.familychat.app.ui.screens.StorageContent
import se.familychat.app.ui.screens.StorageRowUi
import se.familychat.app.ui.screens.StorageUiState
import se.familychat.app.ui.screens.ServerSettingsContent
import se.familychat.app.ui.screens.ServerSettingsUiState
import se.familychat.app.ui.screens.LockContent
import se.familychat.app.ui.screens.SearchContent
import se.familychat.app.ui.screens.SearchUiState
import se.familychat.app.ui.screens.SettingsCallbacks
import se.familychat.app.ui.screens.SettingsContent
import se.familychat.app.ui.screens.SettingsUiState
import se.familychat.app.ui.model.AvatarUi
import se.familychat.app.ui.model.ChatRow
import se.familychat.app.ui.model.ConnectionUi
import se.familychat.app.ui.model.SafetyState
import se.familychat.app.ui.model.SafetyUi
import se.familychat.app.ui.theme.FamilyChatTheme
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/**
 * Screenshots of the main screens with fake state, in light and dark, rendered on the JVM (Robolectric native graphics),
 * written to `app/screenshots/`. They are a review aid, not a regression oracle: nothing compares them with older images.
 * Swedish is the default language; two screens are also captured in English.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = File("screenshots").also { it.mkdirs() }

    @Before fun fixedWorld() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun shot(name: String, dark: Boolean, insecure: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            FamilyChatTheme(darkTheme = dark, dynamicColor = false) {
                CompositionLocalProvider(LocalNowMillis provides Fixtures.NOW) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppFrame(insecure, content) }
                }
            }
        }
        compose.waitForIdle()
        // captureToImage() waits for a draw pass that never comes under Robolectric; drawing the decor view is enough here.
        val image = compose.activity.window.decorView.drawToBitmap()
        FileOutputStream(File(dir, "${name}_${if (dark) "dark" else "light"}.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // The compose rule can only set content once per test, so each variant is its own test.

    private val convState = ConversationUiState(
        title = "Familjen Ek", isGroup = true, memberCount = 4, avatar = AvatarUi("Familjen Ek"), loaded = true,
        draft = "",
    )

    private val directState = ConversationUiState(
        title = "Bertil Ek", isGroup = false, avatar = Fixtures.bob.avatar, loaded = true, otherUserId = "bob",
        typingNames = listOf("Bertil"),
        replyTo = se.familychat.app.ui.model.ReplyUi("a", "Bertil Ek", "Ska vi ses vid fyra?", null, false), draft = "Absolut, ses där",
    )

    /** [rows] are oldest first, like the fixtures; the screen gets them newest first, as pages. */
    @Composable private fun ConversationUnderTest(
        state: ConversationUiState,
        playback: se.familychat.app.ui.components.PlaybackUi = Fixtures.idle,
        firstVisible: Int = 0,
        rows: List<ChatRow> = if (state.isGroup) Fixtures.groupRows else Fixtures.directRows,
    ) {
        val items = flowOf(Fixtures.pages(rows)).collectAsLazyPagingItems()
        ConversationContent(
            state.copy(latestId = (rows.lastOrNull { it is ChatRow.Message } as? ChatRow.Message)?.message?.id), items, playback,
            BubbleActions(), ConversationCallbacks(),
            listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = firstVisible),
        )
    }

    @Test fun conversationsLight() = shot("1_conversations", false) { ConversationsContent(Fixtures.conversationItems, false, {}, {}, {}, {}) }
    @Test fun conversationsDark() = shot("1_conversations", true) { ConversationsContent(Fixtures.conversationItems, false, {}, {}, {}, {}) }

    @Test fun conversationLight() = shot("2_conversation_group", false) { ConversationUnderTest(convState, Fixtures.playing) }
    @Test fun conversationDark() = shot("2_conversation_group", true) { ConversationUnderTest(convState, Fixtures.playing) }

    // scrolled up to the photo, voice message, video and the reply
    @Test fun conversationMediaLight() = shot("2b_conversation_group_media", false) { ConversationUnderTest(convState, Fixtures.playing, firstVisible = 7) }
    @Test fun conversationMediaDark() = shot("2b_conversation_group_media", true) { ConversationUnderTest(convState, Fixtures.playing, firstVisible = 7) }

    // opened with six unread messages: the divider at the top, the jump-to-bottom button with the count
    private val unreadState = convState.copy(openUnread = 6, newMessages = 6)
    @Test fun conversationUnreadLight() = shot("2c_conversation_unread_fab", false) { ConversationUnderTest(unreadState, rows = Fixtures.groupRowsUnread) }
    @Test fun conversationUnreadDark() = shot("2c_conversation_unread_fab", true) { ConversationUnderTest(unreadState, rows = Fixtures.groupRowsUnread) }

    @Test fun conversationOfflineLight() = shot("2d_conversation_offline", false) { ConversationUnderTest(convState.copy(connection = ConnectionUi.OFFLINE), Fixtures.playing) }
    @Test fun conversationOfflineDark() = shot("2d_conversation_offline", true) { ConversationUnderTest(convState.copy(connection = ConnectionUi.OFFLINE), Fixtures.playing) }
    @Test fun conversationConnectingLight() = shot("2e_conversation_connecting", false) { ConversationUnderTest(convState.copy(connection = ConnectionUi.CONNECTING), Fixtures.playing) }

    @Test fun conversationsOfflineLight() = shot("1b_conversations_offline", false) {
        ConversationsContent(Fixtures.conversationItems, false, {}, {}, {}, {}, connection = ConnectionUi.OFFLINE)
    }

    @Test fun conversationVerifiedLight() = shot("3b_conversation_verified", false) { ConversationUnderTest(directState.copy(safety = SafetyUi(SafetyState.VERIFIED))) }
    @Test fun conversationVerifiedDark() = shot("3b_conversation_verified", true) { ConversationUnderTest(directState.copy(safety = SafetyUi(SafetyState.VERIFIED))) }
    @Test fun conversationSafetyWarningLight() = shot("3c_conversation_safety_warning", false) {
        ConversationUnderTest(directState.copy(safety = SafetyUi(SafetyState.WARNING, "bob")))
    }

    @Test fun directLight() = shot("3_conversation_direct", false) { ConversationUnderTest(directState) }
    @Test fun directDark() = shot("3_conversation_direct", true) { ConversationUnderTest(directState) }

    @Test fun recordingLight() = shot("4_conversation_recording", false) {
        ConversationUnderTest(directState.copy(draft = "", replyTo = null, typingNames = emptyList(), recording = RecordingUi(7_300, locked = true)))
    }
    @Test fun recordingDark() = shot("4_conversation_recording", true) {
        ConversationUnderTest(directState.copy(draft = "", replyTo = null, typingNames = emptyList(), recording = RecordingUi(7_300, locked = true)))
    }

    private val login = LoginUiState(serverUrl = "https://hemma.tail1234.ts.net", username = "anna", password = "hemligt-losen", deviceName = "Google Pixel 8")
    @Test fun loginLight() = shot("5_login", false) { LoginContent(login, LoginCallbacks()) }
    @Test fun loginDark() = shot("5_login", true) { LoginContent(login, LoginCallbacks()) }

    private val redeem = login.copy(
        mode = AuthMode.REDEEM, code = "K7QX-93MD", displayName = "Anna", confirmPassword = "annat", fieldErrors = setOf(FieldError.PASSWORD_MISMATCH),
        error = se.familychat.app.data.UiError.INVALID_INVITE,
    )
    @Test fun redeemLight() = shot("6_login_invitation", false) { LoginContent(redeem, LoginCallbacks()) }
    @Test fun redeemDark() = shot("6_login_invitation", true) { LoginContent(redeem, LoginCallbacks()) }

    private val serverOk = ServerSettingsUiState(url = "https://hemma.tail1234.ts.net", result = ServerCheck.Reachable("https://hemma.tail1234.ts.net/api/v1/", true))
    @Test fun serverLight() = shot("7_server_settings", false) { ServerSettingsContent(serverOk, {}, {}, {}) }
    @Test fun serverDark() = shot("7_server_settings", true) { ServerSettingsContent(serverOk, {}, {}, {}) }

    private val serverHttp = ServerSettingsUiState(url = "http://192.168.1.20:8000", result = ServerCheck.Reachable("http://192.168.1.20:8000/api/v1/", false))
    @Test fun serverInsecureLight() = shot("8_server_insecure", false, insecure = true) { ServerSettingsContent(serverHttp, {}, {}, {}) }
    @Test fun serverInsecureDark() = shot("8_server_insecure", true, insecure = true) { ServerSettingsContent(serverHttp, {}, {}, {}) }

    @Test fun newConversationLight() = shot("9_new_conversation", false) { NewConversationContent(NewConversationState(Fixtures.people, loaded = true), {}, {}, {}, {}) }
    @Test fun newConversationDark() = shot("9_new_conversation", true) { NewConversationContent(NewConversationState(Fixtures.people, loaded = true), {}, {}, {}, {}) }

    private val groupCreate = GroupCreateState(name = "Midsommar", users = Fixtures.people, selected = setOf("bob", "emma"))
    @Test fun groupCreateLight() = shot("10_group_create", false) { GroupCreateContent(groupCreate, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun groupCreateDark() = shot("10_group_create", true) { GroupCreateContent(groupCreate, {}, {}, {}, {}, {}, {}, {}) }

    private val groupInfo = GroupInfoState(name = "Familjen Ek", avatar = AvatarUi("Familjen Ek"), members = Fixtures.members, meIsAdmin = true, addable = listOf(Fixtures.emma))
    @Test fun groupInfoLight() = shot("11_group_info", false) { GroupInfoContent(groupInfo, GroupInfoCallbacks()) }
    @Test fun groupInfoDark() = shot("11_group_info", true) { GroupInfoContent(groupInfo, GroupInfoCallbacks()) }

    private val settings = SettingsUiState(
        displayName = "Anna Lindqvist", username = "anna", avatar = Fixtures.anna.avatar, isAdmin = true, serverUrl = "https://hemma.tail1234.ts.net",
        deviceName = "Google Pixel 8",
        devices = DevicesUi.Loaded(
            listOf(
                DeviceUi("d1", "Google Pixel 8", Fixtures.NOW, true),
                DeviceUi("d2", "Surfplatta", Fixtures.at(10, 20, 8, 0), false),
            ),
        ),
        pushStatus = Fixtures.pushActive, distributors = Fixtures.distributors, selectedDistributor = "io.heckel.ntfy",
        theme = ThemeMode.SYSTEM, versionName = "0.2.0",
        notificationPrivacy = se.familychat.app.data.NotificationPrivacy.NAME_ONLY, blockScreenshots = true, appLockEnabled = true,
        lockTimeout = se.familychat.app.data.LockTimeout.MINUTES_5,
    )
    @Test fun settingsLight() = shot("12_settings", false) { SettingsContent(settings, SettingsCallbacks()) }
    @Test fun settingsDark() = shot("12_settings", true) { SettingsContent(settings, SettingsCallbacks()) }

    // the privacy section sits far down: a tall window shows the whole page
    @Test fun settingsPrivacyLight() {
        RuntimeEnvironment.setQualifiers("sv-w360dp-h1500dp-xhdpi")
        shot("12b_settings_privacy", false) { SettingsContent(settings, SettingsCallbacks()) }
    }
    @Test fun settingsPrivacyDark() {
        RuntimeEnvironment.setQualifiers("sv-w360dp-h1500dp-xhdpi")
        shot("12b_settings_privacy", true) { SettingsContent(settings, SettingsCallbacks()) }
    }

    @Test fun conversationsOrganisedLight() = shot("1c_conversations_pinned_muted_archived", false) {
        ConversationsContent(Fixtures.conversationItemsOrganised, false, {}, {}, {}, {}, archivedExpanded = true)
    }
    @Test fun conversationsOrganisedDark() = shot("1c_conversations_pinned_muted_archived", true) {
        ConversationsContent(Fixtures.conversationItemsOrganised, false, {}, {}, {}, {}, archivedExpanded = true)
    }
    @Test fun conversationsOrganisedCollapsedLight() = shot("1d_conversations_archived_collapsed", false) {
        ConversationsContent(Fixtures.conversationItemsOrganised, false, {}, {}, {}, {})
    }

    private val searchAll = SearchUiState(query = "middag", searched = true, groups = Fixtures.searchGroups)
    @Test fun searchLight() = shot("16_search", false) { SearchContent(searchAll, {}, {}, { _, _ -> }, autoFocus = false) }
    @Test fun searchDark() = shot("16_search", true) { SearchContent(searchAll, {}, {}, { _, _ -> }, autoFocus = false) }

    private val searchIn = SearchUiState(query = "middag", conversationId = "g1", conversationTitle = "Familjen Ek", searched = true, groups = Fixtures.searchGroups.take(1))
    @Test fun searchInConversationLight() = shot("16b_search_in_conversation", false) { SearchContent(searchIn, {}, {}, { _, _ -> }, autoFocus = false) }
    @Test fun searchEmptyLight() = shot("16c_search_no_results", false) { SearchContent(SearchUiState(query = "xyz", searched = true), {}, {}, { _, _ -> }, autoFocus = false) }

    @Test fun lockLight() = shot("17_lock", false) { LockContent(onUnlock = {}) }
    @Test fun lockDark() = shot("17_lock", true) { LockContent(onUnlock = {}) }

    private val digits = listOf("37912", "61880", "03483", "41262", "93841", "20197", "58214", "67330", "11925", "48660", "07351", "82594")
    private val newDigits = listOf("92014", "33871", "56620", "10473", "78195", "04418", "62907", "35582", "81146", "27730", "94061", "50329")
    private val contact = ContactUiState(
        user = Fixtures.bob, loading = false,
        devices = listOf(
            DeviceSafetyUi("dv1", 1, "Pixel 7", digits, verified = true, pending = null),
            DeviceSafetyUi("dv2", 2, "Ny telefon", digits.reversed(), verified = false, pending = null),
            DeviceSafetyUi("dv3", 3, "Surfplatta", digits, verified = false, pending = PendingKeyUi(ByteArray(33), newDigits)),
        ),
    )
    @Test fun safetyLight() = shot("13_safety_number", false) { ContactContent(contact, {}, { _, _ -> }, { _, _ -> }) }
    @Test fun safetyDark() = shot("13_safety_number", true) { ContactContent(contact, {}, { _, _ -> }, { _, _ -> }) }

    private val contactChanged = ContactUiState(
        user = Fixtures.bob, loading = false,
        devices = listOf(DeviceSafetyUi("dv3", 3, "Surfplatta", digits, verified = false, pending = PendingKeyUi(ByteArray(33), newDigits))),
    )
    @Test fun safetyChangedLight() = shot("13b_safety_identity_changed", false) { ContactContent(contactChanged, {}, { _, _ -> }, { _, _ -> }) }
    @Test fun safetyChangedDark() = shot("13b_safety_identity_changed", true) { ContactContent(contactChanged, {}, { _, _ -> }, { _, _ -> }) }

    // --- QR codes, message info, storage and auto download --------------------------------------------------------------------

    private val qrText = "fcsn1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(66) { (it * 7 + 3).toByte() })
    private val contactQr = ContactUiState(
        user = Fixtures.bob, loading = false,
        devices = listOf(
            DeviceSafetyUi("dv1", 1, "Pixel 7", digits, verified = false, pending = null, qr = qrText),
            DeviceSafetyUi("dv3", 3, "Surfplatta", digits, verified = false, pending = PendingKeyUi(ByteArray(33), newDigits, qr = qrText)),
        ),
    )
    @Test fun safetyQrLight() {
        RuntimeEnvironment.setQualifiers("sv-w360dp-h1500dp-xhdpi")
        shot("13c_safety_qr", false) { ContactContent(contactQr, {}, { _, _ -> }, { _, _ -> }, initiallyShowQr = true) }
    }
    @Test fun safetyQrDark() {
        RuntimeEnvironment.setQualifiers("sv-w360dp-h1500dp-xhdpi")
        shot("13c_safety_qr", true) { ContactContent(contactQr, {}, { _, _ -> }, { _, _ -> }, initiallyShowQr = true) }
    }

    private val inviteCode = "K7QXr93MD_-abcdEFGH"
    private val inviteQr = se.familychat.app.qr.InviteQr.payload("https://hemma.tail1234.ts.net/api/v1/", inviteCode)
    @Test fun invitationCreatedLight() = shot("18_invitation_created", false) { InvitationCreatedPreview(inviteCode, inviteQr) }
    @Test fun invitationCreatedDark() = shot("18_invitation_created", true) { InvitationCreatedPreview(inviteCode, inviteQr) }

    private val scannedInvite = se.familychat.app.qr.InviteLink("https://hemma.tail1234.ts.net", "hemma.tail1234.ts.net", inviteCode, suspiciousHost = false)
    private val redeemScanned = LoginUiState(
        mode = AuthMode.REDEEM, serverUrl = "https://annat.example.org", invite = scannedInvite, code = inviteCode, deviceName = "Google Pixel 8",
    )
    @Test fun loginScannedInviteLight() = shot("6b_login_invitation_scanned", false) { LoginContent(redeemScanned, LoginCallbacks()) }
    @Test fun loginScannedInviteDark() = shot("6b_login_invitation_scanned", true) { LoginContent(redeemScanned, LoginCallbacks()) }
    @Test fun loginScanRejectedLight() = shot("6c_login_scan_rejected", false) {
        val rejected = redeem.copy(fieldErrors = emptySet(), error = null, scanRejection = se.familychat.app.qr.InviteRejection.INSECURE_SERVER)
        LoginContent(rejected, LoginCallbacks())
    }

    private val messageInfoGroup = MessageInfoState(
        loaded = true, isGroup = true, sentAt = Fixtures.at(10, 24, 14, 30), text = "Vi ses på söndag klockan fyra, ta med fika!",
        recipients = listOf(
            RecipientInfo(Fixtures.members[1], delivered = true, deliveredAt = Fixtures.at(10, 24, 14, 31), read = true, readAt = Fixtures.at(10, 24, 14, 40)),
            RecipientInfo(Fixtures.members[2], delivered = true, deliveredAt = Fixtures.at(10, 24, 14, 31), read = false, readAt = null),
            RecipientInfo(Fixtures.members[3], delivered = true, deliveredAt = null, read = true, readAt = null),
        ),
    )
    @Test fun messageInfoLight() = shot("19_message_info", false) { MessageInfoContent(messageInfoGroup, {}) }
    @Test fun messageInfoDark() = shot("19_message_info", true) { MessageInfoContent(messageInfoGroup, {}) }

    private val storage = StorageUiState(
        loading = false, mediaBytes = 412_000_000, otherBytes = 3_200_000, databaseBytes = 18_500_000, cacheBytes = 24_000_000,
        rows = listOf(
            StorageRowUi("g1", "Familjen Ek", AvatarUi("Familjen Ek"), 318_000_000, 142),
            StorageRowUi("c2", "Bertil Ek", Fixtures.bob.avatar, 82_000_000, 31),
            StorageRowUi("c3", "Emma Berg", Fixtures.emma.avatar, 12_000_000, 4),
        ),
    )
    @Test fun storageLight() = shot("20_storage", false) { StorageContent(storage, {}, {}, {}) }
    @Test fun storageDark() = shot("20_storage", true) { StorageContent(storage, {}, {}, {}) }

    @Test fun autoDownloadLight() {
        RuntimeEnvironment.setQualifiers("sv-w360dp-h1050dp-xhdpi")
        shot("21_auto_download", false) { AutoDownloadContent(se.familychat.app.data.AutoDownloadSettings(), {}, { _, _ -> }) }
    }
    @Test fun autoDownloadDark() {
        RuntimeEnvironment.setQualifiers("sv-w360dp-h1050dp-xhdpi")
        shot("21_auto_download", true) { AutoDownloadContent(se.familychat.app.data.AutoDownloadSettings(), {}, { _, _ -> }) }
    }

    @Test fun conversationsEnglish() {
        RuntimeEnvironment.setQualifiers("en-w360dp-h740dp-xhdpi")
        shot("14_conversations_en", false) { ConversationsContent(Fixtures.conversationItems, false, {}, {}, {}, {}) }
    }

    @Test fun conversationEnglishDark() {
        RuntimeEnvironment.setQualifiers("en-w360dp-h740dp-xhdpi")
        shot("15_conversation_group_en", true) { ConversationUnderTest(convState, Fixtures.playing) }
    }

}
