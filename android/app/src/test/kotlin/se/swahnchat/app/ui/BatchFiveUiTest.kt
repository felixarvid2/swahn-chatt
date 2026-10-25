@file:Suppress("MaxLineLength") // fixtures and one-line assertions

package se.swahnchat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
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
import se.swahnchat.app.ui.components.LocalNowMillis
import se.swahnchat.app.ui.components.MentionPicker
import se.swahnchat.app.ui.components.MessageBubble
import se.swahnchat.app.ui.components.PlaybackUi
import se.swahnchat.app.ui.components.PollCreateForm
import se.swahnchat.app.ui.components.PollDraft
import se.swahnchat.app.ui.components.ScheduleDialog
import se.swahnchat.app.ui.components.ScheduleProblem
import se.swahnchat.app.ui.components.mentionQuery
import se.swahnchat.app.ui.components.mentionSuggestions
import se.swahnchat.app.ui.components.mentionTokenStart
import se.swahnchat.app.ui.components.nextPollChoice
import se.swahnchat.app.ui.components.scheduleProblem
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.ui.model.DeliveryUi
import se.swahnchat.app.ui.model.MemberUi
import se.swahnchat.app.ui.model.MentionUi
import se.swahnchat.app.ui.model.MessageUi
import se.swahnchat.app.ui.model.PollOptionUi
import se.swahnchat.app.ui.model.PollUi
import se.swahnchat.app.ui.model.ReactorUi
import se.swahnchat.app.ui.screens.ConversationCallbacks
import se.swahnchat.app.ui.screens.ConversationContent
import se.swahnchat.app.ui.screens.ConversationUiState
import se.swahnchat.app.ui.screens.ScheduledContent
import se.swahnchat.app.ui.screens.ScheduledRowUi
import se.swahnchat.app.ui.screens.ScheduledState
import se.swahnchat.app.ui.screens.StarredContent
import se.swahnchat.app.ui.screens.StarredRowUi
import se.swahnchat.app.ui.screens.StarredState
import se.swahnchat.app.ui.theme.SwahnChatTheme
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mention picker, the poll bubble, stars and scheduled messages as a user meets them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class BatchFiveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun fixedWorld() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        SwahnChatTheme(darkTheme = false, dynamicColor = false) {
            CompositionLocalProvider(LocalNowMillis provides Fixtures.NOW) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { block() }
            }
        }
    }

    // the bubble itself (the options of a poll are nodes of their own and have a description too)
    private val bubble = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions)
    private fun openMenu() = compose.onNode(bubble).performTouchInput { longClick() }

    private fun member(id: String, name: String, username: String = name.lowercase(), me: Boolean = false) =
        MemberUi(id, name, username, AvatarUi(name), isAdmin = false, isMe = me)

    private val members = listOf(
        member("me", "Anna Lindqvist", me = true), member("bob", "Bertil Ek"), member("cia", "Cecilia Ek"), member("emma", "Emma Berg"),
    )

    // --- the mention picker ------------------------------------------------------------------------------------------------------

    @Test fun `typing an at-sign at the end of the text asks for a person, and only there`() {
        assertEquals("", mentionQuery("hej @"))
        assertEquals("be", mentionQuery("hej @be"))
        assertEquals("", mentionQuery("@"))
        assertNull(mentionQuery("hej"))
        assertNull(mentionQuery("mejla anna@exempel"), "an at-sign inside a word is an address")
        assertNull(mentionQuery("hej @Bertil och"), "the name was finished")
        assertNull(mentionQuery("hej @Bertil "))
        assertEquals(4, mentionTokenStart("hej @be"))
        assertEquals(0, mentionTokenStart("@"))
        assertEquals(-1, mentionTokenStart("hej"))
    }

    @Test fun `suggestions are the other members whose name starts a word with what was typed`() {
        assertEquals(listOf("Bertil Ek", "Cecilia Ek", "Emma Berg"), mentionSuggestions(members, "").map { it.name }, "never yourself")
        assertEquals(listOf("Bertil Ek", "Emma Berg"), mentionSuggestions(members, "be").map { it.name }, "'Berg' starts with it too")
        assertEquals(listOf("Bertil Ek", "Cecilia Ek"), mentionSuggestions(members, "ek").map { it.name }.take(2), "a later word of the name matches too")
        assertEquals(listOf("Emma Berg", "Bertil Ek"), mentionSuggestions(members, "e").map { it.name }.take(2), "names starting with it come first")
        assertEquals(listOf("Cecilia Ek"), mentionSuggestions(members, "CEC").map { it.name })
        assertTrue(mentionSuggestions(members, "zz").isEmpty())
    }

    @Test fun `the picker lists the people and picking one reports who`() {
        val picked = ArrayList<String>()
        content { MentionPicker(mentionSuggestions(members, ""), onPick = { picked += it }) }
        compose.onNodeWithText("Bertil Ek").assertIsDisplayed()
        compose.onNodeWithText("Cecilia Ek").assertIsDisplayed()
        compose.onAllNodesWithText("Anna Lindqvist").assertCountEquals(0)
        compose.onNodeWithText("Cecilia Ek").performClick()
        assertEquals(listOf("cia"), picked)
    }

    @Test fun `without suggestions there is no picker`() {
        content { MentionPicker(emptyList(), onPick = {}) }
        compose.onNodeWithContentDescription("Nämn någon i gruppen").assertDoesNotExist()
    }

    // --- formatting and mentions inside a bubble ---------------------------------------------------------------------------------------

    private fun message(
        id: String = "m1", text: String? = "Hej där", outgoing: Boolean = false, poll: PollUi? = null, starred: Boolean = false,
        scheduledAt: Long? = null, mentions: List<MentionUi> = emptyList(), canDelete: Boolean = outgoing, canEdit: Boolean = false,
    ) = ChatRow.Message(
        MessageUi(
            id = id, outgoing = outgoing, senderName = null, senderAvatar = null, text = text, reply = null, attachments = emptyList(),
            time = Fixtures.at(10, 24, 14, 30), delivery = if (scheduledAt != null) DeliveryUi.PENDING else if (outgoing) DeliveryUi.READ else DeliveryUi.NONE,
            edited = false, deleted = false, undecryptable = null, failed = false, canEdit = canEdit, canDelete = canDelete,
            canDeleteForMe = scheduledAt == null, canShowInfo = false, senderId = if (outgoing) "me" else "bob", speaker = if (outgoing) null else "Bertil Ek",
            mentions = mentions, poll = poll, starred = starred, canStar = true, scheduledAt = scheduledAt, canReact = scheduledAt == null,
        ),
        firstOfRun = true,
    )

    @Test fun `the bubble says the text without its formatting markers`() {
        content { MessageBubble(message(text = "Det är *viktigt* och _fint_ och ~fel~ med `kod`"), BubbleActions(), PlaybackUi()) }
        compose.onNode(hasContentDescription("Det är viktigt och fint och fel med kod", substring = true)).assertIsDisplayed()
    }

    @Test fun `a bubble with a mention reads the name with its at-sign`() {
        content {
            MessageBubble(
                message(text = "Hej @Bertil Ek, kommer du?", mentions = listOf(MentionUi("bob", "Bertil Ek", false))),
                BubbleActions(), PlaybackUi(),
            )
        }
        compose.onNode(hasContentDescription("Hej @Bertil Ek, kommer du?", substring = true)).assertIsDisplayed()
    }

    // --- poll bubble ------------------------------------------------------------------------------------------------------------------

    private fun voter(id: String, name: String, me: Boolean = false) = ReactorUi(id, name, AvatarUi(name), me)

    private fun pollUi(
        mine: Int? = null, closed: Boolean = false, canClose: Boolean = false, multiple: Boolean = false, canVote: Boolean = !closed,
    ): PollUi {
        val votes = mapOf(0 to listOf(voter("bob", "Bertil Ek"), voter("cia", "Cecilia Ek")), 1 to listOf(voter("emma", "Emma Berg")), 2 to emptyList())
        val options = listOf("Pizza", "Pasta", "Sallad").mapIndexed { i, t ->
            val vs = votes.getValue(i) + if (mine == i) listOf(voter("me", "Anna Lindqvist", me = true)) else emptyList()
            PollOptionUi(i, t, vs, mine == i)
        }
        return PollUi(options, multiple, closed, voterCount = options.sumOf { it.count }, canVote = canVote, canClose = canClose)
    }

    @Test fun `a poll shows the question, every option with its count and the number of voters`() {
        content { MessageBubble(message(text = "Vad äter vi?", poll = pollUi()), BubbleActions(), PlaybackUi()) }
        compose.onNodeWithText("Vad äter vi?").assertIsDisplayed()
        for (option in listOf("Pizza", "Pasta", "Sallad")) compose.onNodeWithText(option).assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("3 har röstat").assertIsDisplayed()
        compose.onNodeWithText("Välj ett alternativ").assertIsDisplayed()
    }

    @Test fun `tapping an option votes, tapping your own choice withdraws it`() {
        val votes = ArrayList<Pair<String, List<Int>>>()
        content {
            MessageBubble(message(text = "Vad äter vi?", poll = pollUi(mine = 1)), BubbleActions(onVote = { id, ids -> votes += id to ids }), PlaybackUi())
        }
        compose.onNodeWithText("Pizza").performClick()
        compose.onNodeWithText("Pasta").performClick()
        assertEquals(listOf("m1" to listOf(0), "m1" to emptyList()), votes)
    }

    @Test fun `in a poll with several choices a tap adds or removes one option`() {
        val poll = pollUi(mine = 1, multiple = true)
        assertEquals(listOf(0, 1), nextPollChoice(poll, 0))
        assertEquals(emptyList(), nextPollChoice(poll, 1))
        assertEquals(listOf(1, 2), nextPollChoice(poll, 2))
        val single = pollUi(mine = 1)
        assertEquals(listOf(2), nextPollChoice(single, 2))
        assertEquals(emptyList(), nextPollChoice(single, 1))
        assertEquals(listOf(0), nextPollChoice(pollUi(), 0))
    }

    @Test fun `a closed poll shows its result and cannot be voted on`() {
        val votes = ArrayList<List<Int>>()
        content {
            MessageBubble(message(text = "Vad åt vi?", poll = pollUi(closed = true)), BubbleActions(onVote = { _, ids -> votes += ids }), PlaybackUi())
        }
        compose.onNodeWithText("Avslutad").assertIsDisplayed()
        compose.onNodeWithText("Pizza").assertIsNotEnabled()
        compose.onNodeWithText("Pizza").performClick()
        assertTrue(votes.isEmpty())
    }

    @Test fun `tapping a count shows who voted for the option`() {
        content { MessageBubble(message(text = "Vad äter vi?", poll = pollUi()), BubbleActions(), PlaybackUi()) }
        compose.onNodeWithText("2").performClick()
        compose.onNodeWithText("Bertil Ek").assertIsDisplayed()
        compose.onNodeWithText("Cecilia Ek").assertIsDisplayed()
        compose.onNodeWithText("Stäng").performClick()
        compose.onNodeWithText("0").performClick()
        compose.onNodeWithText("Ingen har röstat på det här alternativet.").assertIsDisplayed()
    }

    @Test fun `the creator can close an open poll from the menu, others cannot`() {
        val closed = ArrayList<String>()
        content { MessageBubble(message(text = "Vad äter vi?", outgoing = true, poll = pollUi(canClose = true)), BubbleActions(onClosePoll = { closed += it }), PlaybackUi()) }
        openMenu()
        compose.onNodeWithText("Avsluta omröstningen").performClick()
        assertEquals(listOf("m1"), closed)
    }

    @Test fun `someone else's poll has no close entry and a poll cannot be edited or forwarded from the menu`() {
        content { MessageBubble(message(text = "Vad äter vi?", poll = pollUi(canClose = false)), BubbleActions(), PlaybackUi()) }
        openMenu()
        compose.onAllNodesWithText("Avsluta omröstningen").assertCountEquals(0)
        compose.onAllNodesWithText("Redigera").assertCountEquals(0)
    }

    // --- stars ------------------------------------------------------------------------------------------------------------------------

    @Test fun `the menu stars a message and a starred one offers to remove the star`() {
        val stars = ArrayList<Pair<String, Boolean>>()
        content { MessageBubble(message(), BubbleActions(onStar = { id, on -> stars += id to on }), PlaybackUi()) }
        openMenu()
        compose.onNodeWithText("Stjärnmärk").performClick()
        assertEquals(listOf("m1" to true), stars)
    }

    @Test fun `a starred message says so and the menu takes the star away`() {
        val stars = ArrayList<Pair<String, Boolean>>()
        content { MessageBubble(message(starred = true), BubbleActions(onStar = { id, on -> stars += id to on }), PlaybackUi()) }
        compose.onNode(hasContentDescription("Stjärnmärkt", substring = true)).assertIsDisplayed()
        openMenu()
        compose.onNodeWithText("Ta bort stjärna").performClick()
        assertEquals(listOf("m1" to false), stars)
    }

    private fun starredRow(id: String, text: String?, chat: String = "Familjen Ek", who: String? = "Cecilia Ek") =
        StarredRowUi(id, "c-$id", chat, AvatarUi(chat), who, Fixtures.at(10, 23, 18, 0), text, null)

    @Test fun `the list of starred messages shows each message with its chat, and a tap opens it there`() {
        val opened = ArrayList<Pair<String, String>>()
        content {
            StarredContent(
                StarredState(true, listOf(starredRow("a", "Vi grillar på lördag"), starredRow("b", "Husnyckeln ligger under krukan", chat = "Bertil Ek", who = null))),
                showChat = true, onBack = {}, onOpen = { c, m -> opened += c to m },
            )
        }
        compose.onNodeWithText("Cecilia Ek · Familjen Ek").assertIsDisplayed()
        compose.onNodeWithText("Du · Bertil Ek").assertIsDisplayed()
        compose.onNodeWithText("Vi grillar på lördag").performClick()
        compose.onNodeWithText("Husnyckeln ligger under krukan").performClick()
        assertEquals(listOf("c-a" to "a", "c-b" to "b"), opened)
    }

    @Test fun `inside one conversation the rows do not repeat the chat and an empty list explains stars`() {
        content { StarredContent(StarredState(true, listOf(starredRow("a", "Hej"))), showChat = false, onBack = {}, onOpen = { _, _ -> }) }
        compose.onNodeWithText("Cecilia Ek").assertIsDisplayed()
        compose.onAllNodesWithText("Cecilia Ek · Familjen Ek").assertCountEquals(0)
    }

    @Test fun `no starred messages shows how to star one`() {
        content { StarredContent(StarredState(true, emptyList()), showChat = true, onBack = {}, onOpen = { _, _ -> }) }
        compose.onNodeWithText("Inga stjärnmärkta meddelanden").assertIsDisplayed()
        compose.onNodeWithText("Håll in ett meddelande och välj Stjärnmärk. Stjärnorna finns bara på den här telefonen och skickas aldrig.").assertIsDisplayed()
    }

    // --- scheduled messages --------------------------------------------------------------------------------------------------------

    @Test fun `a scheduled message is shown with its time and the menu offers to change, send now or delete`() {
        val asked = ArrayList<String>()
        content {
            MessageBubble(
                message(text = "Grattis på födelsedagen!", outgoing = true, scheduledAt = Fixtures.at(10, 25, 8, 0), canEdit = true),
                BubbleActions(onReschedule = { asked += "time:$it" }, onSendScheduledNow = { asked += "now:$it" }, onCancelScheduled = { asked += "delete:$it" }, onEdit = { asked += "edit:$it" }),
                PlaybackUi(),
            )
        }
        compose.onNode(hasContentDescription("schemalagt till", substring = true) and hasContentDescription("25 okt", substring = true)).assertIsDisplayed()
        for (label in listOf("Ändra tid", "Skicka nu", "Ta bort", "Redigera")) {
            openMenu()
            compose.onNodeWithText(label).performClick()
        }
        assertEquals(listOf("time:m1", "now:m1", "delete:m1", "edit:m1"), asked)
        openMenu()
        compose.onAllNodesWithText("Stjärnmärk").assertCountEquals(0)
        compose.onAllNodesWithText("Radera för alla").assertCountEquals(0)
    }

    @Test fun `the list of scheduled messages changes the time, sends now and deletes after asking`() {
        val calls = ArrayList<String>()
        content {
            ScheduledContent(
                ScheduledState(true, listOf(ScheduledRowUi("s1", "Grattis!", 0, Fixtures.at(10, 25, 8, 0)), ScheduledRowUi("s2", null, 2, Fixtures.at(10, 26, 9, 30)))),
                onBack = {}, onReschedule = { id, at -> calls += "time:$id:$at" }, onSendNow = { calls += "now:$it" }, onDelete = { calls += "delete:$it" },
            )
        }
        compose.onNode(hasText("Skickas", substring = true) and hasText("08:00", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Skickas", substring = true) and hasText("09:30", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Grattis!").assertIsDisplayed()
        compose.onNodeWithText("Bilaga").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Skicka nu"))[0].performClick()
        compose.onAllNodes(hasContentDescription("Ta bort"))[1].performClick()
        compose.onNodeWithText("Ta bort det schemalagda meddelandet?").assertIsDisplayed()
        assertEquals(listOf("now:s1"), calls, "nothing is deleted before the question is answered")
        compose.onAllNodes(hasText("Ta bort") and androidx.compose.ui.test.hasClickAction()).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        assertEquals(listOf("now:s1", "delete:s2"), calls)
    }

    @Test fun `no scheduled messages explains how to schedule one`() {
        content { ScheduledContent(ScheduledState(true, emptyList()), onBack = {}, onReschedule = { _, _ -> }, onSendNow = {}, onDelete = {}) }
        compose.onNodeWithText("Inga schemalagda meddelanden").assertIsDisplayed()
        compose.onNodeWithText("Håll in skicka-knappen för att välja när ett meddelande ska skickas.").assertIsDisplayed()
    }

    @Test fun `a schedule must lie between a minute and thirty days ahead`() {
        val now = 1_000_000_000L
        assertEquals(ScheduleProblem.TOO_EARLY, scheduleProblem(now + 59_999, now))
        assertEquals(ScheduleProblem.TOO_EARLY, scheduleProblem(now - 1, now))
        assertNull(scheduleProblem(now + 60_000, now))
        assertNull(scheduleProblem(now + 30L * 24 * 3_600_000, now))
        assertEquals(ScheduleProblem.TOO_LATE, scheduleProblem(now + 30L * 24 * 3_600_000 + 1, now))
    }

    @Test fun `the schedule dialog refuses a time that is too soon and says the message may be late`() {
        val picked = ArrayList<Long>()
        val now = Fixtures.NOW
        content { ScheduleDialog(initial = now + 10_000, now = { now }, onConfirm = { picked += it }, onDismiss = {}) }
        compose.onNodeWithText("Välj en tid minst en minut fram.").assertIsDisplayed()
        compose.onNodeWithText("Schemalägg", substring = false).assertExists()
        compose.onNode(hasText("Schemalägg") and androidx.compose.ui.test.hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("kan bli några minuter sent", substring = true)).assertIsDisplayed()
    }

    @Test fun `the schedule dialog accepts a good time and reports it`() {
        val picked = ArrayList<Long>()
        val now = Fixtures.NOW
        val at = now + 3 * 3_600_000 // 19:30 the same day
        content { ScheduleDialog(initial = at, now = { now }, onConfirm = { picked += it }, onDismiss = {}) }
        compose.onNode(hasText("Schemalägg") and androidx.compose.ui.test.hasClickAction()).assertIsEnabled().performClick()
        assertEquals(listOf(at), picked)
    }

    // --- the composer of a conversation -----------------------------------------------------------------------------------------------

    private val groupState = ConversationUiState(title = "Familjen Ek", isGroup = true, memberCount = 4, avatar = AvatarUi("Familjen Ek"), loaded = true)

    @Composable private fun Conversation(state: ConversationUiState, callbacks: ConversationCallbacks = ConversationCallbacks()) {
        val items = flowOf(Fixtures.pages(listOf(message("Hej")))).collectAsLazyPagingItems()
        ConversationContent(state, items, Fixtures.idle, BubbleActions(), callbacks)
    }

    @Test fun `the mention picker appears above the composer in a group and picking reports the member`() {
        val picked = ArrayList<String>()
        content {
            Conversation(
                groupState.copy(draft = "hej @e", mentionSuggestions = mentionSuggestions(members, "e")),
                ConversationCallbacks(onPickMention = { picked += it }),
            )
        }
        compose.onNodeWithText("Emma Berg").assertIsDisplayed()
        compose.onNodeWithText("Emma Berg").performClick()
        assertEquals(listOf("emma"), picked)
    }

    @Test fun `no suggestions, no picker`() {
        content { Conversation(groupState.copy(draft = "hej")) }
        compose.onAllNodesWithText("Emma Berg").assertCountEquals(0)
    }

    @Test fun `the attach menu offers a poll`() {
        content { Conversation(groupState) }
        compose.onNodeWithContentDescription("Bifoga").performClick()
        compose.onNodeWithText("Omröstning").assertIsDisplayed()
        compose.onNodeWithText("Foto eller video").assertIsDisplayed()
    }

    // (The form is tested outside a dialog: text fields in a dialog window never become idle under Robolectric.)
    @Test fun `the poll form needs a question and two options, takes up to ten, and creates what was typed`() {
        val draft = PollDraft()
        content { PollCreateForm(draft) }
        assertTrue(!draft.canCreate)
        compose.onNodeWithText("Fråga").performTextInput("Pizza ikväll?")
        compose.onNodeWithText("Alternativ 1").performTextInput("Ja")
        assertTrue(!draft.canCreate, "one option is not a poll")
        compose.onNodeWithText("Alternativ 2").performTextInput("Nej")
        assertTrue(draft.canCreate)
        compose.onNodeWithText("Lägg till alternativ").performClick()
        compose.onNodeWithText("Alternativ 3").performTextInput("Kanske")
        compose.onNodeWithText("Tillåt flera val").performClick()
        assertEquals("Pizza ikväll?", draft.question)
        assertEquals(listOf("Ja", "Nej", "Kanske"), draft.filled)
        assertTrue(draft.multiple)
        // blank options are not sent
        compose.onNodeWithText("Lägg till alternativ").performClick()
        assertEquals(3, draft.filled.size)
        compose.onNodeWithContentDescription("Ta bort alternativ 4").performClick()
        assertEquals(3, draft.options.size)
    }

    @Test fun `the poll form stops at ten options and keeps at least two`() {
        val draft = PollDraft()
        content { PollCreateForm(draft) }
        compose.onAllNodesWithContentDescription("Ta bort alternativ 1").assertCountEquals(0)
        repeat(8) { compose.onNodeWithText("Lägg till alternativ").performScrollTo().performClick() }
        compose.onNodeWithText("Alternativ 10").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Lägg till alternativ").assertCountEquals(0)
        assertEquals(10, draft.options.size)
    }

    @Test fun `the question and the options are cut at their limits`() {
        val draft = PollDraft()
        content { PollCreateForm(draft) }
        compose.onNodeWithText("Fråga").performTextInput("q".repeat(250))
        compose.onNodeWithText("Alternativ 1").performTextInput("o".repeat(150))
        assertEquals(200, draft.question.length)
        assertEquals(100, draft.options[0].length)
    }

    @Test fun `a long press on send offers to schedule the message, a short press sends`() {
        val sent = ArrayList<String>()
        content { Conversation(groupState.copy(draft = "Grattis!"), ConversationCallbacks(onSend = { sent += "send" })) }
        compose.onNodeWithContentDescription("Skicka").performClick()
        assertEquals(listOf("send"), sent)
        compose.onNodeWithContentDescription("Skicka").performTouchInput { longClick() }
        compose.onNodeWithText("Schemalägg meddelande").assertIsDisplayed()
        compose.onNodeWithText("Meddelandet skickas när telefonen är online, det kan bli några minuter sent. Det krypteras först när det skickas.").assertIsDisplayed()
        assertEquals(listOf("send"), sent, "nothing was sent by the long press")
    }

    @Test fun `the conversation menu leads to the starred and the scheduled messages`() {
        val opened = ArrayList<String>()
        content {
            Conversation(
                groupState.copy(scheduledCount = 2),
                ConversationCallbacks(onOpenStarred = { opened += "starred" }, onOpenScheduled = { opened += "scheduled" }),
            )
        }
        compose.onNodeWithContentDescription("Fler alternativ").performClick()
        compose.onNodeWithText("Stjärnmärkta").performClick()
        compose.onNodeWithContentDescription("Fler alternativ").performClick()
        compose.onNodeWithText("Schemalagda (2)").performClick()
        assertEquals(listOf("starred", "scheduled"), opened)
    }
}
