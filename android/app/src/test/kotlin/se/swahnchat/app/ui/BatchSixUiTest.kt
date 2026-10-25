@file:Suppress("MaxLineLength") // fixtures and one-line assertions

package se.swahnchat.app.ui

import android.Manifest
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.swahnchat.app.media.AttachmentPreparer
import se.swahnchat.app.media.CaptureTarget
import se.swahnchat.app.screenshots.Fixtures
import se.swahnchat.app.ui.components.BubbleActions
import se.swahnchat.app.ui.components.LocalNowMillis
import se.swahnchat.app.ui.components.LocationShareDialogs
import se.swahnchat.app.ui.components.LocationShareUi
import se.swahnchat.app.ui.components.MessageBubble
import se.swahnchat.app.ui.components.PlaybackUi
import se.swahnchat.app.ui.components.StagedStrip
import se.swahnchat.app.ui.components.VideoSendOptions
import se.swahnchat.app.ui.components.rememberCameraActions
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.ui.model.ChatRowContext
import se.swahnchat.app.ui.model.DeliveryUi
import se.swahnchat.app.ui.model.FileItem
import se.swahnchat.app.ui.model.GalleryItem
import se.swahnchat.app.ui.model.LinkItem
import se.swahnchat.app.ui.model.LocationUi
import se.swahnchat.app.ui.model.MediaCell
import se.swahnchat.app.ui.model.MessageUi
import se.swahnchat.app.ui.model.fileItems
import se.swahnchat.app.ui.model.linkItems
import se.swahnchat.app.ui.model.mediaCells
import se.swahnchat.app.ui.model.monthHeaderBetween
import se.swahnchat.app.ui.model.toChatRow
import se.swahnchat.app.ui.screens.CompressionUi
import se.swahnchat.app.ui.screens.GalleryTab
import se.swahnchat.app.ui.screens.MediaGalleryContent
import se.swahnchat.app.ui.screens.StagedUi
import se.swahnchat.app.ui.theme.SwahnChatTheme
import se.swahnchat.core.client.DownloadState
import se.swahnchat.core.client.LocalAttachment
import se.swahnchat.core.client.MessageStatus
import se.swahnchat.core.client.StoredMessage
import se.swahnchat.core.model.LocationSpec
import java.io.File
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Position bubble and dialogs, the media gallery, the camera flow and the video options as a user meets them (batch 6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
class BatchSixUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun fixedWorld() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        SwahnChatTheme(darkTheme = false, dynamicColor = false) {
            CompositionLocalProvider(LocalNowMillis provides Fixtures.NOW) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { block() }
            }
        }
    }

    // --- the position bubble ------------------------------------------------------------------------------------------------

    private val here = LocationUi(59.3293, 18.0686, 12.0, "Hemma hos mormor")

    private fun positionRow(location: LocationUi? = here, outgoing: Boolean = false) = ChatRow.Message(
        MessageUi(
            id = "p1", outgoing = outgoing, senderName = null, senderAvatar = null, text = null, reply = null, attachments = emptyList(),
            time = Fixtures.at(10, 24, 14, 30), delivery = if (outgoing) DeliveryUi.READ else DeliveryUi.NONE, edited = false, deleted = false,
            undecryptable = null, failed = false, canEdit = false, canDelete = outgoing, senderId = if (outgoing) "me" else "bob",
            speaker = if (outgoing) null else "Bertil Ek", canStar = true, canReact = true, location = location,
        ),
        firstOfRun = true,
    )

    @Test fun `a position shows its coordinates, accuracy and label drawn offline, with the two actions`() {
        content { MessageBubble(positionRow(), BubbleActions(), PlaybackUi()) }
        // like the text of any bubble, the numbers are read as part of the bubble's one spoken line
        compose.onNode(hasContentDescription("Position 59.329300, 18.068600, noggrannhet 12 m, Hemma hos mormor", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Öppna i kartapp").assertIsDisplayed()
        compose.onNodeWithText("Kopiera koordinater").assertIsDisplayed()
        compose.onNodeWithContentDescription("Position, ritad utan karta").assertIsDisplayed()
    }

    @Test fun `opening in a map app first asks, and a no hands over nothing`() {
        val opened = ArrayList<Triple<Double, Double, String?>>()
        content { MessageBubble(positionRow(), BubbleActions(onOpenLocation = { a, b, c -> opened += Triple(a, b, c) }), PlaybackUi()) }
        compose.onAllNodesWithText("Kartappen du väljer får se den här positionen.").assertCountEquals(0)
        compose.onNodeWithText("Öppna i kartapp").performClick()
        compose.onNodeWithText("Kartappen du väljer får se den här positionen.").assertIsDisplayed()
        assertTrue(opened.isEmpty(), "nothing is handed over before the answer")
        compose.onNodeWithText("Avbryt").performClick()
        compose.onAllNodesWithText("Kartappen du väljer får se den här positionen.").assertCountEquals(0)
        assertTrue(opened.isEmpty())
    }

    @Test fun `confirming hands the position and its label to the chooser`() {
        val opened = ArrayList<Triple<Double, Double, String?>>()
        content { MessageBubble(positionRow(), BubbleActions(onOpenLocation = { a, b, c -> opened += Triple(a, b, c) }), PlaybackUi()) }
        compose.onNodeWithText("Öppna i kartapp").performClick()
        compose.onNodeWithText("Välj kartapp").performClick()
        assertEquals(listOf<Triple<Double, Double, String?>>(Triple(59.3293, 18.0686, "Hemma hos mormor")), opened)
        compose.onAllNodesWithText("Kartappen du väljer får se den här positionen.").assertCountEquals(0)
    }

    @Test fun `copying gives the coordinates with a dot and no position leaves the phone`() {
        val copied = ArrayList<String>()
        val opened = ArrayList<String>()
        content { MessageBubble(positionRow(), BubbleActions(onCopy = { copied += it }, onOpenLocation = { _, _, _ -> opened += "x" }), PlaybackUi()) }
        compose.onNodeWithText("Kopiera koordinater").performClick()
        assertEquals(listOf("59.329300, 18.068600"), copied)
        assertTrue(opened.isEmpty())
    }

    @Test fun `a position without a label shows no name`() {
        content { MessageBubble(positionRow(here.copy(label = null), outgoing = true), BubbleActions(), PlaybackUi()) }
        compose.onNode(hasContentDescription("Position 59.329300, 18.068600, noggrannhet 12 m", substring = true)).assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Hemma hos mormor", substring = true)).assertCountEquals(0)
    }

    // --- the position flow ------------------------------------------------------------------------------------------------------

    private val spec = LocationSpec(59.3293, 18.0686, 25.0)

    @Test fun `the first step asks how exact, and the choice is reported`() {
        val chosen = ArrayList<Boolean>()
        content { LocationShareDialogs(LocationShareUi.Choose, onChoose = { chosen += it }, onCancel = {}, onSend = {}) }
        compose.onNodeWithText("Dela din position").assertIsDisplayed()
        compose.onNodeWithText("Ungefärlig position").assertIsDisplayed()
        compose.onNodeWithText("Exakt position").performClick()
        assertEquals(listOf(true), chosen)
    }

    @Test fun `while waiting there is a message about the 30 seconds and a way to cancel`() {
        var cancelled = 0
        content { LocationShareDialogs(LocationShareUi.Fetching(true), onChoose = {}, onCancel = { cancelled++ }, onSend = {}) }
        compose.onNodeWithText("Hämtar din position…").assertIsDisplayed()
        compose.onNodeWithText("Det kan ta upp till 30 sekunder.").assertIsDisplayed()
        compose.onNodeWithText("Avbryt").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun `the preview shows the coordinates and the accuracy and only sends on the button`() {
        val sent = ArrayList<LocationSpec>()
        var cancelled = 0
        content { LocationShareDialogs(LocationShareUi.Preview(spec), onChoose = {}, onCancel = { cancelled++ }, onSend = { sent += it }) }
        compose.onNodeWithText("Skicka den här positionen?").assertIsDisplayed()
        compose.onNodeWithText("59.329300, 18.068600").assertIsDisplayed()
        compose.onNodeWithText("Noggrannhet: ± 25 m").assertIsDisplayed()
        assertTrue(sent.isEmpty())
        compose.onNodeWithText("Avbryt").performClick()
        assertEquals(1, cancelled); assertTrue(sent.isEmpty())
        compose.onNodeWithText("Skicka").performClick()
        assertEquals(listOf(spec), sent)
    }

    @Test fun `no step, no dialog`() {
        content { LocationShareDialogs(null, onChoose = {}, onCancel = {}, onSend = {}) }
        compose.onAllNodesWithText("Dela din position").assertCountEquals(0)
    }

    // --- how a stored position becomes a row ------------------------------------------------------------------------------------

    private fun stored(id: String = "m", location: LocationSpec? = spec, deleted: Boolean = false, attachments: List<LocalAttachment> = emptyList(), text: String? = null, ts: Long = Fixtures.at(10, 24, 12), from: String = "bob", status: MessageStatus = MessageStatus.RECEIVED) =
        StoredMessage(id, "c", from, "d", from == "me", status, 5, ts, ts, text, null, attachments, deleted = deleted, location = location)

    private val ctx = ChatRowContext("me", false, emptyMap(), zone = ZoneId.of("UTC"))

    @Test fun `a stored position maps to a bubble that can be reacted to and starred but not edited, copied as text or forwarded`() {
        val m = assertIs<ChatRow.Message>(stored().toChatRow(ctx)).message
        assertEquals(LocationUi(59.3293, 18.0686, 25.0, null), m.location)
        assertFalse(m.canEdit); assertFalse(m.canForward)
        assertTrue(m.canReact && m.canStar)
        assertNull(assertIs<ChatRow.Message>(stored(deleted = true).toChatRow(ctx)).message.location, "a deleted message keeps no position")
    }

    // --- gallery models ----------------------------------------------------------------------------------------------------------

    private fun att(id: String, kind: String, name: String? = null, size: Long = 100) =
        LocalAttachment(id, "k", "s", size, if (kind == "image") "image/jpeg" else "application/pdf", name, kind, 10, 10, null, null, null, DownloadState.NONE)

    @Test fun `media cells are the photos and videos of a message, files only the documents, voice neither`() {
        val m = stored(location = null, attachments = listOf(att("a", "image"), att("b", "voice"), att("c", "file", "plan.pdf"), att("d", "video")))
        assertEquals(listOf("a", "d"), m.mediaCells().map { it.attachmentId })
        assertEquals(listOf(false, true), m.mediaCells().map { it.video })
        assertEquals(listOf("c"), m.fileItems().map { it.attachmentId })
        assertEquals(emptyList(), stored(location = null, deleted = true, attachments = listOf(att("a", "image"))).mediaCells())
    }

    @Test fun `links are the web addresses of the text, once each, in order, and nothing else`() {
        val m = stored(location = null, text = "Se https://exempel.se/a och *igen* https://exempel.se/a samt http://b.se/x?y=1, mejla kalle@exempel.se eller ring 070-123 45 67")
        assertEquals(listOf("https://exempel.se/a", "http://b.se/x?y=1"), m.linkItems().map { it.url })
        assertEquals(emptyList(), stored(location = null, text = "ingen länk här").linkItems())
        assertEquals(emptyList(), stored(location = null, deleted = true, text = "https://exempel.se").linkItems())
    }

    @Test fun `a month header goes above the first item of each month and at the top`() {
        val zone = ZoneId.of("UTC")
        fun cell(id: String, month: Int, day: Int) = GalleryItem.Cell(MediaCell("m$id", id, false, null, Fixtures.at(month, day, 12), null))
        val oct2 = cell("1", 10, 20); val oct1 = cell("2", 10, 2); val sep = cell("3", 9, 30)
        assertEquals(GalleryItem.Header(YearMonth.of(2026, 10)), monthHeaderBetween(null, oct2, zone))
        assertNull(monthHeaderBetween(oct2, oct1, zone), "same month, no header")
        assertEquals(GalleryItem.Header(YearMonth.of(2026, 9)), monthHeaderBetween(oct1, sep, zone))
        assertNull(monthHeaderBetween(sep, null, zone), "nothing after the last item")
        // the month is the one in the phone's time zone
        val late = GalleryItem.Cell(MediaCell("m", "x", false, null, Fixtures.at(9, 30, 23, 30), null))
        assertEquals(YearMonth.of(2026, 10), monthHeaderBetween(null, late, ZoneId.of("Europe/Stockholm"))!!.month)
    }

    // --- the gallery screen ------------------------------------------------------------------------------------------------------

    private fun cell(id: String, month: Int, day: Int, video: Boolean = false) =
        GalleryItem.Cell(MediaCell("msg-$id", "att-$id", video, if (video) Fixtures.videoThumb else Fixtures.photoThumb, Fixtures.at(month, day, 12), null))

    private val mediaItems: List<GalleryItem> = listOf(
        GalleryItem.Header(YearMonth.of(2026, 10)), cell("1", 10, 20), cell("2", 10, 12, video = true),
        GalleryItem.Header(YearMonth.of(2026, 9)), cell("3", 9, 30), cell("4", 9, 3),
    )
    private val fileItems = listOf(FileItem("f1", "fa1", "Hyreskontrakt.pdf", "application/pdf", 245_000, Fixtures.at(10, 20, 12)))
    private val linkItems = listOf(LinkItem("l1", "https://exempel.se/recept", Fixtures.at(10, 21, 12), outgoing = false))

    /** A page stream whose first load is done, as a Pager reports it. */
    private fun <T : Any> loaded(items: List<T>): PagingData<T> {
        val done = androidx.paging.LoadState.NotLoading(endOfPaginationReached = true)
        return PagingData.from(items, androidx.paging.LoadStates(refresh = androidx.paging.LoadState.NotLoading(false), prepend = done, append = done))
    }

    @Composable private fun Gallery(
        media: List<GalleryItem> = mediaItems, files: List<FileItem> = fileItems, links: List<LinkItem> = linkItems, tab: GalleryTab = GalleryTab.MEDIA,
        onMedia: (String, String) -> Unit = { _, _ -> }, onFile: (FileItem) -> Unit = {}, onLink: (String) -> Unit = {},
    ) = MediaGalleryContent(
        flowOf(loaded(media)).collectAsLazyPagingItems(), flowOf(loaded(files)).collectAsLazyPagingItems(),
        flowOf(loaded(links)).collectAsLazyPagingItems(), onBack = {}, onOpenMedia = onMedia, onOpenFile = onFile,
        onOpenLinkMessage = onLink, initialTab = tab,
    )

    @Test fun `the gallery has three tabs and starts on the photos grouped by month`() {
        content { Gallery() }
        compose.onNodeWithText("Media och filer").assertIsDisplayed()
        for (tab in listOf("Bilder och video", "Filer", "Länkar")) compose.onNodeWithText(tab).assertIsDisplayed()
        compose.onNodeWithText("oktober 2026", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("september 2026", ignoreCase = true).assertIsDisplayed()
        compose.onAllNodesWithContentDescriptionText("Foto").assertCountEquals(3)
        compose.onAllNodesWithContentDescriptionText("Video").assertCountEquals(1)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onAllNodesWithContentDescriptionText(text: String) =
        onAllNodes(hasContentDescription(text))

    @Test fun `tapping a photo opens the viewer at that photo`() {
        val opened = ArrayList<Pair<String, String>>()
        content { Gallery(onMedia = { m, a -> opened += m to a }) }
        compose.onAllNodesWithContentDescriptionText("Video").onFirst().performClick()
        assertEquals(listOf("msg-2" to "att-2"), opened)
    }

    @Test fun `the files tab lists documents with their size and a tap opens one`() {
        val opened = ArrayList<FileItem>()
        content { Gallery(onFile = { opened += it }) }
        compose.onNodeWithText("Filer").performClick()
        compose.onNodeWithText("Hyreskontrakt.pdf").assertIsDisplayed()
        compose.onNodeWithText("Hyreskontrakt.pdf").performClick()
        assertEquals(fileItems, opened)
    }

    @Test fun `the links tab lists web addresses and a tap shows the message they were in`() {
        val shown = ArrayList<String>()
        content { Gallery(onLink = { shown += it }) }
        compose.onNodeWithText("Länkar").performClick()
        compose.onNodeWithText("https://exempel.se/recept").assertIsDisplayed()
        compose.onNodeWithText("https://exempel.se/recept").performClick()
        assertEquals(listOf("l1"), shown)
    }

    @Test fun `an empty tab says so`() {
        content { Gallery(media = emptyList(), files = emptyList(), links = emptyList()) }
        compose.onNodeWithText("Inga bilder eller videor i den här konversationen ännu.").assertIsDisplayed()
        compose.onNodeWithText("Filer").performClick()
        compose.onNodeWithText("Inga filer i den här konversationen ännu.").assertIsDisplayed()
        compose.onNodeWithText("Länkar").performClick()
        compose.onNodeWithText("Inga länkar i den här konversationen ännu.").assertIsDisplayed()
    }

    @Test fun `a deleted message leaves the grid and an emptied month loses its header`() {
        val source = MutableStateFlow(loaded(mediaItems))
        content {
            MediaGalleryContent(
                source.collectAsLazyPagingItems(), flowOf(loaded(fileItems)).collectAsLazyPagingItems(),
                flowOf(loaded(linkItems)).collectAsLazyPagingItems(), onBack = {}, onOpenMedia = { _, _ -> }, onOpenFile = {}, onOpenLinkMessage = {},
            )
        }
        compose.onAllNodesWithContentDescriptionText("Foto").assertCountEquals(3)
        // the two photos of September are deleted: the list is rebuilt without them (Room invalidates the Paging source)
        source.value = loaded(mediaItems.take(3))
        compose.waitForIdle()
        compose.onAllNodesWithContentDescriptionText("Foto").assertCountEquals(1)
        compose.onAllNodesWithText("september 2026", ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithText("oktober 2026", ignoreCase = true).assertIsDisplayed()
    }

    // --- the camera -------------------------------------------------------------------------------------------------------------

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val preparer get() = AttachmentPreparer(app)

    // FileProvider keeps the folders it serves in a static cache; every Robolectric test has a new data folder
    @Before fun forgetFileProviderRoots() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @After fun cleanCameraFiles() {
        preparer.stagingDir.deleteRecursively()
        preparer.outgoingDir.deleteRecursively()
    }

    /** The system's camera app and permission dialog, played by the test. */
    private class FakeSystem(val grantPermission: Boolean = true, val takePicture: (Uri) -> Boolean = { true }) : ActivityResultRegistry() {
        val launched = ArrayList<String>()

        @Suppress("UNCHECKED_CAST")
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            when (contract) {
                is ActivityResultContracts.RequestPermission -> { launched += "permission:$input"; dispatchResult(requestCode, grantPermission as O) }
                is ActivityResultContracts.TakePicture -> { launched += "photo"; dispatchResult(requestCode, takePicture(input as Uri) as O) }
                is ActivityResultContracts.CaptureVideo -> { launched += "video"; dispatchResult(requestCode, takePicture(input as Uri) as O) }
                else -> error("unexpected $contract")
            }
        }
    }

    private class CameraHost(
        val system: FakeSystem, val preparer: AttachmentPreparer,
        val captured: MutableList<Uri> = ArrayList(), val discarded: MutableList<Uri> = ArrayList(), var denied: Int = 0,
        val targets: MutableList<CaptureTarget> = ArrayList(),
    )

    @Composable private fun CameraButtons(host: CameraHost) {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = host.system }
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            val camera = rememberCameraActions(
                newTarget = { video -> host.preparer.newCaptureTarget(video).also { host.targets += it } },
                discard = { host.discarded += it }, onCaptured = { host.captured += it }, onPermissionDenied = { host.denied++ },
            )
            Column {
                Button(onClick = camera.takePhoto) { Text("Ta bild") }
                Button(onClick = camera.recordVideo) { Text("Spela in") }
            }
        }
    }

    @Test fun `a photo is taken into a private staging file and handed on once the camera app says yes`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val host = CameraHost(FakeSystem(), preparer)
        content { CameraButtons(host) }
        compose.onNodeWithText("Ta bild").performClick()
        assertEquals(listOf("photo"), host.system.launched, "CAMERA is held, so no permission dialog")
        val target = host.targets.single()
        assertEquals(listOf(target.stagedUri), host.captured)
        assertEquals("file", target.stagedUri.scheme)
        assertTrue(target.file.canonicalPath.startsWith(preparer.stagingDir.canonicalPath), "inside the app's private staging folder")
        assertEquals("content", target.providerUri.scheme, "the camera app gets a FileProvider uri, not a path")
        assertTrue(host.discarded.isEmpty())
    }

    @Test fun `without the camera permission it is asked for first, and a no starts nothing`() {
        val host = CameraHost(FakeSystem(grantPermission = false), preparer)
        content { CameraButtons(host) }
        compose.onNodeWithText("Ta bild").performClick()
        assertEquals(listOf("permission:${Manifest.permission.CAMERA}"), host.system.launched)
        assertEquals(1, host.denied)
        assertTrue(host.captured.isEmpty() && host.targets.isEmpty(), "no file was prepared for a refused permission")
    }

    @Test fun `once the permission is granted the capture follows, for video too`() {
        val host = CameraHost(FakeSystem(grantPermission = true), preparer)
        content { CameraButtons(host) }
        compose.onNodeWithText("Spela in").performClick()
        assertEquals(listOf("permission:${Manifest.permission.CAMERA}", "video"), host.system.launched)
        assertEquals("video.mp4", host.targets.single().file.name)
        assertEquals(listOf(host.targets.single().stagedUri), host.captured)
    }

    @Test fun `backing out of the camera app discards the empty file`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val host = CameraHost(FakeSystem(takePicture = { false }), preparer)
        content { CameraButtons(host) }
        compose.onNodeWithText("Ta bild").performClick()
        assertTrue(host.captured.isEmpty())
        assertEquals(listOf(host.targets.single().stagedUri), host.discarded)
    }

    private fun writeJpegWithGps(file: File) {
        val bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        androidx.exifinterface.media.ExifInterface(file.path).apply {
            setLatLong(59.3293, 18.0686)
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, "Fairphone")
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:24 14:30:00")
            saveAttributes()
        }
    }

    @Test fun `a captured photo reaches the preview strip as a photo, and what is prepared from it carries no location`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val hostRef = arrayOfNulls<CameraHost>(1)
        val system = FakeSystem(takePicture = { writeJpegWithGps(hostRef[0]!!.targets.last().file); true })
        val host = CameraHost(system, preparer).also { hostRef[0] = it }
        val staged = mutableStateListOf<StagedUi>()
        content { Column { CameraButtons(host); StagedStrip(staged, onRemove = {}) } }
        compose.onNodeWithText("Ta bild").performClick()
        val captured = host.captured.single()
        // the camera's own file does carry the position: this is what must not go out
        assertNotNull(androidx.exifinterface.media.ExifInterface(host.targets.last().file.path).latLong)
        // what the view model does next: describe it and put it in the preview strip
        val picked = kotlinx.coroutines.runBlocking { preparer.describe(captured) }
        assertEquals("image", picked.kind)
        staged += StagedUi("1", picked.uri, se.swahnchat.app.ui.model.attachmentKindOf(picked.kind), picked.name)
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Valda filer, 1 av 10")).assertIsDisplayed()
        // and when it is sent
        val out = kotlinx.coroutines.runBlocking { preparer.prepare(picked.uri) }
        val exif = androidx.exifinterface.media.ExifInterface(out.file.path)
        assertNull(exif.latLong); assertNull(exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL))
        // removing it from the strip deletes the staged copy and its folder
        preparer.deleteStagedCopy(captured)
        assertFalse(host.targets.last().file.parentFile!!.exists())
    }

    // --- video options ----------------------------------------------------------------------------------------------------------

    @Test fun `with a video in the strip the original-quality switch is offered, without one it is not`() {
        val changes = ArrayList<Boolean>()
        content { Column { VideoSendOptions(hasVideo = true, original = false, compression = null, onOriginal = { changes += it }, onCancel = {}) } }
        compose.onNodeWithContentDescription("Skicka i originalkvalitet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Skicka i originalkvalitet").performClick()
        assertEquals(listOf(true), changes)
    }

    @Test fun `no video, no options`() {
        content { Column { VideoSendOptions(hasVideo = false, original = false, compression = null, onOriginal = {}, onCancel = {}) } }
        compose.onAllNodes(hasContentDescription("Skicka i originalkvalitet")).assertCountEquals(0)
    }

    @Test fun `while compressing the progress is shown with a cancel button instead of the switch`() {
        var cancelled = 0
        content { VideoSendOptions(hasVideo = true, original = false, compression = CompressionUi(0.42f, 1, 1), onOriginal = {}, onCancel = { cancelled++ }) }
        compose.onNodeWithText("Komprimerar video… 42 %").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Skicka i originalkvalitet")).assertCountEquals(0)
        compose.onNodeWithText("Avbryt").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun `with several videos the progress says which one`() {
        content { VideoSendOptions(hasVideo = true, original = false, compression = CompressionUi(0.5f, 2, 3), onOriginal = {}, onCancel = {}) }
        compose.onNodeWithText("Komprimerar video 2 av 3… 50 %").assertIsDisplayed()
    }
}
