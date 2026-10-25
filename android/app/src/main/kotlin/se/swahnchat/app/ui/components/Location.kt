package se.swahnchat.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.swahnchat.app.R
import se.swahnchat.app.location.formatAccuracy
import se.swahnchat.app.location.formatCoordinates
import se.swahnchat.app.ui.model.LocationUi
import se.swahnchat.core.model.LocationSpec

/**
 * The body of a bubble that holds a shared position. Nothing is loaded from the internet (a map tile request would tell its
 * provider where the people in this conversation are): the "map" is drawn here, a plain grid with a pin, and the numbers are
 * the position. Opening it in a map app goes through a confirmation (see [OpenInMapDialog]).
 */
@Composable
fun LocationBubbleBody(location: LocationUi, content: Color, onOpen: () -> Unit, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val coordinates = formatCoordinates(location.lat, location.lon)
    Column(modifier.width(240.dp).padding(top = 2.dp)) {
        OfflineMapPlaceholder(Modifier.fillMaxWidth().height(110.dp))
        location.label?.let {
            Text(
                it, style = MaterialTheme.typography.titleSmall, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).clearAndSetSemantics {},
            )
        }
        Text(
            coordinates, style = MaterialTheme.typography.bodyMedium, color = content,
            modifier = Modifier.padding(top = if (location.label == null) 6.dp else 0.dp).clearAndSetSemantics {},
        )
        Text(
            stringResource(R.string.location_accuracy, formatAccuracy(location.accuracyM)),
            style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.75f), modifier = Modifier.clearAndSetSemantics {},
        )
        TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.location_open_map), modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.location_copy), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** A grid and a pin, drawn on the phone: stands in for a map without contacting anyone. */
@Composable
fun OfflineMapPlaceholder(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val description = stringResource(R.string.location_placeholder_description)
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val background = scheme.surfaceVariant
        val line = scheme.outline.copy(alpha = 0.35f)
        Canvas(Modifier.matchParentSize()) {
            drawRect(background)
            val step = 28.dp.toPx()
            var x = step / 2
            while (x < size.width) {
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                x += step
            }
            var y = step / 2
            while (y < size.height) {
                drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                y += step
            }
        }
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = scheme.error, modifier = Modifier.size(40.dp))
    }
}

/** "Kartappen du väljer får se den här positionen." before a position is handed to another app. */
@Composable
fun OpenInMapDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_open_confirm_title)) },
        text = { Text(stringResource(R.string.location_open_confirm_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.location_open_confirm_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Where the "Position" flow stands; kept by the conversation's view model. */
sealed interface LocationShareUi {
    /** Asking how exact the position should be. */
    data object Choose : LocationShareUi

    /** Waiting for the one fix (at most 30 s). */
    data class Fetching(val precise: Boolean) : LocationShareUi

    /** The fix, shown with its accuracy before anything is sent. */
    data class Preview(val location: LocationSpec) : LocationShareUi
}

/** The dialogs of the flow. Nothing is read from the phone's sensors until a button here asks for it. */
@Composable
fun LocationShareDialogs(
    state: LocationShareUi?,
    onChoose: (precise: Boolean) -> Unit,
    onCancel: () -> Unit,
    onSend: (LocationSpec) -> Unit,
) {
    when (state) {
        null -> Unit
        LocationShareUi.Choose -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.location_choose_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.location_choose_text))
                    TextButton(onClick = { onChoose(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.location_approximate), modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { onChoose(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.location_exact), modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
        )
        is LocationShareUi.Fetching -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.location_fetching)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Text(stringResource(R.string.location_fetching_hint))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
        )
        is LocationShareUi.Preview -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.location_preview_title)) },
            text = {
                Column {
                    OfflineMapPlaceholder(Modifier.fillMaxWidth().height(96.dp))
                    Text(
                        formatCoordinates(state.location.lat, state.location.lon), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(stringResource(R.string.location_accuracy, formatAccuracy(state.location.accuracyM)))
                    Text(
                        stringResource(R.string.location_preview_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onSend(state.location) }) { Text(stringResource(R.string.action_send)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
