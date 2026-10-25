package se.swahnchat.app.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.swahnchat.app.R
import se.swahnchat.core.client.call.CallState

/**
 * The slim bar at the top of the app while a call is ringing or running and the call screen is not up: a tap goes back to it.
 * [padStatusBar] is false when something above (the insecure-connection strip) already sits under the status bar.
 */
@Composable
fun OngoingCallBar(call: CallState.InCall, padStatusBar: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val text = stringResource(if (call is CallState.Incoming) R.string.call_incoming_bar else R.string.call_ongoing_bar)
    Row(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(role = Role.Button) { context.startActivity(CallActivity.showIntent(context)) }
            .then(if (padStatusBar) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
        Text(
            text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp),
        )
    }
}
