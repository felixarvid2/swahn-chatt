package se.swahnchat.app.call

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * A [SurfaceViewRenderer] that shows [track], on the shared [egl] context of the media factory (never a context of its own).
 * The renderer is released when it leaves the composition or when the track changes; a track that the session disposed
 * meanwhile is not touched (removing a sink from a disposed track throws). [mirror] flips the picture (the preview of the front
 * camera); [overlay] puts it above the other surface (the small preview over the full-screen video).
 */
@Composable
fun VideoRenderer(track: VideoTrack, egl: EglBase.Context, mirror: Boolean, overlay: Boolean, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context: Context ->
            SurfaceViewRenderer(context).apply {
                init(egl, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setZOrderMediaOverlay(overlay)
                view = this
            }
        },
        update = { it.setMirror(mirror) },
    )
    val renderer = view
    if (renderer != null) {
        DisposableEffect(renderer, track) {
            track.addSink(renderer)
            onDispose {
                runCatching { track.removeSink(renderer) } // the session may have disposed the track already
            }
        }
        DisposableEffect(renderer) { onDispose { renderer.release() } }
    }
}
