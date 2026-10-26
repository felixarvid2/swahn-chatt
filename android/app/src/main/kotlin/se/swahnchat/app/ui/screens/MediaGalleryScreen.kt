package se.swahnchat.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.flatMap
import androidx.paging.insertSeparators
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.swahnchat.app.R
import se.swahnchat.app.data.ChatRepository
import se.swahnchat.app.data.db.toModel
import se.swahnchat.app.media.MediaValidator
import se.swahnchat.app.ui.components.currentLocale
import se.swahnchat.app.ui.components.formatSize
import se.swahnchat.app.ui.model.FileItem
import se.swahnchat.app.ui.model.GalleryItem
import se.swahnchat.app.ui.model.LinkItem
import se.swahnchat.app.ui.model.fileItems
import se.swahnchat.app.ui.model.linkItems
import se.swahnchat.app.ui.model.mediaCells
import se.swahnchat.app.ui.model.monthHeaderBetween
import se.swahnchat.core.client.DownloadState
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.client.SwahnChatEngine
import java.io.File
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

/**
 * "Media och filer" of one conversation: what is in the local database, nothing from the server. Photos and videos (newest first,
 * by month), documents, and the web addresses found in the text of messages. Each list is a Paging source over this
 * conversation's messages, so a large chat is read page by page, and a message that is deleted or expires leaves the lists at once.
 */
@HiltViewModel
class MediaGalleryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ChatRepository,
    private val engine: SwahnChatEngine,
    private val store: LocalStore,
) : ViewModel() {
    private val conversationId: String = checkNotNull(savedState["conversationId"])
    private val zone: ZoneId = ZoneId.systemDefault()
    private val config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)

    val media: Flow<PagingData<GalleryItem>> = Pager(config) { repo.pagedMedia(conversationId) }.flow
        .map { data ->
            data.map { it.toModel() }.flatMap { m -> m.mediaCells().map<_, GalleryItem> { GalleryItem.Cell(it) } }
                .insertSeparators { before, after -> monthHeaderBetween(before, after, zone) }
        }
        .cachedIn(viewModelScope)

    val files: Flow<PagingData<FileItem>> = Pager(config) { repo.pagedFiles(conversationId) }.flow
        .map { data -> data.map { it.toModel() }.flatMap { it.fileItems() } }
        .cachedIn(viewModelScope)

    val links: Flow<PagingData<LinkItem>> = Pager(config) { repo.pagedLinks(conversationId) }.flow
        .map { data -> data.map { it.toModel() }.flatMap { it.linkItems() } }
        .cachedIn(viewModelScope)

    private val _effects = MutableSharedFlow<GalleryEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<GalleryEffect> = _effects.asSharedFlow()

    /** Hands a document to another app (downloaded and checked first if it is not on this phone yet). */
    fun open(item: FileItem) {
        viewModelScope.launch {
            try {
                val attachment = store.getMessage(item.messageId)?.attachments?.firstOrNull { it.id == item.attachmentId }
                val local = attachment?.localPath?.let(::File)?.takeIf { attachment.downloadState == DownloadState.DONE && it.isFile }
                val file = local ?: engine.downloadAttachment(item.messageId, item.attachmentId)
                val ok = withContext(Dispatchers.Default) { MediaValidator.isValid(file, "file") }
                _effects.tryEmit(if (ok) GalleryEffect.OpenFile(file.absolutePath, item.mime, item.name) else GalleryEffect.Failed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _effects.tryEmit(GalleryEffect.Failed)
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 60
    }
}

sealed interface GalleryEffect {
    data class OpenFile(val path: String, val mime: String, val name: String?) : GalleryEffect
    data object Failed : GalleryEffect
}

enum class GalleryTab { MEDIA, FILES, LINKS }

@Composable
fun MediaGalleryScreen(
    onBack: () -> Unit,
    onOpenMedia: (messageId: String, attachmentId: String) -> Unit,
    onOpenLinkMessage: (messageId: String) -> Unit,
    vm: MediaGalleryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is GalleryEffect.OpenFile -> openFile(context, effect.path, effect.mime, effect.name)
                GalleryEffect.Failed -> snackbar.showSnackbar(resources.getString(R.string.attachment_invalid))
            }
        }
    }
    MediaGalleryContent(
        media = vm.media.collectAsLazyPagingItems(),
        files = vm.files.collectAsLazyPagingItems(),
        links = vm.links.collectAsLazyPagingItems(),
        onBack = onBack,
        onOpenMedia = onOpenMedia,
        onOpenFile = vm::open,
        onOpenLinkMessage = onOpenLinkMessage,
        snackbarHost = { SnackbarHost(snackbar) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGalleryContent(
    media: LazyPagingItems<GalleryItem>,
    files: LazyPagingItems<FileItem>,
    links: LazyPagingItems<LinkItem>,
    onBack: () -> Unit,
    onOpenMedia: (messageId: String, attachmentId: String) -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onOpenLinkMessage: (messageId: String) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: GalleryTab = GalleryTab.MEDIA,
    snackbarHost: @Composable () -> Unit = {},
) {
    var selected by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    Scaffold(
        modifier = modifier,
        snackbarHost = snackbarHost,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.menu_media_files)) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
                TabRow(selectedTabIndex = selected) {
                    listOf(R.string.gallery_tab_media, R.string.gallery_tab_files, R.string.gallery_tab_links).forEachIndexed { i, label ->
                        Tab(
                            selected = selected == i, onClick = { selected = i },
                            text = { Text(stringResource(label), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (GalleryTab.entries[selected]) {
                GalleryTab.MEDIA -> MediaGrid(media, onOpenMedia)
                GalleryTab.FILES -> FileList(files, onOpenFile)
                GalleryTab.LINKS -> LinkList(links, onOpenLinkMessage)
            }
        }
    }
}

@Composable
private fun EmptyOrLoading(loading: Boolean, empty: Boolean, emptyText: Int, content: @Composable () -> Unit) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        empty -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(emptyText), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> content()
    }
}

@Composable
private fun <T : Any> LazyPagingItems<T>.isInitialLoading() =
    itemCount == 0 && loadState.refresh is androidx.paging.LoadState.Loading

@Composable
private fun <T : Any> LazyPagingItems<T>.isEmptyAndLoaded() =
    itemCount == 0 && loadState.refresh is androidx.paging.LoadState.NotLoading

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(items: LazyPagingItems<GalleryItem>, onOpen: (String, String) -> Unit) {
    EmptyOrLoading(items.isInitialLoading(), items.isEmptyAndLoaded(), R.string.gallery_empty_media) {
        val locale = currentLocale()
        val monthFormat = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = items.itemCount,
                key = { index -> items.peek(index)?.key ?: "i$index" },
                span = { index -> if (items.peek(index) is GalleryItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { index -> items.peek(index)?.let { it::class } },
            ) { index ->
                when (val item = items[index]) {
                    is GalleryItem.Header -> MonthHeader(monthLabel(item.month, monthFormat))
                    is GalleryItem.Cell -> MediaTile(item, onOpen)
                    null -> Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}

private fun monthLabel(month: YearMonth, format: DateTimeFormatter): String =
    month.format(format).replaceFirstChar { it.titlecase(Locale.getDefault()) }

@Composable
private fun MonthHeader(label: String) {
    Text(
        label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun MediaTile(item: GalleryItem.Cell, onOpen: (String, String) -> Unit) {
    val cell = item.cell
    val bitmap = remember(cell.thumbnail) { cell.thumbnail?.let { decodeSmallJpeg(it) } }
    val description = stringResource(if (cell.video) R.string.attachment_video else R.string.attachment_photo)
    Box(
        Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClickLabel = description) { onOpen(cell.messageId, cell.attachmentId) }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (cell.video) {
            Icon(Icons.Filled.PlayCircle, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

/** A thumbnail from a message is untrusted input: only a small JPEG is decoded. */
private fun decodeSmallJpeg(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024 && bounds.outMimeType == "image/jpeg") {
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } else {
        null
    }
} catch (e: Exception) {
    null
}

@Composable
private fun FileList(items: LazyPagingItems<FileItem>, onOpen: (FileItem) -> Unit) {
    EmptyOrLoading(items.isInitialLoading(), items.isEmptyAndLoaded(), R.string.gallery_empty_files) {
        val date = rememberDateFormatter()
        LazyColumn(Modifier.fillMaxSize()) {
            items(count = items.itemCount, key = items.itemKey { it.key }) { index ->
                val item = items[index]
                if (item != null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { onOpen(item) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(32.dp))
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(item.name ?: stringResource(R.string.attachment_file), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${formatSize(item.size)} · ${date(item.time)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LinkList(items: LazyPagingItems<LinkItem>, onOpenMessage: (String) -> Unit) {
    EmptyOrLoading(items.isInitialLoading(), items.isEmptyAndLoaded(), R.string.gallery_empty_links) {
        val date = rememberDateFormatter()
        LazyColumn(Modifier.fillMaxSize()) {
            items(count = items.itemCount, key = items.itemKey { it.key }) { index ->
                val item = items[index]
                if (item != null) {
                    val open = stringResource(R.string.gallery_link_show_message)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = open) { onOpenMessage(item.messageId) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(32.dp))
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(item.url, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "${stringResource(if (item.outgoing) R.string.you else R.string.gallery_link_received)} · ${date(item.time)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun rememberDateFormatter(): (Long) -> String {
    val locale = currentLocale()
    val format = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    return { time -> java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().format(format) }
}
