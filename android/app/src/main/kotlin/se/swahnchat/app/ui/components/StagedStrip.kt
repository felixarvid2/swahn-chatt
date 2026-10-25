package se.swahnchat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import se.swahnchat.app.R
import se.swahnchat.app.ui.model.AttachmentKindUi
import se.swahnchat.app.ui.screens.CompressionUi
import se.swahnchat.app.ui.screens.StagedUi
import kotlin.math.roundToInt

/**
 * The files chosen for the next message, above the text field: a small preview each, with a button to take it out again.
 * Up to ten; they are sent together as one message with the text as caption.
 */
@Composable
fun StagedStrip(items: List<StagedUi>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val summary = stringResource(R.string.staged_strip_description, items.size)
    LazyRow(
        modifier.semantics { contentDescription = summary }.padding(top = 8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item -> StagedTile(item, onRemove) }
    }
}

@Composable
private fun StagedTile(item: StagedUi, onRemove: (String) -> Unit) {
    val name = item.name ?: stringResource(R.string.attachment_file)
    Box(Modifier.size(width = 80.dp, height = 80.dp)) {
        Box(
            Modifier.padding(top = 8.dp, end = 8.dp).size(72.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when (item.kind) {
                AttachmentKindUi.IMAGE ->
                    AsyncImage(model = item.uri, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp))
                AttachmentKindUi.VIDEO -> Icon(Icons.Filled.PlayCircle, contentDescription = name, modifier = Modifier.size(32.dp))
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // a 48 dp touch target around a small round button in the corner
        val remove = stringResource(R.string.action_remove_staged, name)
        Box(
            Modifier.align(Alignment.TopEnd).size(48.dp).clickable(role = Role.Button, onClickLabel = remove) { onRemove(item.id) }
                .semantics { contentDescription = remove },
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.inverseSurface), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * Under the preview strip while a video is staged: the "Skicka i originalkvalitet" switch (videos above 16 MB or 1080p are otherwise
 * shrunk to 720p before they are uploaded), and, while that happens, the progress with a button to stop it.
 */
@Composable
fun VideoSendOptions(
    hasVideo: Boolean,
    original: Boolean,
    compression: CompressionUi?,
    onOriginal: (Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!hasVideo) return
    Column(modifier.padding(horizontal = 16.dp)) {
        if (compression != null) {
            val percent = (compression.progress * 100).roundToInt()
            val label = if (compression.count > 1) {
                stringResource(R.string.video_compressing_n, compression.index, compression.count, percent)
            } else {
                stringResource(R.string.video_compressing, percent)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(progress = { compression.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
            }
        } else {
            val toggleLabel = stringResource(R.string.video_send_original)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = original, role = Role.Switch, onValueChange = onOriginal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(toggleLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).clearAndSetSemantics {})
                Switch(checked = original, onCheckedChange = null, modifier = Modifier.semantics { contentDescription = toggleLabel })
            }
        }
    }
}
