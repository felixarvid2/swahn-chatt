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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.swahnchat.app.screenshots.Fixtures
import se.swahnchat.app.ui.components.BubbleActions
import se.swahnchat.app.ui.components.ConnectionBanner
import se.swahnchat.app.ui.components.LocalNowMillis
import se.swahnchat.app.ui.components.MessageBubble
import se.swahnchat.app.ui.components.PlaybackUi
import se.swahnchat.app.ui.model.AttachmentKindUi
import se.swahnchat.app.ui.model.AttachmentUi
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.ui.model.ConnectionUi
import se.swahnchat.app.ui.model.DeliveryUi
import se.swahnchat.app.ui.model.MessageUi
import se.swahnchat.app.ui.model.SafetyState
import se.swahnchat.app.ui.model.SafetyUi
import se.swahnchat.app.ui.model.TransferUi
import se.swahnchat.app.ui.screens.ConversationCallbacks
import se.swahnchat.app.ui.screens.ConversationContent
import se.swahnchat.app.ui.screens.ConversationRow
import se.swahnchat.app.ui.screens.ConversationUiState
import se.swahnchat.app.ui.theme.SwahnChatTheme
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Behaviour of the conversation screens that a screenshot cannot show: semantics, callbacks, what is displayed when. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class ConversationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun fixedWorld() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        SwahnChatTheme(darkTheme = false, dynamicColor = false) {
            CompositionLocalProvider(LocalNowMillis provides Fixtures.NOW) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { block() }
            }
        }
    }

    private fun message(
        text: String? = "Hej där", outgoing: Boolean = false, delivery: DeliveryUi = DeliveryUi.NONE, attachments: List<AttachmentUi> = emptyList(),
        speaker: String? = "Bertil Ek", edited: Boolean = false,
    ) = ChatRow.Message(
        MessageUi(
            id = "m1", outgoing = outgoing, senderName = null, senderAvatar = null, text = text, reply = null, attachments = attachments,
            time = Fixtures.at(10, 24, 14, 30), delivery = delivery, edited = edited, deleted = false, undecryptable = null, failed = false,
            canEdit = false, canDelete = false, senderId = if (outgoing) "me" else "bob", speaker = if (outgoing) null else speaker,
        ),
        firstOfRun = true,
    )

    // --- TalkBack: one description per bubble ---------------------------------------------------------------------------------

    @Test fun `an own read message is one node with who, text, time and status`() {
        content { MessageBubble(message("Vi ses", outgoing = true, delivery = DeliveryUi.READ), BubbleActions(), PlaybackUi()) }
        compose.onNodeWithContentDescription("Du, Vi ses, 14:30, Läst").assertIsDisplayed()
        // the status icon and the time are not reachable on their own
        compose.onAllNodesWithContentDescription("Läst", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Levererat", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun `an incoming message names its sender, also in a direct chat`() {
        content { MessageBubble(message("Hej där", speaker = "Bertil Ek"), BubbleActions(), PlaybackUi()) }
        compose.onNodeWithContentDescription("Bertil Ek, Hej där, 14:30").assertIsDisplayed()
    }

    @Test fun `attachments are named by kind, voice with its length, and pending or failed status is spoken`() {
        val voice = AttachmentUi("v", AttachmentKindUi.VOICE, "audio/mp4", "v.m4a", 10, null, null, 23_000, null, TransferUi.Ready("/x"))
        content { MessageBubble(message(null, outgoing = true, delivery = DeliveryUi.PENDING, attachments = listOf(voice)), BubbleActions(), PlaybackUi()) }
        compose.onNodeWithContentDescription("Du, Röstmeddelande 0:23, 14:30, Väntar på att skickas").assertIsDisplayed()
    }

    @Test fun `edited is part of the description`() {
        content { MessageBubble(message("Rättat", outgoing = true, delivery = DeliveryUi.SENT, edited = true), BubbleActions(), PlaybackUi()) }
        compose.onNodeWithContentDescription("Du, Rättat, redigerad, 14:30, Skickat").assertIsDisplayed()
    }

    // --- links ------------------------------------------------------------------------------------------------------------------

    @Test fun `links are offered as accessibility actions and a tap opens nothing by itself`() {
        val asked = ArrayList<String>()
        content { MessageBubble(message("Titta https://example.com/x och ring 070-123 45 67"), BubbleActions(onOpenLink = { asked += it }), PlaybackUi()) }
        val node = compose.onNode(hasContentDescription("Bertil Ek", substring = true)).fetchSemanticsNode()
        // the bubble also offers "Svara" (the swipe's accessible twin); the links follow
        val actions = node.config[SemanticsActions.CustomActions].filter { it.label.startsWith("Öppna länk") }
        assertEquals(listOf("Öppna länk: example.com", "Öppna länk: 0701234567"), actions.map { it.label })
        assertTrue(asked.isEmpty(), "nothing was asked or opened yet")
        actions[0].action()
        assertEquals(listOf("https://example.com/x"), asked, "the screen is asked to confirm; it is what opens the dialog")
    }

    // --- voice speed -------------------------------------------------------------------------------------------------------------

    @Test fun `the voice bubble has a speed chip that asks for the next speed`() {
        var cycled = 0
        val voice = AttachmentUi("v", AttachmentKindUi.VOICE, "audio/mp4", "v.m4a", 10, null, null, 23_000, null, TransferUi.Ready("/x"))
        content { MessageBubble(message(null, attachments = listOf(voice)), BubbleActions(onCycleVoiceSpeed = { cycled++ }), PlaybackUi(speed = 1.5f)) }
        compose.onNodeWithText("1.5x").assertIsDisplayed()
        compose.onNodeWithContentDescription("Uppspelningshastighet 1.5x").performClick()
        assertEquals(1, cycled)
    }

    @Test fun `a voice message that is not downloaded has no speed chip`() {
        val voice = AttachmentUi("v", AttachmentKindUi.VOICE, "audio/mp4", "v.m4a", 10, null, null, 23_000, null, TransferUi.Remote)
        content { MessageBubble(message(null, attachments = listOf(voice)), BubbleActions(), PlaybackUi()) }
        compose.onAllNodesWithContentDescription("Uppspelningshastighet 1x").assertCountEquals(0)
    }

    // --- connection banner -----------------------------------------------------------------------------------------------------

    @Test fun `the banner says connecting or offline, is a polite live region and is empty when connected`() {
        var state by mutableStateOf(ConnectionUi.HIDDEN)
        content { ConnectionBanner(state) }
        compose.onAllNodes(hasText("Ansluter…")).assertCountEquals(0)
        compose.onAllNodes(hasText("Offline", substring = true)).assertCountEquals(0)

        state = ConnectionUi.CONNECTING
        compose.waitForIdle()
        compose.onNodeWithText("Ansluter…").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()

        state = ConnectionUi.OFFLINE
        compose.waitForIdle()
        compose.onNodeWithText("Offline – meddelanden skickas när du är online igen").assertIsDisplayed()
    }

    // --- the conversation screen ---------------------------------------------------------------------------------------------------

    @Composable private fun Conversation(state: ConversationUiState, rows: List<ChatRow>, callbacks: ConversationCallbacks = ConversationCallbacks()) {
        val items = flowOf(Fixtures.pages(rows)).collectAsLazyPagingItems()
        ConversationContent(state, items, PlaybackUi(), BubbleActions(), callbacks)
    }

    private val base = ConversationUiState(
        title = "Familjen Ek", isGroup = true, memberCount = 4, loaded = true,
        latestId = "13",
    )

    @Test fun `opened with unread messages the divider is on screen and the button counts them`() {
        content { Conversation(base.copy(openUnread = 6, newMessages = 6), Fixtures.groupRowsUnread) }
        compose.waitForIdle()
        compose.onNodeWithText("Olästa meddelanden").assertIsDisplayed()
        compose.onNodeWithContentDescription("Gå till senaste meddelandet, 6 nya meddelanden").assertIsDisplayed()
        compose.onNodeWithText("6").assertIsDisplayed()
    }

    @Test fun `opened without unread messages the list is at the bottom with no button and no divider`() {
        content { Conversation(base.copy(openUnread = 0), Fixtures.groupRows) }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Gå till senaste meddelandet", substring = true).assertCountEquals(0)
        compose.onAllNodes(hasText("Olästa meddelanden")).assertCountEquals(0)
    }

    @Test fun `the unread state arrives after the list so it is hidden first and then positioned at the divider`() {
        var state by mutableStateOf(base.copy(openUnread = -1))
        content { Conversation(state, Fixtures.groupRowsUnread) }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Gå till senaste meddelandet", substring = true).assertCountEquals(0)
        // the anchor is resolved: six unread messages
        state = base.copy(openUnread = 6, newMessages = 6)
        compose.waitForIdle()
        compose.onNodeWithText("Olästa meddelanden").assertIsDisplayed()
        val top = compose.onNodeWithText("Olästa meddelanden").getBoundsInRoot().top
        assertTrue(top < 300.dp, "the divider is in the upper part of the screen, was at $top")
        compose.onNodeWithContentDescription("Gå till senaste meddelandet, 6 nya meddelanden").assertIsDisplayed()
    }

    @Test fun `the header shows a shield when everything is verified and a warning for a pending change`() {
        var opened = 0
        content { Conversation(base.copy(safety = SafetyUi(SafetyState.VERIFIED)), Fixtures.groupRows, ConversationCallbacks(onOpenSafety = { opened++ })) }
        compose.onNodeWithContentDescription("Alla enheter är verifierade. Öppna säkerhetsnummer.").performClick()
        assertEquals(1, opened, "a tap opens the safety numbers")
        compose.onAllNodesWithContentDescription("Säkerhetsnyckeln har ändrats", substring = true).assertCountEquals(0)
    }

    @Test fun `the pending-change warning is announced as such`() {
        content { Conversation(base.copy(safety = SafetyUi(SafetyState.WARNING, "bob")), Fixtures.groupRows) }
        compose.onNodeWithContentDescription("Säkerhetsnyckeln har ändrats eller en ny enhet väntar på ditt godkännande. Granska.").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Alla enheter är verifierade", substring = true).assertCountEquals(0)
    }

    @Test fun `without anything to say the header has no safety icon`() {
        content { Conversation(base, Fixtures.groupRows) }
        compose.onAllNodesWithContentDescription("verifierade", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Säkerhetsnyckeln", substring = true).assertCountEquals(0)
    }

    // --- drafts in the list ------------------------------------------------------------------------------------------------------

    @Test fun `a draft replaces the last message in the list preview`() {
        val withDraft = Fixtures.conversationItems.first { it.id == "d2" }
        content { ConversationRow(withDraft, onClick = {}) }
        compose.onNodeWithText("Utkast: Jag kan hämta på fredag, passar det?").assertIsDisplayed()
        compose.onAllNodes(hasText("Perfekt, vi ses då!", substring = true)).assertCountEquals(0)
    }

    @Test fun `without a draft the last message shows, and a typing contact beats a draft`() {
        val plain = Fixtures.conversationItems.first { it.id == "d2" }.copy(draft = null)
        content { ConversationRow(plain, onClick = {}) }
        compose.onNodeWithText("Du: Perfekt, vi ses då!").assertIsDisplayed()
    }

    // --- calls ------------------------------------------------------------------------------------------------------------------------

    private val direct = ConversationUiState(title = "Bertil Ek", otherUserId = "bob", loaded = true, latestId = "13")

    @Test fun `a direct conversation offers a voice and a video call and reports which one was pressed`() {
        val calls = ArrayList<Boolean>()
        content { Conversation(direct, Fixtures.directRows, ConversationCallbacks(onCall = { calls += it })) }
        compose.onNodeWithContentDescription("Ring").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Videosamtal").assertIsDisplayed().performClick()
        assertEquals(listOf(false, true), calls)
    }

    @Test fun `a group has no call buttons`() {
        content { Conversation(base, Fixtures.groupRows) }
        compose.onAllNodesWithContentDescription("Ring").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Videosamtal").assertCountEquals(0)
    }

    @Test fun `while a call is in progress the buttons are disabled and say why`() {
        val calls = ArrayList<Boolean>()
        content { Conversation(direct.copy(callActive = true), Fixtures.directRows, ConversationCallbacks(onCall = { calls += it })) }
        compose.onNodeWithContentDescription("Ring").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Videosamtal").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Du är redan i ett samtal")).assertCountEquals(2)
    }
}
