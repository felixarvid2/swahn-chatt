package se.familychat.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.familychat.app.qr.QrCode

/**
 * [text] as a QR code: black modules on white whatever the theme (scanners expect that), crisp edges, [description] for
 * screen readers (the code itself cannot be described, so say what it is for).
 */
@Composable
fun QrImage(text: String, description: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) { QrCode.bitmap(text, moduleSize = 8).asImageBitmap() }
    Image(
        bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(4.dp).semantics { contentDescription = description },
    )
}
