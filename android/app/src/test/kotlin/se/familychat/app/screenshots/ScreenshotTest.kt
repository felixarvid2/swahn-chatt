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
import se.familychat.app.ui.screens.ServerSettingsContent
import se.familychat.app.ui.screens.ServerSettingsUiState
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
        theme = ThemeMode.SYSTEM, versionName = "0.1.0",
    )
    @Test fun settingsLight() = shot("12_settings", false) { SettingsContent(settings, SettingsCallbacks()) }
    @Test fun settingsDark() = shot("12_settings", true) { SettingsContent(settings, SettingsCallbacks()) }

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

    @Test fun conversationsEnglish() {
        RuntimeEnvironment.setQualifiers("en-w360dp-h740dp-xhdpi")
        shot("14_conversations_en", false) { ConversationsContent(Fixtures.conversationItems, false, {}, {}, {}, {}) }
    }

    @Test fun conversationEnglishDark() {
        RuntimeEnvironment.setQualifiers("en-w360dp-h740dp-xhdpi")
        shot("15_conversation_group_en", true) { ConversationUnderTest(convState, Fixtures.playing) }
    }

}
