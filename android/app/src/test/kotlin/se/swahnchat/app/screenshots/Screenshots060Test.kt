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
import androidx.paging.LoadState
import androidx.paging.LoadStates
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
import se.swahnchat.app.ui.components.BubbleActions
import se.swahnchat.app.ui.components.LocalNowMillis
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.ui.model.DeliveryUi
import se.swahnchat.app.ui.model.FileItem
import se.swahnchat.app.ui.model.GalleryItem
import se.swahnchat.app.ui.model.LinkItem
import se.swahnchat.app.ui.model.LocationUi
import se.swahnchat.app.ui.model.MediaCell
import se.swahnchat.app.ui.model.MessageUi
import se.swahnchat.app.ui.nav.AppFrame
import se.swahnchat.app.ui.screens.ConversationCallbacks
import se.swahnchat.app.ui.screens.ConversationContent
import se.swahnchat.app.ui.screens.ConversationUiState
import se.swahnchat.app.ui.screens.GalleryTab
import se.swahnchat.app.ui.screens.MediaGalleryContent
import se.swahnchat.app.ui.theme.SwahnChatTheme
import java.io.File
import java.io.FileOutputStream
import java.time.YearMonth
import java.util.TimeZone

/**
 * Screenshots of batch 6: a shared position and the media gallery (dialogs are separate windows and do not show in these images), light and dark. Same method as
 * [Screenshots050Test]; written to `app/screenshots/` (names start with `60`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class Screenshots060Test {
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
        compose.activityRule.scenario.recreate()
        shot(name, true, content)
    }

    private fun position(id: String, from: se.swahnchat.app.ui.model.UserUi?, time: Long, location: LocationUi): ChatRow.Message {
        val outgoing = from == null
        return ChatRow.Message(
            MessageUi(
                id = id, outgoing = outgoing, senderName = if (!outgoing) from!!.name else null, senderAvatar = if (!outgoing) from!!.avatar else null,
                text = null, reply = null, attachments = emptyList(), time = time, delivery = if (outgoing) DeliveryUi.READ else DeliveryUi.NONE,
                edited = false, deleted = false, undecryptable = null, failed = false, canEdit = false, canDelete = outgoing,
                senderId = from?.id ?: "me", speaker = from?.name, canStar = true, canReact = true, location = location,
            ),
            firstOfRun = true,
        )
    }

    private fun text(id: String, from: se.swahnchat.app.ui.model.UserUi?, text: String, time: Long): ChatRow.Message {
        val outgoing = from == null
        return ChatRow.Message(
            MessageUi(
                id = id, outgoing = outgoing, senderName = if (!outgoing) from!!.name else null, senderAvatar = if (!outgoing) from!!.avatar else null,
                text = text, reply = null, attachments = emptyList(), time = time, delivery = if (outgoing) DeliveryUi.READ else DeliveryUi.NONE,
                edited = false, deleted = false, undecryptable = null, failed = false, canEdit = false, canDelete = outgoing,
                senderId = from?.id ?: "me", speaker = from?.name,
            ),
            firstOfRun = true,
        )
    }

    private val rows = listOf(
        ChatRow.DateHeader(Fixtures.at(10, 24, 0)),
        text("1", Fixtures.bob, "Var är ni? Vi står vid parkeringen.", Fixtures.at(10, 24, 15, 58)),
        position("2", null, Fixtures.at(10, 24, 16, 2), LocationUi(59.329323, 18.068581, 8.0, "Vid stora lekplatsen")),
        position("3", Fixtures.bob, Fixtures.at(10, 24, 16, 4), LocationUi(59.334591, 18.063240, 25.0, null)),
    )

    private val state = ConversationUiState(title = "Bertil Ek", isGroup = false, otherUserId = "bob", avatar = Fixtures.bob.avatar, loaded = true)

    @Composable private fun Conversation() {
        val items = flowOf(Fixtures.pages(rows)).collectAsLazyPagingItems()
        ConversationContent(state.copy(latestId = "3"), items, Fixtures.idle, BubbleActions(), ConversationCallbacks())
    }

    @Test fun locationBubble() = both("60_location_bubble") { Conversation() }

    private fun cell(id: String, month: Int, day: Int, video: Boolean = false, thumb: Boolean = true) =
        GalleryItem.Cell(
            MediaCell(
                "m$id", "a$id", video, if (!thumb) null else if (video) Fixtures.videoThumb else Fixtures.photoThumb,
                Fixtures.at(month, day, 12), if (video) 23_000 else null,
            ),
        )

    private val media: List<GalleryItem> = listOf(
        GalleryItem.Header(YearMonth.of(2026, 10)),
        cell("1", 10, 24), cell("2", 10, 22, video = true), cell("3", 10, 22), cell("4", 10, 15), cell("5", 10, 12, video = true), cell("6", 10, 3),
        GalleryItem.Header(YearMonth.of(2026, 9)),
        cell("7", 9, 28), cell("8", 9, 14), cell("9", 9, 14, video = true), cell("10", 9, 2),
    )

    private val files = listOf(
        FileItem("f1", "fa1", "Hyreskontrakt.pdf", "application/pdf", 245_000, Fixtures.at(10, 20, 12)),
        FileItem("f2", "fa2", "Semesterplan 2027.xlsx", "application/vnd.ms-excel", 1_820_000, Fixtures.at(10, 9, 8)),
    )

    private val links = listOf(
        LinkItem("l1", "https://exempel.se/recept/lax-i-ugn", Fixtures.at(10, 21, 12), outgoing = false),
        LinkItem("l2", "https://www.smhi.se/vader/prognoser", Fixtures.at(10, 18, 9), outgoing = true),
    )

    private fun <T : Any> loaded(items: List<T>): PagingData<T> {
        val done = LoadState.NotLoading(endOfPaginationReached = true)
        return PagingData.from(items, LoadStates(refresh = LoadState.NotLoading(false), prepend = done, append = done))
    }

    @Composable private fun Gallery(tab: GalleryTab) = MediaGalleryContent(
        flowOf(loaded(media)).collectAsLazyPagingItems(), flowOf(loaded(files)).collectAsLazyPagingItems(),
        flowOf(loaded(links)).collectAsLazyPagingItems(), onBack = {}, onOpenMedia = { _, _ -> }, onOpenFile = {}, onOpenLinkMessage = {},
        initialTab = tab,
    )

    @Test fun galleryMedia() = both("61_gallery_media") { Gallery(GalleryTab.MEDIA) }
    @Test fun galleryFiles() = both("61b_gallery_files") { Gallery(GalleryTab.FILES) }
    @Test fun galleryLinks() = both("61c_gallery_links") { Gallery(GalleryTab.LINKS) }
}
