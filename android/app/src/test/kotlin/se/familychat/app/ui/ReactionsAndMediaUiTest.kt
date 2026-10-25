package se.familychat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.familychat.app.screenshots.Fixtures
import se.familychat.app.ui.components.BubbleActions
import se.familychat.app.ui.components.DisappearingDialog
import se.familychat.app.ui.components.EmojiCatalog
import se.familychat.app.ui.components.LocalNowMillis
import se.familychat.app.ui.components.MessageBubble
import se.familychat.app.ui.components.PlaybackUi
import se.familychat.app.ui.components.StagedStrip
import se.familychat.app.ui.model.AttachmentKindUi
import se.familychat.app.ui.model.AttachmentUi
import se.familychat.app.ui.model.AvatarUi
import se.familychat.app.ui.model.ChatRow
import se.familychat.app.ui.model.DeliveryUi
import se.familychat.app.ui.model.MessageUi
import se.familychat.app.ui.model.ReactionUi
import se.familychat.app.ui.model.ReactorUi
import se.familychat.app.ui.model.TransferUi
import se.familychat.app.ui.screens.StagedUi
import se.familychat.app.ui.theme.FamilyChatTheme
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reactions under a bubble, the long-press reaction bar and picker, the photo grid, the preview strip and the timer dialog. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class ReactionsAndMediaUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun fixedWorld() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        FamilyChatTheme(darkTheme = false, dynamicColor = false) {
            CompositionLocalProvider(LocalNowMillis provides Fixtures.NOW) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { block() }
            }
        }
    }

    private fun reactor(id: String, name: String, me: Boolean = false) = ReactorUi(id, name, AvatarUi(name), me)

    private fun message(
        id: String = "m1", text: String? = "Hej där", outgoing: Boolean = false, canReact: Boolean = true, canForward: Boolean = true,
        reactions: List<ReactionUi> = emptyList(), myReaction: String? = null, attachments: List<AttachmentUi> = emptyList(),
        forwarded: Boolean = false, imported: Boolean = false, expiresIn: Long? = null,
    ) = ChatRow.Message(
        MessageUi(
            id = id, outgoing = outgoing, senderName = null, senderAvatar = null, text = text, reply = null, attachments = attachments,
            time = Fixtures.at(10, 24, 14, 30), delivery = if (outgoing) DeliveryUi.READ else DeliveryUi.NONE, edited = false, deleted = false,
            undecryptable = null, failed = false, canEdit = false, canDelete = outgoing, canDeleteForMe = true, canShowInfo = false,
            senderId = if (outgoing) "me" else "bob", speaker = if (outgoing) null else "Bertil Ek", reactions = reactions, myReaction = myReaction,
            canReact = canReact, canForward = canForward, forwarded = forwarded, imported = imported, expiresIn = expiresIn,
        ),
        firstOfRun = true,
    )

    private fun openMenu() = compose.onNode(hasContentDescription("Hej där", substring = true)).performTouchInput { longClick() }

    // --- the reactions under a bubble ------------------------------------------------------------------------------------------

    private val reactions = listOf(
        ReactionUi("👍", listOf(reactor("bob", "Bertil Ek"), reactor("me", "Anna", me = true))),
        ReactionUi("❤️", listOf(reactor("cy", "Cia Lund"))),
    )

    @Test fun `reactions are shown under the bubble with their counts and are read out`() {
        content { MessageBubble(message(reactions = reactions, myReaction = "👍"), BubbleActions(), PlaybackUi()) }
        compose.onNodeWithContentDescription("👍, 2, ❤️, 1").assertIsDisplayed()
        // the chips themselves (their text is hidden from the reader: the row says it all)
        compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("1", useUnmergedTree = true).assertIsDisplayed()
        // the bubble's own description mentions them for a screen reader as well
        compose.onNode(hasContentDescription("👍 2 ❤️ 1", substring = true)).assertIsDisplayed()
    }

    @Test fun `a message without reactions shows no reaction row`() {
        content { MessageBubble(message(), BubbleActions(), PlaybackUi()) }
        compose.onAllNodesWithContentDescription("Reaktioner. Tryck för att se vilka som reagerat.", substring = true).assertCountEquals(0)
    }

    @Test fun `a tap on the reactions shows who reacted, and your own can be taken back`() {
        val reacted = ArrayList<Pair<String, String>>()
        content { MessageBubble(message(reactions = reactions, myReaction = "👍"), BubbleActions(onReact = { id, e -> reacted += id to e }), PlaybackUi()) }
        compose.onNodeWithContentDescription("👍, 2, ❤️, 1").performClick()
        compose.onNodeWithText("Reaktioner").assertIsDisplayed()
        compose.onNodeWithText("Bertil Ek").assertIsDisplayed()
        compose.onNodeWithText("Cia Lund").assertIsDisplayed()
        compose.onNodeWithText("Du").assertIsDisplayed()
        compose.onNodeWithText("Tryck på din reaktion för att ta bort den").assertIsDisplayed()
        // someone else's reaction is not clickable into anything; yours takes it back
        compose.onNodeWithText("Bertil Ek").performClick()
        assertTrue(reacted.isEmpty())
        compose.onNodeWithText("Du").performClick()
        assertEquals(listOf("m1" to "👍"), reacted)
        compose.onAllNodesWithText("Reaktioner").assertCountEquals(0) // the dialog closed
    }

    // --- the long-press bar and the picker ---------------------------------------------------------------------------------------

    @Test fun `long press shows the six quick reactions, a plus, and picking one reacts`() {
        val reacted = ArrayList<Pair<String, String>>()
        content { MessageBubble(message(), BubbleActions(onReact = { id, e -> reacted += id to e }), PlaybackUi()) }
        openMenu()
        for (e in EmojiCatalog.quick) compose.onNodeWithContentDescription("Reagera med $e").assertIsDisplayed()
        assertEquals(6, EmojiCatalog.quick.size)
        compose.onNodeWithContentDescription("Fler emojier").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reagera med 😂").performClick()
        assertEquals(listOf("m1" to "😂"), reacted)
    }

    @Test fun `the plus opens a grid with every emoji of the catalogue and picking one reacts`() {
        val reacted = ArrayList<Pair<String, String>>()
        content { MessageBubble(message(myReaction = "👍"), BubbleActions(onReact = { id, e -> reacted += id to e }), PlaybackUi()) }
        openMenu()
        compose.onNodeWithContentDescription("Fler emojier").performClick()
        compose.onNodeWithText("Välj en emoji").assertIsDisplayed()
        compose.onNodeWithText("Ansikten").assertIsDisplayed()
        // an emoji far down the grid
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasContentDescription("Reagera med 🦄"))
        compose.onNodeWithContentDescription("Reagera med 🦄").performClick()
        assertEquals(listOf("m1" to "🦄"), reacted)
        compose.onAllNodesWithText("Välj en emoji").assertCountEquals(0)
    }

    @Test fun `a message that cannot be reacted to has no reaction bar`() {
        content { MessageBubble(message(canReact = false, canForward = false), BubbleActions(), PlaybackUi()) }
        openMenu()
        compose.onAllNodesWithContentDescription("Fler emojier").assertCountEquals(0)
        compose.onAllNodesWithText("Vidarebefordra").assertCountEquals(0)
    }

    @Test fun `forward is in the long-press menu and a forwarded message says so`() {
        val forwarded = ArrayList<String>()
        content { MessageBubble(message(forwarded = true), BubbleActions(onForward = { forwarded += it }), PlaybackUi()) }
        compose.onNodeWithText("Vidarebefordrat", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasContentDescription("Vidarebefordrat", substring = true)).assertIsDisplayed() // and the bubble says so too
        openMenu()
        compose.onNodeWithText("Vidarebefordra").performClick()
        assertEquals(listOf("m1"), forwarded)
    }

    @Test fun `an imported message is marked`() {
        content { MessageBubble(message(imported = true), BubbleActions(), PlaybackUi()) }
        compose.onNodeWithText("Importerad", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasContentDescription("Importerad", substring = true)).assertIsDisplayed()
    }

    // --- several photos in one bubble ----------------------------------------------------------------------------------------------

    private fun photo(i: Int, transfer: TransferUi = TransferUi.Remote) =
        AttachmentUi("a$i", AttachmentKindUi.IMAGE, "image/jpeg", "IMG_$i.jpg", 1000, 1600, 1200, null, null, transfer)

    private fun grid(count: Int, actions: BubbleActions = BubbleActions()) =
        content { MessageBubble(message(text = "Kolla", attachments = (1..count).map { photo(it) }), actions, PlaybackUi()) }

    @Test fun `one photo keeps its own view, two to four are a grid, more say how many are hidden`() {
        for ((count, tiles) in listOf(1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 4, 10 to 4)) {
            compose.activityRule.scenario.recreate()
            grid(count)
            val photos = compose.onAllNodesWithContentDescription("Foto").fetchSemanticsNodes().size
            // the tiles carry the description "Foto" (plus the grid's own, which says how many there are)
            assertTrue(photos >= tiles, "$count photos: $photos nodes called Foto, expected at least $tiles")
            if (count >= 2) compose.onNodeWithContentDescription("$count foton och videor").assertIsDisplayed()
            if (count > 4) compose.onNodeWithText("+${count - 4}").assertIsDisplayed()
            else compose.onAllNodesWithText("+", substring = true).assertCountEquals(0)
        }
    }

    @Test fun `a tap on a tile opens that photo, a tap on one that is not downloaded fetches it`() {
        val opened = ArrayList<String>()
        val downloaded = ArrayList<String>()
        content {
            MessageBubble(
                message(text = "Kolla", attachments = listOf(photo(1, TransferUi.Ready("/f1")), photo(2), photo(3, TransferUi.Ready("/f3")), photo(4))),
                BubbleActions(onOpenAttachment = { _, a -> opened += a.id }, onDownload = { _, a -> downloaded += a.id }), PlaybackUi(),
            )
        }
        val tiles = compose.onAllNodesWithContentDescription("Foto")
        // find the tiles by order of appearance; Ready ones open, the others download
        for (i in 0 until tiles.fetchSemanticsNodes().size) tiles[i].performClick()
        assertTrue("a1" in opened || "a3" in opened, "opened: $opened, downloaded: $downloaded")
        assertTrue("a2" in downloaded || "a4" in downloaded, "opened: $opened, downloaded: $downloaded")
    }

    // --- the preview strip ---------------------------------------------------------------------------------------------------------

    @Test fun `the strip shows each chosen file with a button to take it out`() {
        val removed = ArrayList<String>()
        val uri = android.net.Uri.parse("content://x/1")
        val items = listOf(
            StagedUi("s1", uri, AttachmentKindUi.IMAGE, "a.jpg"), StagedUi("s2", uri, AttachmentKindUi.VIDEO, "b.mp4"),
            StagedUi("s3", uri, AttachmentKindUi.FILE, "c.pdf"),
        )
        content { StagedStrip(items, onRemove = { removed += it }) }
        compose.onNodeWithContentDescription("Valda filer, 3 av 10").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ta bort b.mp4").performClick()
        compose.onNodeWithContentDescription("Ta bort a.jpg").performClick()
        assertEquals(listOf("s2", "s1"), removed)
    }

    @Test fun `an empty strip takes no room`() {
        content { StagedStrip(emptyList(), onRemove = {}) }
        compose.onAllNodesWithContentDescription("Valda filer", substring = true).assertCountEquals(0)
    }

    // --- the timer dialog ------------------------------------------------------------------------------------------------------------

    @Test fun `the timer dialog offers off, 5 minutes, 1 hour, 1 day, 1 week and 4 weeks`() {
        val chosen = ArrayList<Long>()
        content { DisappearingDialog(current = 0, canChange = true, onSelect = { chosen += it }, onDismiss = {}) }
        for (label in listOf("Av", "5 minuter", "1 timme", "1 dag", "1 vecka", "4 veckor")) compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("1 dag").performClick()
        compose.onNodeWithText("4 veckor").performClick()
        compose.onNodeWithText("5 minuter").performClick()
        assertEquals(listOf(86_400L, 2_419_200L, 300L), chosen)
    }

    @Test fun `a group member who is not an administrator sees the timer but cannot change it`() {
        val chosen = ArrayList<Long>()
        content { DisappearingDialog(current = 3_600, canChange = false, onSelect = { chosen += it }, onDismiss = {}) }
        compose.onNodeWithText("1 timme").assertIsNotEnabled()
        compose.onNodeWithText("Bara administratörer kan ändra tiden i en grupp.").assertIsDisplayed()
        compose.onNodeWithText("1 dag").performClick()
        assertTrue(chosen.isEmpty())
    }

    @Test fun `a timer from another app that the settings do not offer is still shown`() {
        content { DisappearingDialog(current = 7_200, canChange = true, onSelect = {}, onDismiss = {}) }
        compose.onNodeWithText("7200 sekunder").assertIsDisplayed()
    }
}
