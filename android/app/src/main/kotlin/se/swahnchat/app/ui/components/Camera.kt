package se.swahnchat.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import se.swahnchat.app.media.CaptureTarget

/** "Kamera" and "Spela in video" in the attach menu. */
class CameraActions(val takePhoto: () -> Unit, val recordVideo: () -> Unit)

/**
 * Photo and video from the chat through the system camera app (`ACTION_IMAGE_CAPTURE` / `ACTION_VIDEO_CAPTURE`).
 *
 * - The app declares CAMERA in its manifest (for the QR scanner), and the platform then makes the app hold it before it may start the
 *   camera app: it is asked for here, right before the first launch.
 * - The camera app writes into [newTarget]'s file, a FileProvider URI inside the app's private staging folder, never shared storage.
 *   On success [onCaptured] gets the staged `file:` URI (the same kind of URI the share target stages), which the caller puts in the
 *   preview strip; if the person backs out the empty file is handed to [discard].
 * - The file's name is kept across a configuration change while the camera app is open (a rotation recreates the activity).
 */
@Composable
fun rememberCameraActions(
    newTarget: (video: Boolean) -> CaptureTarget,
    discard: (Uri) -> Unit,
    onCaptured: (Uri) -> Unit,
    onPermissionDenied: () -> Unit,
): CameraActions {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var wantVideo by rememberSaveable { mutableStateOf(false) }

    fun finish(ok: Boolean) {
        val staged = pending?.let(Uri::parse)
        pending = null
        if (staged == null) return
        if (ok) onCaptured(staged) else discard(staged)
    }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { finish(it) }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { finish(it) }

    fun launch(forVideo: Boolean) {
        val target = newTarget(forVideo)
        pending = target.stagedUri.toString()
        if (forVideo) video.launch(target.providerUri) else photo.launch(target.providerUri)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launch(wantVideo) else onPermissionDenied()
    }

    fun start(forVideo: Boolean) {
        wantVideo = forVideo
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launch(forVideo)
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    return CameraActions(takePhoto = { start(false) }, recordVideo = { start(true) })
}
