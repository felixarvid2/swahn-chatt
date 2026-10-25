package se.swahnchat.app.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import se.swahnchat.app.R

/** What the scanner dialog can do right now. */
private enum class CameraState { ASK, DENIED, UNAVAILABLE, READY }

/** True if a camera is built in or attached; without one the dialog says so instead of asking for a permission. */
private fun hasCamera(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

private fun cameraGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * A full screen camera view that reads one QR code and hands its text to [onResult] (once). Whatever it returns is
 * untrusted input: the caller must check it. The camera permission is asked for only now, when the person chose to scan; if
 * it is refused the dialog explains and offers the system settings. No Google Play services are used (zxing's own decoder).
 */
@Composable
fun QrScannerDialog(title: String, hint: String, onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var state by remember {
        mutableStateOf(
            when {
                !hasCamera(context) -> CameraState.UNAVAILABLE
                cameraGranted(context) -> CameraState.READY
                else -> CameraState.ASK
            },
        )
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state = if (granted) CameraState.READY else CameraState.DENIED
    }
    LaunchedEffect(state) { if (state == CameraState.ASK) request.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }
                Text(hint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (state) {
                        CameraState.READY -> CameraPreview(onResult)
                        CameraState.ASK -> Unit
                        CameraState.DENIED -> Explanation(
                            R.string.scan_camera_denied,
                            action = R.string.scan_camera_retry to { request.launch(Manifest.permission.CAMERA) },
                            second = R.string.scan_camera_settings to { openAppSettings(context) },
                        )
                        CameraState.UNAVAILABLE -> Explanation(R.string.scan_no_camera)
                    }
                }
            }
        }
    }
}

@Composable
private fun Explanation(text: Int, action: Pair<Int, () -> Unit>? = null, second: Pair<Int, () -> Unit>? = null) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        action?.let { (label, run) -> Button(onClick = run, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(label)) } }
        second?.let { (label, run) -> TextButton(onClick = run, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(label)) } }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val deliver = rememberUpdatedState(onResult)
    var done by remember { mutableStateOf(false) }
    val view = remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    DisposableEffect(lifecycle, view.value) {
        val v = view.value
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> v?.resume()
                Lifecycle.Event.ON_PAUSE -> v?.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (v != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) v.resume()
        onDispose {
            lifecycle.removeObserver(observer)
            v?.pause()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            DecoratedBarcodeView(ctx).also { v ->
                v.barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                v.setStatusText("")
                v.decodeSingle(
                    BarcodeCallback { result ->
                        val text = result.text
                        if (!done && text != null) {
                            done = true
                            deliver.value(text)
                        }
                    },
                )
                view.value = v
            }
        },
    )
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
