@file:Suppress("MaxLineLength") // fixtures and one-line assertions

package se.swahnchat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.swahnchat.app.data.Accent
import se.swahnchat.app.data.AppLanguage
import se.swahnchat.app.data.ChatBackground
import se.swahnchat.app.data.QuietHoursSettings
import se.swahnchat.app.screenshots.Fixtures
import se.swahnchat.app.ui.components.BubbleActions
import se.swahnchat.app.ui.components.LocalNowMillis
import se.swahnchat.app.ui.components.MessageBubble
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.ui.model.ConversationItemUi
import se.swahnchat.app.ui.model.DeliveryUi
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.model.MessageUi
import se.swahnchat.app.ui.model.PreviewKind
import se.swahnchat.app.ui.nav.PaneLayout
import se.swahnchat.app.ui.screens.ConversationCallbacks
import se.swahnchat.app.ui.screens.ConversationContent
import se.swahnchat.app.ui.screens.ConversationUiState
import se.swahnchat.app.ui.screens.ConversationsContent
import se.swahnchat.app.ui.screens.SettingsCallbacks
import se.swahnchat.app.ui.screens.SettingsContent
import se.swahnchat.app.ui.screens.SettingsUiState
import se.swahnchat.app.ui.theme.SwahnChatTheme
import se.swahnchat.core.client.Presence
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The settings rows, dialogs and screens of batch 7 (Swedish, as the app opens by default). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h2400dp-xhdpi")
class BatchSevenSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        SwahnChatTheme(darkTheme = false, dynamicColor = false) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { block() } }
    }

    @Test fun `read receipts and typing are on by default, say what they do and report the change`() {
        val receipts = ArrayList<Boolean>()
        val typing = ArrayList<Boolean>()
        content { SettingsContent(SettingsUiState(), SettingsCallbacks(onSendReadReceipts = { receipts += it }, onShowTyping = { typing += it })) }
        compose.onNodeWithText("Du ser fortfarande andras läskvitton", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Du ser fortfarande när andra skriver", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Skicka läskvitton").performScrollTo().performClick()
        compose.onNodeWithText("Visa när jag skriver").performScrollTo().performClick()
        assertEquals(listOf(false), receipts)
        assertEquals(listOf(false), typing)
    }

    @Test fun `online status is off until the server has answered, then follows the server and reports the change`() {
        val changes = ArrayList<Boolean>()
        var state by androidx.compose.runtime.mutableStateOf(SettingsUiState(sharePresence = null))
        content { SettingsContent(state, SettingsCallbacks(onSharePresence = { changes += it })) }
        compose.onNodeWithText("Visa när jag är online").performScrollTo().assertIsNotEnabled()
        state = SettingsUiState(sharePresence = false)
        compose.waitForIdle()
        compose.onNodeWithText("Visa när jag är online").performScrollTo().performClick()
        assertEquals(listOf(true), changes)
        compose.onNodeWithText("Du ser andras status om de har slagit på den", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `the quiet hours row shows the window and the dialog edits it`() {
        val seen = ArrayList<QuietHoursSettings>()
        var state by androidx.compose.runtime.mutableStateOf(SettingsUiState(quietHours = QuietHoursSettings(enabled = false)))
        content { SettingsContent(state, SettingsCallbacks(onQuietHours = { seen += it; state = state.copy(quietHours = it) })) }
        compose.onNodeWithText("Tysta timmar").performScrollTo().performClick()
        compose.onNodeWithText("Använd tysta timmar").performClick()
        assertEquals(listOf(true), seen.map { it.enabled })
        compose.onNodeWithText("Från").assertIsDisplayed()
        compose.onNodeWithText("22:00").assertIsDisplayed()
        compose.onNodeWithText("07:00").assertIsDisplayed()
        compose.onNodeWithText("Tysta även samtal").performClick()
        assertTrue(seen.last().muteCalls)
        compose.onNodeWithText("mån", substring = true, ignoreCase = true).performClick() // Monday off
        assertEquals(6, seen.last().days.size)
    }

    @Test fun `accent colour, chat background and language are chosen from dialogs`() {
        val accents = ArrayList<Accent>()
        val backgrounds = ArrayList<ChatBackground>()
        val languages = ArrayList<AppLanguage>()
        content {
            SettingsContent(
                SettingsUiState(),
                SettingsCallbacks(onAccent = { accents += it }, onChatBackground = { backgrounds += it }, onLanguage = { languages += it }),
            )
        }
        compose.onNodeWithText("Färg").performScrollTo().performClick()
        compose.onNodeWithText("Lila").performClick()
        assertEquals(listOf(Accent.PURPLE), accents)
        compose.onNodeWithText("Chattbakgrund").performScrollTo().performClick()
        compose.onNodeWithText("Prickar").performClick()
        assertEquals(listOf(ChatBackground.DOTS), backgrounds)
        compose.onNodeWithText("Språk").performScrollTo().performClick()
        compose.onNodeWithText("English").performClick()
        assertEquals(listOf(AppLanguage.ENGLISH), languages)
    }

    @Test fun `high contrast is a switch in the appearance section`() {
        val changes = ArrayList<Boolean>()
        content { SettingsContent(SettingsUiState(), SettingsCallbacks(onHighContrast = { changes += it })) }
        compose.onNodeWithText("Hög kontrast").performScrollTo().performClick()
        assertEquals(listOf(true), changes)
    }

    @Test fun `the section titles are headings for TalkBack`() {
        content { SettingsContent(SettingsUiState(), SettingsCallbacks()) }
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        for (title in listOf("Konto", "Notiser", "Integritet och säkerhet", "Utseende")) {
            compose.onNode(hasText(title).and(heading), useUnmergedTree = false).performScrollTo().assertIsDisplayed()
        }
    }
}

/** The chat header, the bubble announcement and the two panes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w1024dp-h768dp-xhdpi")
class BatchSevenChatUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        SwahnChatTheme(darkTheme = false, dynamicColor = false) {
            CompositionLocalProvider(LocalNowMillis provides Fixtures.NOW) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { block() }
            }
        }
    }

    private val rows = listOf(
        ChatRow.DateHeader(Fixtures.at(10, 24, 0)),
        ChatRow.Message(message("1", false, "Var är ni?", Fixtures.at(10, 24, 15, 58)), firstOfRun = true),
        ChatRow.Message(message("2", true, "Vid parkeringen", Fixtures.at(10, 24, 16, 2)), firstOfRun = true),
    )

    private fun message(id: String, outgoing: Boolean, text: String, time: Long) = MessageUi(
        id = id, outgoing = outgoing, senderName = if (outgoing) null else "Bertil Ek", senderAvatar = if (outgoing) null else Fixtures.bob.avatar,
        text = text, reply = null, attachments = emptyList(), time = time, delivery = if (outgoing) DeliveryUi.READ else DeliveryUi.NONE,
        edited = false, deleted = false, undecryptable = null, failed = false, canEdit = false, canDelete = outgoing,
        senderId = if (outgoing) "me" else "bob", speaker = if (outgoing) null else "Bertil Ek",
    )

    @Composable private fun Chat(state: ConversationUiState) {
        val items = flowOf(Fixtures.pages(rows)).collectAsLazyPagingItems()
        ConversationContent(state.copy(latestId = "2"), items, Fixtures.idle, BubbleActions(), ConversationCallbacks())
    }

    private val direct = ConversationUiState(title = "Bertil Ek", isGroup = false, otherUserId = "bob", avatar = Fixtures.bob.avatar, loaded = true)

    @Test fun `the header shows online for a person who shares and when they were last seen otherwise`() {
        content { Chat(direct.copy(presence = Presence(online = true, lastSeenAt = Fixtures.NOW))) }
        compose.onNodeWithText("online").assertIsDisplayed()
    }

    @Test fun `last seen is shown as a time`() {
        content { Chat(direct.copy(presence = Presence(online = false, lastSeenAt = Fixtures.at(10, 24, 14, 5)))) }
        compose.onNodeWithText("senast sedd 14:05").assertIsDisplayed()
    }

    @Test fun `nothing is shown for a person who does not share, or for a group, and typing wins`() {
        content { Chat(direct.copy(presence = null)) }
        compose.onAllNodesWithText("online").assertCountEquals(0)
        compose.onAllNodesWithText("senast sedd", substring = true).assertCountEquals(0)
    }

    @Test fun `typing replaces the online line`() {
        content { Chat(direct.copy(presence = Presence(true, Fixtures.NOW), typingNames = listOf("Bertil"))) }
        compose.onAllNodesWithText("online").assertCountEquals(0)
    }

    @Test fun `a bubble is one announcement - who, what, when and status`() {
        content {
            MessageBubble(rows[2] as ChatRow.Message, BubbleActions(), Fixtures.idle)
        }
        compose.onNode(hasContentDescription("Du, Vid parkeringen", substring = true)).assert(hasContentDescription("16:02", substring = true))
    }

    @Test fun `an incoming bubble names the sender first`() {
        content { MessageBubble(rows[1] as ChatRow.Message, BubbleActions(), Fixtures.idle) }
        compose.onNode(hasContentDescription("Bertil Ek, Var är ni?, 15:58")).assertIsDisplayed()
    }

    private fun item(id: String, title: String) = ConversationItemUi(
        id = id, title = title, isGroup = false, avatar = AvatarUi(title), previewKind = PreviewKind.TEXT, previewText = "Hej", previewSender = null,
        previewOwn = false, previewDelivery = DeliveryUi.NONE, lastActivity = Fixtures.at(10, 24, 15), unread = 0,
    )

    @Test fun `on a tablet the list and the open chat are side by side, and the open chat is marked in the list`() {
        content {
            PaneLayout(
                twoPane = true,
                list = {
                    ConversationsContent(
                        listOf(item("a", "Anna Berg"), item("b", "Bertil Ek")), refreshing = false, onRefresh = {}, onOpen = {}, onNew = {},
                        onSettings = {}, selectedId = "b",
                    )
                },
            ) { Chat(direct) }
        }
        compose.onNodeWithText("Anna Berg").assertIsDisplayed()
        compose.onNode(hasText("Bertil Ek", substring = true).and(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))).assertExists()
        compose.onNode(hasText("Anna Berg", substring = true).and(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))).assertDoesNotExist()
        compose.onNode(hasContentDescription("Var är ni?", substring = true)).assertIsDisplayed() // the chat on the right
    }

    @Test fun `on a phone only the detail is shown`() {
        content { PaneLayout(twoPane = false, list = { androidx.compose.material3.Text("LISTAN") }) { androidx.compose.material3.Text("DETALJ") } }
        compose.onAllNodesWithText("LISTAN").assertCountEquals(0)
        compose.onNodeWithText("DETALJ").assertIsDisplayed()
    }
}
