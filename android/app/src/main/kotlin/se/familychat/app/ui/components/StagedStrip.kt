package se.familychat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import se.familychat.app.R
import se.familychat.app.ui.model.AttachmentKindUi
import se.familychat.app.ui.screens.StagedUi

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
