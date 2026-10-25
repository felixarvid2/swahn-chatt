@file:Suppress("MaxLineLength") // fixtures and one-line assertions

package se.swahnchat.app.screenshots

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import se.swahnchat.app.ui.components.BubbleActions
import se.swahnchat.app.ui.components.LocalNowMillis
import se.swahnchat.app.ui.components.mentionSuggestions
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.ui.model.DeliveryUi
import se.swahnchat.app.ui.model.MemberUi
import se.swahnchat.app.ui.model.MentionUi
import se.swahnchat.app.ui.model.MessageUi
import se.swahnchat.app.ui.model.PollOptionUi
import se.swahnchat.app.ui.model.PollUi
import se.swahnchat.app.ui.model.ReactorUi
import se.swahnchat.app.ui.nav.AppFrame
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
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/**
 * Screenshots of batch 5: a poll, @-mentions, text formatting, starred messages and scheduled messages. Same method as
 * [Screenshots040Test]; written to `app/screenshots/` (names start with `50`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class Screenshots050Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = File("screenshots").also { it.mkdirs() }

    @Before fun fixedWorld() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
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

    private fun both(name: String, content: @Composable () -> Unit) {
        shot(name, false, content)
    }

    private fun voter(user: se.swahnchat.app.ui.model.UserUi, me: Boolean = false) = ReactorUi(user.id, user.name, user.avatar, me)

    private val poll = PollUi(
        options = listOf(
            PollOptionUi(0, "Lördag kl 17", listOf(voter(Fixtures.bob), voter(Fixtures.cia), voter(Fixtures.anna, me = true)), mine = true),
            PollOptionUi(1, "Söndag kl 13", listOf(voter(Fixtures.david)), mine = false),
            PollOptionUi(2, "Någon annan dag", emptyList(), mine = false),
        ),
        multiple = false, closed = false, voterCount = 4, canVote = true, canClose = false,
    )

    private fun message(
        id: String, from: se.swahnchat.app.ui.model.UserUi?, text: String?, time: Long, poll: PollUi? = null, mentions: List<MentionUi> = emptyList(),
        starred: Boolean = false, scheduledAt: Long? = null,
    ): ChatRow.Message {
        val outgoing = from == null
        return ChatRow.Message(
            MessageUi(
                id = id, outgoing = outgoing, senderName = if (!outgoing) from!!.name else null, senderAvatar = if (!outgoing) from!!.avatar else null,
                text = text, reply = null, attachments = emptyList(), time = time,
                delivery = if (scheduledAt != null) DeliveryUi.PENDING else if (outgoing) DeliveryUi.READ else DeliveryUi.NONE,
                edited = false, deleted = false, undecryptable = null, failed = false, canEdit = false, canDelete = outgoing, senderId = from?.id ?: "me",
                speaker = from?.name, poll = poll, mentions = mentions, starred = starred, canStar = true, scheduledAt = scheduledAt, canReact = scheduledAt == null,
            ),
            firstOfRun = true,
        )
    }

    private val state = ConversationUiState(title = "Familjen Ek", isGroup = true, memberCount = 4, avatar = AvatarUi("Familjen Ek"), loaded = true)

    @Composable private fun Conversation(rows: List<ChatRow>, s: ConversationUiState = state) {
        val items = flowOf(Fixtures.pages(rows)).collectAsLazyPagingItems()
        ConversationContent(
            s.copy(latestId = (rows.lastOrNull { it is ChatRow.Message } as? ChatRow.Message)?.message?.id), items, Fixtures.idle,
            BubbleActions(), ConversationCallbacks(),
        )
    }

    private val day = ChatRow.DateHeader(Fixtures.at(10, 24, 0))

    private val pollRows = listOf(
        day,
        message("1", Fixtures.cia, "Vi grillar hos oss, när passar det?", Fixtures.at(10, 24, 15, 0)),
        message("2", Fixtures.cia, "Vilken dag vill ni komma?", Fixtures.at(10, 24, 15, 1), poll = poll),
        message("3", null, "Lördag funkar för mig!", Fixtures.at(10, 24, 15, 5)),
    )

    private val mentionRows = listOf(
        day,
        message("m1", Fixtures.bob, "@Anna Lindqvist kan du ta med efterrätt?", Fixtures.at(10, 24, 15, 58), mentions = listOf(MentionUi("me", "Anna Lindqvist", true))),
        message("m2", null, "Absolut! @Cecilia Ek tar du dryck?", Fixtures.at(10, 24, 16, 2), mentions = listOf(MentionUi("cia", "Cecilia Ek", false))),
        message("m3", Fixtures.cia, "Jag fixar det, @Bertil Ek tar kolen.", Fixtures.at(10, 24, 16, 5), mentions = listOf(MentionUi("bob", "Bertil Ek", false))),
    )

    private val formatRows = listOf(
        day,
        message("f1", Fixtures.bob, "Det här är *fet*, _kursiv_ och ~genomstruken~ text", Fixtures.at(10, 24, 15, 58)),
        message("f2", null, "Kombinera: *_fet och kursiv_* eller `kod()` i en rad", Fixtures.at(10, 24, 16, 2)),
        message("f3", Fixtures.cia, "```\nval grill = true\nif (grill) println(\"kom!\")\n```\nSe https://exempel.se/a_b_c och snake_case_namn", Fixtures.at(10, 24, 16, 5)),
        message("f4", null, "Ensamma * stjärnor och _understreck blir kvar", Fixtures.at(10, 24, 16, 8)),
    )

    private val members = listOf(
        MemberUi("me", "Anna Lindqvist", "anna", AvatarUi("Anna Lindqvist"), false, true),
        MemberUi("bob", "Bertil Ek", "bertil", Fixtures.bob.avatar, false, false),
        MemberUi("cia", "Cecilia Ek", "cecilia", Fixtures.cia.avatar, false, false),
        MemberUi("emma", "Emma Berg", "emma", Fixtures.emma.avatar, false, false),
    )

    private val starredRows = listOf(
        StarredRowUi("s1", "g1", "Familjen Ek", AvatarUi("Familjen Ek"), "Cecilia Ek", Fixtures.at(10, 23, 18, 0), "Husnyckeln ligger under blomkrukan vid dörren", null),
        StarredRowUi("s2", "d1", "Bertil Ek", Fixtures.bob.avatar, null, Fixtures.at(10, 20, 9, 12), "Adressen till stugan: Skogsvägen 12", null),
        StarredRowUi("s3", "g2", "Semesterplanering", AvatarUi("Semesterplanering"), "David Ek", Fixtures.at(10, 12, 14, 30), "Vilken dag vill ni komma?", null, isPoll = true),
    )

    @Test fun pollLight() = both("50_poll") { Conversation(pollRows) }
    @Test fun pollDark() = shot("50_poll", true) { Conversation(pollRows) }

    @Test fun mentionsLight() = both("50b_mentions") { Conversation(mentionRows) }
    @Test fun mentionsDark() = shot("50b_mentions", true) { Conversation(mentionRows) }

    @Test fun mentionPickerLight() = both("50c_mention_picker") {
        Conversation(mentionRows.take(2), state.copy(draft = "Hej @e", mentionSuggestions = mentionSuggestions(members, "e")))
    }
    @Test fun mentionPickerDark() = shot("50c_mention_picker", true) {
        Conversation(mentionRows.take(2), state.copy(draft = "Hej @e", mentionSuggestions = mentionSuggestions(members, "e")))
    }

    @Test fun formattingLight() = both("50d_formatting") { Conversation(formatRows) }
    @Test fun formattingDark() = shot("50d_formatting", true) { Conversation(formatRows) }

    @Test fun starredListLight() = both("50e_starred") { StarredContent(StarredState(true, starredRows), showChat = true, onBack = {}, onOpen = { _, _ -> }) }
    @Test fun starredListDark() = shot("50e_starred", true) { StarredContent(StarredState(true, starredRows), showChat = true, onBack = {}, onOpen = { _, _ -> }) }

    private val scheduledRows = listOf(
        day,
        message("a", Fixtures.cia, "Ses vi på lördag?", Fixtures.at(10, 24, 15, 58)),
        message("b", null, "Ja! Jag tar med kaffe.", Fixtures.at(10, 24, 16, 2)),
        message("c", null, "Grattis på födelsedagen, Cecilia!", Fixtures.at(10, 25, 8, 0), scheduledAt = Fixtures.at(10, 25, 8, 0)),
    )

    @Test fun scheduledBubbleLight() = both("50f_scheduled_bubble") { Conversation(scheduledRows, state.copy(scheduledCount = 1)) }
    @Test fun scheduledBubbleDark() = shot("50f_scheduled_bubble", true) { Conversation(scheduledRows, state.copy(scheduledCount = 1)) }

    private val scheduledList = ScheduledState(
        true,
        listOf(
            ScheduledRowUi("c", "Grattis på födelsedagen, Cecilia!", 0, Fixtures.at(10, 25, 8, 0)),
            ScheduledRowUi("d", null, 2, Fixtures.at(10, 31, 18, 30)),
        ),
    )

    @Test fun scheduledListLight() = both("50g_scheduled_list") { ScheduledContent(scheduledList, {}, { _, _ -> }, {}, {}) }
    @Test fun scheduledListDark() = shot("50g_scheduled_list", true) { ScheduledContent(scheduledList, {}, { _, _ -> }, {}, {}) }
}
