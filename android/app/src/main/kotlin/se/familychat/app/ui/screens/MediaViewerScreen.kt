package se.familychat.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.familychat.app.R
import se.familychat.app.media.MediaValidator
import se.familychat.core.client.DownloadState
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.LocalStore
import java.io.File
import javax.inject.Inject

sealed interface MediaViewerState {
    data object Loading : MediaViewerState
    data object Failed : MediaViewerState

    /** The file passed validation as the kind the message claimed. */
    data class Ready(val kind: String, val file: File, val mime: String, val name: String) : MediaViewerState
}

/** The photos and videos of one message, in the order of the message; [start] is the one that was tapped. */
data class MediaPages(val attachmentIds: List<String>, val start: Int)

@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val engine: FamilyChatEngine,
    private val store: LocalStore,
) : ViewModel() {
    private val messageId: String = checkNotNull(savedState["messageId"])
    private val attachmentId: String = checkNotNull(savedState["attachmentId"])
    private val _pages = MutableStateFlow<MediaPages?>(null)
    val pages: StateFlow<MediaPages?> = _pages.asStateFlow()
    private val _states = MutableStateFlow<Map<String, MediaViewerState>>(emptyMap())

    /** Per photo or video: loading, failed, or ready. A page loads when it is first shown. */
    val states: StateFlow<Map<String, MediaViewerState>> = _states.asStateFlow()
    private val started = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        viewModelScope.launch {
            val media = store.getMessage(messageId)?.attachments.orEmpty().filter { it.kind == "image" || it.kind == "video" }
            val ids = media.map { it.id }.ifEmpty { listOf(attachmentId) }
            _pages.value = MediaPages(ids, ids.indexOf(attachmentId).coerceAtLeast(0))
        }
    }

    /** Fetches (if need be), decrypts and checks the attachment shown on a page. */
    fun load(id: String) {
        if (!started.add(id)) return
        viewModelScope.launch {
            val state = try {
                val attachment = store.getMessage(messageId)?.attachments?.firstOrNull { it.id == id }
                if (attachment == null) {
                    MediaViewerState.Failed
                } else {
                    val file = if (attachment.downloadState == DownloadState.DONE && attachment.localPath != null && File(attachment.localPath!!).isFile) {
                        File(attachment.localPath!!)
                    } else {
                        engine.downloadAttachment(messageId, id)
                    }
                    val ok = withContext(Dispatchers.Default) { MediaValidator.isValid(file, attachment.kind) }
                    if (ok) MediaViewerState.Ready(attachment.kind, file, attachment.mime, attachment.name ?: file.name) else MediaViewerState.Failed
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                MediaViewerState.Failed
            }
            _states.value = _states.value + (id to state)
        }
    }

    /** Copies the file to the document the user picked in the system file chooser. */
    fun saveTo(context: Context, source: File, target: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(target)?.use { out -> source.inputStream().use { it.copyTo(out) } }
            } catch (e: Exception) {
                // the chooser reports its own errors; nothing sensible to add
            }
        }
    }
}

@Composable
fun MediaViewerScreen(onBack: () -> Unit, vm: MediaViewerViewModel = hiltViewModel()) {
    val pages by vm.pages.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingSave
        if (uri != null && file != null) vm.saveTo(context, file, uri)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val p = pages
        if (p == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        } else {
            val pager = rememberPagerState(initialPage = p.start, pageCount = { p.attachmentIds.size })
            HorizontalPager(pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0, key = { p.attachmentIds[it] }) { page ->
                val id = p.attachmentIds[page]
                LaunchedEffect(id) { vm.load(id) }
                MediaPage(states[id] ?: MediaViewerState.Loading, active = pager.currentPage == page)
            }
            val current = states[p.attachmentIds[pager.currentPage]] as? MediaViewerState.Ready
            if (p.attachmentIds.size > 1) {
                Text(
                    stringResource(R.string.media_position, pager.currentPage + 1, p.attachmentIds.size), color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.statusBarsPadding().padding(top = 20.dp).align(Alignment.TopCenter),
                )
            }
            current?.let { ready ->
                Row(Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopEnd)) {
                    ViewerButton({
                        pendingSave = ready.file
                        save.launch(ready.name)
                    }) { Icon(Icons.Filled.Download, stringResource(R.string.action_save_to_device)) }
                    ViewerButton({ openFile(context, ready.file.absolutePath, ready.mime, ready.name) }) { Icon(Icons.Filled.Share, stringResource(R.string.action_share)) }
                }
            }
        }
        Row(Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart)) {
            ViewerButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
        }
    }
}

@Composable
private fun MediaPage(state: MediaViewerState, active: Boolean) {
    Box(Modifier.fillMaxSize()) {
        when (state) {
            MediaViewerState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            MediaViewerState.Failed -> Column(Modifier.align(Alignment.Center)) {
                Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally))
                Text(stringResource(R.string.attachment_invalid), color = Color.White, modifier = Modifier.padding(top = 8.dp))
            }
            is MediaViewerState.Ready -> if (state.kind == "video") {
                if (active) VideoPlayer(state.file)
            } else {
                ZoomableImage(state.file)
            }
        }
    }
}

@Composable
private fun ViewerButton(onClick: () -> Unit, icon: @Composable () -> Unit) {
    IconButton(
        onClick = onClick, modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White),
    ) { icon() }
}

@Composable
private fun ZoomableImage(file: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                // Pinch to zoom, and pan while zoomed. A single finger on a picture that is not zoomed is left alone
                // (not consumed), so the pager can take it and swipe to the next picture.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            },
    ) {
        AsyncImage(
            model = file, contentDescription = stringResource(R.string.attachment_photo), contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}

@Composable
private fun VideoPlayer(file: File) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
}
