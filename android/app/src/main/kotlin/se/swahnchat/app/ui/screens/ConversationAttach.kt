package se.swahnchat.app.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import se.swahnchat.app.R
import se.swahnchat.app.location.geoUri
import se.swahnchat.app.ui.components.LocationShareDialogs
import se.swahnchat.app.ui.components.rememberCameraActions

/** The attach menu's camera and position entries (the photo/video picker and documents stay in [ConversationCallbacks]). */
class AttachCallbacks(val onTakePhoto: () -> Unit = {}, val onRecordVideo: () -> Unit = {}, val onSharePosition: () -> Unit = {})

/**
 * Camera and position for one conversation: launches the system camera app into a private file, asks for the permissions only when
 * they are needed (CAMERA before the first capture; location approximate first, exact only after "Exakt position"), and shows the
 * dialogs of the position flow. Everything lands in the view model: a photo or video in the preview strip, a position as a message
 * only after the person pressed "Skicka" in the preview.
 */
@Composable
internal fun rememberAttachCallbacks(vm: ConversationViewModel, locationShare: se.swahnchat.app.ui.components.LocationShareUi?): AttachCallbacks {
    val camera = rememberCameraActions(
        newTarget = vm::newCaptureTarget,
        discard = vm::discardCapture,
        onCaptured = { vm.attach(listOf(it)) },
        onPermissionDenied = { vm.notice(R.string.error_camera_permission) },
    )
    val context = LocalContext.current
    val currentVm by rememberUpdatedState(vm)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val fine = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        when {
            fine -> currentVm.fetchLocation(true)
            // "approximate" was granted (Android 12 lets the person downgrade an exact request): go on, the preview shows the accuracy
            coarse -> currentVm.fetchLocation(false)
            else -> currentVm.locationPermissionDenied()
        }
    }
    LocationShareDialogs(
        state = locationShare,
        onChoose = { precise ->
            val needed = if (precise) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (locationPermissionHeld(context, precise)) vm.fetchLocation(precise) else permissions.launch(needed)
        },
        onCancel = vm::cancelLocation,
        onSend = vm::sendLocation,
    )
    return AttachCallbacks(camera.takePhoto, camera.recordVideo, vm::openLocation)
}

/** True if what [precise] needs is granted already: exact needs FINE, approximate is served by either. */
internal fun locationPermissionHeld(context: Context, precise: Boolean): Boolean {
    fun held(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return if (precise) held(Manifest.permission.ACCESS_FINE_LOCATION)
    else held(Manifest.permission.ACCESS_COARSE_LOCATION) || held(Manifest.permission.ACCESS_FINE_LOCATION)
}

/** Hands a confirmed position to the system chooser as a `geo:` URI; the map app the person picks gets to see it. */
internal fun openInMapApp(context: Context, lat: Double, lon: Double, label: String?) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(lat, lon, label)))
    try {
        context.startActivity(Intent.createChooser(view, context.getString(R.string.location_chooser_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.location_no_map_app, Toast.LENGTH_LONG).show()
    }
}
