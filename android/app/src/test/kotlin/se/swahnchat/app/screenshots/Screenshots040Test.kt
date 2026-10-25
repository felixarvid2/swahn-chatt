package se.swahnchat.app.screenshots

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.swahnchat.app.data.history.ExportResult
import se.swahnchat.app.data.history.HistoryJob
import se.swahnchat.app.data.history.ImportResult
import se.swahnchat.app.media.PendingShare
import se.swahnchat.app.ui.components.BubbleActions
import se.swahnchat.app.ui.components.DisappearingOptions
import se.swahnchat.app.ui.components.LocalNowMillis
import se.swahnchat.app.ui.components.QuickReactionRow
import se.swahnchat.app.ui.components.ReactionPickerGrid
import se.swahnchat.app.ui.model.AttachmentKindUi
import se.swahnchat.app.ui.model.AttachmentUi
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.ui.model.DeliveryUi
import se.swahnchat.app.ui.model.MessageUi
import se.swahnchat.app.ui.model.ReactionUi
import se.swahnchat.app.ui.model.ReactorUi
import se.swahnchat.app.ui.model.SystemKind
import se.swahnchat.app.ui.model.TransferUi
import se.swahnchat.app.ui.nav.AppFrame
import se.swahnchat.app.ui.screens.ConversationCallbacks
import se.swahnchat.app.ui.screens.ConversationContent
import se.swahnchat.app.ui.screens.ConversationUiState
import se.swahnchat.app.ui.screens.ForwardContent
import se.swahnchat.app.ui.screens.GroupInfoCallbacks
import se.swahnchat.app.ui.screens.GroupInfoContent
import se.swahnchat.app.ui.screens.GroupInfoState
import se.swahnchat.app.ui.screens.HistoryContent
import se.swahnchat.app.ui.screens.ShareScreen
import se.swahnchat.app.ui.screens.ShareState
import se.swahnchat.app.ui.screens.StagedUi
import se.swahnchat.app.ui.theme.SwahnChatTheme
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/**
 * Screenshots of what 0.4.0 added: reactions, the photo grid and preview strip, the share picker, forwarding, the timer setting
 * and its notices, and the history export. Same method as [ScreenshotTest]; written to `app/screenshots/`.
 * Dialogs and menus are windows of their own and do not appear in a screenshot of the activity, so their contents are drawn
 * on a card instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class Screenshots040Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = File("screenshots").also { it.mkdirs() }

    @Before fun fixedWorld() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            SwahnChatTheme(darkTheme = dark, dynamicColor = false) {
                CompositionLocalProvider(LocalNowMillis provides Fixtures.NOW) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppFrame(false, content) }
                }
            }
        }
        compose.waitForIdle()
        val image = compose.activity.window.decorView.drawToBitmap()
        FileOutputStream(File(dir, "${name}_${if (dark) "dark" else "light"}.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------------

    private fun who(user: se.swahnchat.app.ui.model.UserUi, me: Boolean = false) = ReactorUi(user.id, user.name, user.avatar, me)

    private fun message(
        id: String, from: se.swahnchat.app.ui.model.UserUi?, text: String?, time: Long, attachments: List<AttachmentUi> = emptyList(),
        reactions: List<ReactionUi> = emptyList(), myReaction: String? = null, forwarded: Boolean = false, imported: Boolean = false,
        expiresIn: Long? = null, delivery: DeliveryUi = DeliveryUi.READ,
    ): ChatRow.Message {
        val outgoing = from == null
        return ChatRow.Message(
            MessageUi(
                id = id, outgoing = outgoing, senderName = if (!outgoing) from!!.name else null, senderAvatar = if (!outgoing) from!!.avatar else null,
                text = text, reply = null, attachments = attachments, time = time, delivery = if (outgoing) delivery else DeliveryUi.NONE,
                edited = false, deleted = false, undecryptable = null, failed = false, canEdit = false, canDelete = outgoing,
                reactions = reactions, myReaction = myReaction, canReact = true, canForward = true, forwarded = forwarded, imported = imported,
                expiresIn = expiresIn,
            ),
            firstOfRun = true,
        )
    }

    private fun photo(id: String, ready: Boolean = false) = AttachmentUi(
        id, AttachmentKindUi.IMAGE, "image/jpeg", "IMG_$id.jpg", 1_800_000, 1600, 1200, null, Fixtures.photoThumb,
        if (ready) TransferUi.Ready("/missing/$id") else TransferUi.Remote,
    )

    private fun video(id: String) = AttachmentUi(
        id, AttachmentKindUi.VIDEO, "video/mp4", "film.mp4", 9_400_000, 1280, 720, 42_000, Fixtures.videoThumb, TransferUi.Ready("/missing/$id"),
    )

    private val state = ConversationUiState(title = "Familjen Ek", isGroup = true, memberCount = 4, avatar = AvatarUi("Familjen Ek"), loaded = true)

    @Composable private fun Conversation(rows: List<ChatRow>, s: ConversationUiState = state) {
        val items = flowOf(Fixtures.pages(rows)).collectAsLazyPagingItems()
        ConversationContent(
            s.copy(latestId = (rows.lastOrNull { it is ChatRow.Message } as? ChatRow.Message)?.message?.id), items, Fixtures.idle,
            BubbleActions(), ConversationCallbacks(),
        )
    }

    private val reactionRows = listOf(
        ChatRow.DateHeader(Fixtures.at(10, 24, 0)),
        message("1", Fixtures.bob, "Vi grillar på lördag, vem kommer?", Fixtures.at(10, 24, 15, 58), reactions = listOf(
            ReactionUi("👍", listOf(who(Fixtures.anna, me = true), who(Fixtures.cia), who(Fixtures.david))),
            ReactionUi("❤️", listOf(who(Fixtures.emma))),
            ReactionUi("😂", listOf(who(Fixtures.cia), who(Fixtures.david))),
        ), myReaction = "👍"),
        message("2", null, "Jag kommer! Tar med efterrätt.", Fixtures.at(10, 24, 16, 2), reactions = listOf(
            ReactionUi("🙏", listOf(who(Fixtures.bob))),
        )),
        message("3", Fixtures.cia, "Perfekt, då blir vi sex", Fixtures.at(10, 24, 16, 10)),
    )

    // --- reactions ------------------------------------------------------------------------------------------------------------------

    @Test fun reactionsLight() = shot("30_reactions") { Conversation(reactionRows) }
    @Test fun reactionsDark() = shot("30_reactions", dark = true) { Conversation(reactionRows) }

    @Test fun reactionBarAndPickerLight() = shot("30b_reaction_bar_and_picker") {
        Column(Modifier.padding(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text("Håll in ett meddelande", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
                    QuickReactionRow(selected = "👍", onPick = {}, onMore = {})
                }
            }
            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Välj en emoji", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                    ReactionPickerGrid(selected = "👍", onPick = {})
                }
            }
        }
    }

    // --- several photos ----------------------------------------------------------------------------------------------------------------

    private val gridRows = listOf(
        ChatRow.DateHeader(Fixtures.at(10, 24, 0)),
        message("g2", Fixtures.cia, "Två bilder", Fixtures.at(10, 24, 14, 0), attachments = listOf(photo("a"), photo("b", ready = false))),
        message("g3", null, "Tre bilder och en bildtext", Fixtures.at(10, 24, 14, 5), attachments = listOf(photo("c"), photo("d"), photo("e"))),
        message("g4", Fixtures.bob, null, Fixtures.at(10, 24, 14, 8), attachments = listOf(photo("f"), photo("g"), video("h"), photo("i"))),
        message("g6", Fixtures.david, "Hela resan, sex bilder", Fixtures.at(10, 24, 14, 12), attachments = (1..6).map { photo("k$it") }),
    )

    private val smallGridRows = listOf(
        ChatRow.DateHeader(Fixtures.at(10, 24, 0)),
        message("h2", Fixtures.cia, "Två bilder", Fixtures.at(10, 24, 14, 0), attachments = listOf(photo("a"), photo("b"))),
        message("h3", null, "Tre bilder och en bildtext", Fixtures.at(10, 24, 14, 5), attachments = listOf(photo("c"), photo("d"), photo("e"))),
    )

    @Test fun mediaGridTwoAndThreeLight() = shot("31c_media_grid_two_and_three") { Conversation(smallGridRows) }

    @Test fun mediaGridLight() = shot("31_media_grid") { Conversation(gridRows) }
    @Test fun mediaGridDark() = shot("31_media_grid", dark = true) { Conversation(gridRows) }

    @Test fun stagedStripLight() = shot("31b_staged_strip_with_caption") {
        val uri = Uri.parse("content://example/1")
        Conversation(
            listOf(ChatRow.DateHeader(Fixtures.at(10, 24, 0)), message("x", Fixtures.bob, "Skicka bilderna från idag!", Fixtures.at(10, 24, 17, 0))),
            state.copy(
                draft = "Kul dag!", staged = listOf(
                    StagedUi("1", uri, AttachmentKindUi.IMAGE, "IMG_1.jpg"), StagedUi("2", uri, AttachmentKindUi.IMAGE, "IMG_2.jpg"),
                    StagedUi("3", uri, AttachmentKindUi.VIDEO, "film.mp4"), StagedUi("4", uri, AttachmentKindUi.FILE, "boende-kroatien.pdf"),
                    StagedUi("5", uri, AttachmentKindUi.IMAGE, "IMG_5.jpg"),
                ),
            ),
        )
    }

    // --- forwarded, imported, timers -----------------------------------------------------------------------------------------------------

    private val timerRows = listOf(
        ChatRow.DateHeader(Fixtures.at(10, 24, 0)),
        ChatRow.System("t1", SystemKind.TIMER_CHANGED, "bob", "Bertil Ek", null, Fixtures.at(10, 24, 9, 0), value = 86_400),
        message("t2", Fixtures.bob, "Det här försvinner i morgon", Fixtures.at(10, 24, 9, 1), expiresIn = 86_400),
        message("t3", null, "Okej!", Fixtures.at(10, 24, 9, 2), expiresIn = 86_400),
        message("t4", Fixtures.cia, "Skickat vidare från Semesterplanering", Fixtures.at(10, 24, 9, 5), forwarded = true),
        ChatRow.System("t5", SystemKind.TIMER_CHANGED, "me", "Anna Lindqvist", null, Fixtures.at(10, 24, 10, 0), own = true, value = 0),
        ChatRow.System("t6", SystemKind.UPDATE_REQUIRED, "", "", null, Fixtures.at(10, 24, 10, 1)),
        message("t7", Fixtures.david, "Gammalt meddelande från min förra telefon", Fixtures.at(10, 20, 10, 0), imported = true),
    ).sortedBy { (it as? ChatRow.Message)?.message?.time ?: (it as? ChatRow.System)?.time ?: (it as ChatRow.DateHeader).dayStart }

    @Test fun timerNoticesLight() = shot("32_timer_notices_forwarded_imported") {
        Conversation(timerRows, state.copy(timerSeconds = 86_400))
    }

    @Test fun timerSettingLight() = shot("32b_timer_setting") {
        Column(Modifier.padding(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Försvinnande meddelanden", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                    DisappearingOptions(current = 86_400, canChange = true, onSelect = {})
                }
            }
        }
    }

    @Test fun timerSettingDark() = shot("32b_timer_setting", dark = true) {
        Column(Modifier.padding(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Försvinnande meddelanden", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                    DisappearingOptions(current = 3_600, canChange = false, onSelect = {})
                }
            }
        }
    }

    @Test fun groupInfoWithTimerLight() = shot("32c_group_info_timer") {
        GroupInfoContent(
            GroupInfoState(name = "Familjen Ek", avatar = AvatarUi("Familjen Ek"), members = Fixtures.members, meIsAdmin = true, timerSeconds = 604_800),
            GroupInfoCallbacks(),
        )
    }

    // --- forward and share --------------------------------------------------------------------------------------------------------------------

    @Test fun forwardPickerLight() = shot("33_forward_picker") {
        ForwardContent(
            items = Fixtures.conversationItems, selected = setOf("g1", "d2"), query = "", sending = false, snackbar = SnackbarHostState(),
            onBack = {}, onQuery = {}, onToggle = {}, onSend = {},
        )
    }

    @Test fun sharePickerLight() = shot("34_share_picker") {
        ShareScreen(
            state = ShareState.Ready(
                "Kolla in den här artikeln https://exempel.se/artikel", listOf(File("/tmp/semester.pdf"), File("/tmp/IMG_1.jpg")),
                refused = 1, truncated = false,
            ),
            items = Fixtures.conversationItems, onChoose = {}, onClose = {},
        )
    }

    @Test fun sharePickerDark() = shot("34_share_picker", dark = true) {
        ShareScreen(
            state = ShareState.Ready(null, listOf(File("/tmp/semester.pdf")), refused = 0, truncated = false),
            items = Fixtures.conversationItems, onChoose = {}, onClose = {},
        )
    }

    // --- history export --------------------------------------------------------------------------------------------------------------------------

    @Test fun historyIdleLight() = shot("35_history_export") {
        HistoryContent(HistoryJob.Idle, {}, { _, _ -> }, {}, importChosen = false, onImport = {}, onCancelImport = {}, onDismissResult = {})
    }

    @Test fun historyResultDark() = shot("35_history_export_done", dark = true) {
        HistoryContent(
            HistoryJob.Exported(ExportResult(conversations = 12, messages = 4_231, mediaFiles = 318, leftOut = 7)),
            {}, { _, _ -> }, {}, importChosen = false, onImport = {}, onCancelImport = {}, onDismissResult = {},
        )
    }

    @Test fun historyImportedLight() = shot("35b_history_import_done") {
        HistoryContent(
            HistoryJob.Imported(ImportResult(imported = 4_100, alreadyHere = 120, unknownConversation = 11, invalid = 0, mediaFiles = 300)),
            {}, { _, _ -> }, {}, importChosen = false, onImport = {}, onCancelImport = {}, onDismissResult = {},
        )
    }

    @Suppress("unused") private val keep = PendingShare(null, emptyList())
}
