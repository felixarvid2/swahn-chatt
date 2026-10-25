package se.familychat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.familychat.app.screenshots.Fixtures
import se.familychat.app.ui.components.BubbleActions
import se.familychat.app.ui.components.LocalNowMillis
import se.familychat.app.ui.components.MessageBubble
import se.familychat.app.ui.components.PlaybackUi
import se.familychat.app.ui.model.ChatRow
import se.familychat.app.ui.model.DeliveryUi
import se.familychat.app.ui.model.MessageUi
import se.familychat.app.ui.theme.FamilyChatTheme
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Swipe-to-reply and the long-press menu (delete for me / for everyone, message info) of a bubble. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class MessageActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun fixedWorld() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        FamilyChatTheme(darkTheme = false, dynamicColor = false) {
            CompositionLocalProvider(LocalNowMillis provides Fixtures.NOW) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { block() }
            }
        }
    }

    private fun row(
        id: String = "m1", text: String = "Hej där", outgoing: Boolean = false, deleted: Boolean = false, canDelete: Boolean = outgoing && !deleted,
        canDeleteForMe: Boolean = true, canShowInfo: Boolean = outgoing && !deleted,
    ) = ChatRow.Message(
        MessageUi(
            id = id, outgoing = outgoing, senderName = null, senderAvatar = null, text = if (deleted) null else text, reply = null, attachments = emptyList(),
            time = Fixtures.at(10, 24, 14, 30), delivery = if (outgoing) DeliveryUi.READ else DeliveryUi.NONE, edited = false, deleted = deleted,
            undecryptable = null, failed = false, canEdit = false, canDelete = canDelete, canDeleteForMe = canDeleteForMe, canShowInfo = canShowInfo,
            senderId = if (outgoing) "me" else "bob", speaker = if (outgoing) null else "Bertil Ek",
        ),
        firstOfRun = true,
    )

    private val bubble = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

    private fun swipe(dx: Float, dy: Float = 0f) {
        compose.onNode(bubble).performTouchInput {
            val start = Offset(centerX - 40f, centerY)
            swipe(start, Offset(start.x + dx, start.y + dy), durationMillis = 300)
        }
        compose.waitForIdle()
    }

    // --- swipe to reply ------------------------------------------------------------------------------------------------------

    @Test fun `swiping right past the threshold replies once`() {
        val replies = ArrayList<String>()
        content { MessageBubble(row(), BubbleActions(onReply = { replies += it }), PlaybackUi()) }
        swipe(dx = 320f)
        assertEquals(listOf("m1"), replies)
    }

    @Test fun `an own message can be swiped too`() {
        val replies = ArrayList<String>()
        content { MessageBubble(row(outgoing = true), BubbleActions(onReply = { replies += it }), PlaybackUi()) }
        swipe(dx = 320f)
        assertEquals(listOf("m1"), replies)
    }

    @Test fun `a short swipe, a swipe to the left and a vertical swipe do not reply`() {
        val replies = ArrayList<String>()
        content { MessageBubble(row(), BubbleActions(onReply = { replies += it }), PlaybackUi()) }
        swipe(dx = 70f) // past the touch slop but short of the threshold
        swipe(dx = -320f)
        swipe(dx = 30f, dy = 300f) // a scroll that drifts a little to the side
        swipe(dx = 200f, dy = 300f) // more vertical than horizontal
        assertTrue(replies.isEmpty(), "replied: $replies")
    }

    @Test fun `a deleted message cannot be swiped to reply`() {
        val replies = ArrayList<String>()
        content { MessageBubble(row(deleted = true), BubbleActions(onReply = { replies += it }), PlaybackUi()) }
        swipe(dx = 320f)
        assertTrue(replies.isEmpty())
    }

    @Test fun `a message in a scrolling list - the list still scrolls vertically and the swipe still replies`() {
        val replies = ArrayList<String>()
        val rows = (1..30).map { row(id = "m$it", text = "Meddelande nummer $it") }
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        content {
            state = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(rows, key = { it.key }) { MessageBubble(it, BubbleActions(onReply = { id -> replies += id }), PlaybackUi()) }
            }
        }
        // a finger that starts on a bubble and moves up scrolls the list, replying to nothing
        compose.onNode(hasContentDescription("Meddelande nummer 2, ", substring = true)).performTouchInput {
            swipe(Offset(centerX, centerY + 150f), Offset(centerX + 10f, centerY - 150f), durationMillis = 250)
        }
        compose.waitForIdle()
        assertTrue(state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0, "the list did not scroll")
        assertTrue(replies.isEmpty(), "replied: $replies")
        // a horizontal swipe on a bubble replies and does not scroll
        val before = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        compose.onNode(hasContentDescription("Meddelande nummer 5, ", substring = true)).performTouchInput {
            swipe(Offset(centerX - 40f, centerY), Offset(centerX + 260f, centerY), durationMillis = 250)
        }
        compose.waitForIdle()
        assertEquals(listOf("m5"), replies)
        assertEquals(before, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
    }

    @Test fun `reply stays available without gestures - as an accessibility action and in the menu`() {
        val replies = ArrayList<String>()
        content { MessageBubble(row(), BubbleActions(onReply = { replies += it }), PlaybackUi()) }
        val node = compose.onNode(bubble)
        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertTrue(actions.any { it.label == "Svara" })
        actions.first { it.label == "Svara" }.action()
        assertEquals(listOf("m1"), replies)

        node.performTouchInput { longClick() }
        compose.onNodeWithText("Svara").performClick()
        assertEquals(listOf("m1", "m1"), replies)
    }

    // --- the long-press menu --------------------------------------------------------------------------------------------------

    private fun openMenu() = compose.onNode(bubble).performTouchInput { longClick() }

    @Test fun `someone else's message offers delete for me but not delete for everyone or info`() {
        content { MessageBubble(row(outgoing = false), BubbleActions(), PlaybackUi()) }
        openMenu()
        compose.onNodeWithText("Radera för mig").assertIsDisplayed()
        compose.onAllNodesWithText("Radera för alla").assertCountEquals(0)
        compose.onAllNodesWithText("Meddelandeinfo").assertCountEquals(0)
    }

    @Test fun `an own message offers info, delete for me and delete for everyone`() {
        val asked = ArrayList<String>()
        content {
            MessageBubble(
                row(outgoing = true),
                BubbleActions(onDeleteForMe = { asked += "me:$it" }, onDelete = { asked += "all:$it" }, onInfo = { asked += "info:$it" }),
                PlaybackUi(),
            )
        }
        openMenu()
        compose.onNodeWithText("Meddelandeinfo").assertIsDisplayed()
        compose.onNodeWithText("Radera för mig").assertIsDisplayed()
        compose.onNodeWithText("Radera för alla").assertIsDisplayed()
        compose.onNodeWithText("Radera för mig").performClick()
        openMenu()
        compose.onNodeWithText("Radera för alla").performClick()
        openMenu()
        compose.onNodeWithText("Meddelandeinfo").performClick()
        assertEquals(listOf("me:m1", "all:m1", "info:m1"), asked)
    }

    @Test fun `a message that is still queued can only be deleted for everyone`() {
        content { MessageBubble(row(outgoing = true, canDeleteForMe = false, canShowInfo = false), BubbleActions(), PlaybackUi()) }
        openMenu()
        compose.onAllNodesWithText("Radera för mig").assertCountEquals(0)
        compose.onNodeWithText("Radera för alla").assertIsDisplayed()
    }
}
