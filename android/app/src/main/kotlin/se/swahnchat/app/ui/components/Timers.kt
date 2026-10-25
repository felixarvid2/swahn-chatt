package se.swahnchat.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import se.swahnchat.app.R

/** The timers the settings offer, in seconds; 0 is off. (The protocol accepts any value from 5 minutes to a year.) */
object DisappearingTimers {
    val options: List<Long> = listOf(0L, 5 * 60L, 60 * 60L, 24 * 60 * 60L, 7 * 24 * 60 * 60L, 28 * 24 * 60 * 60L)
}

/** "Av", "5 minuter", "1 timme", "1 dag", "1 vecka", "4 veckor"; any other value from another app is spelled in seconds. */
@Composable
fun timerLabel(seconds: Long): String {
    val res = when (seconds) {
        0L -> R.string.disappearing_off
        5 * 60L -> R.string.disappearing_5min
        60 * 60L -> R.string.disappearing_1h
        24 * 60 * 60L -> R.string.disappearing_1d
        7 * 24 * 60 * 60L -> R.string.disappearing_1w
        28 * 24 * 60 * 60L -> R.string.disappearing_4w
        else -> return stringResource(R.string.disappearing_other_seconds, seconds.toInt())
    }
    return stringResource(res)
}

/**
 * The timer setting: one choice from [DisappearingTimers.options]. Without [canChange] (a member of a group that is not an
 * administrator) the current value is shown and nothing can be picked.
 */
@Composable
fun DisappearingDialog(current: Long, canChange: Boolean, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disappearing_title)) },
        text = { DisappearingOptions(current, canChange, onSelect) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** The explanation and the choices of the timer dialog (also what the screenshots show). */
@Composable
fun DisappearingOptions(current: Long, canChange: Boolean, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.disappearing_explain), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.padding(top = 8.dp).selectableGroup()) {
            val options = if (current in DisappearingTimers.options) DisappearingTimers.options else DisappearingTimers.options + current
            for (seconds in options) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = seconds == current, enabled = canChange, role = Role.RadioButton, onClick = { onSelect(seconds) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = seconds == current, onClick = null, enabled = canChange)
                    Text(timerLabel(seconds), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        if (!canChange) {
            Text(
                stringResource(R.string.disappearing_admin_only), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            stringResource(R.string.disappearing_server_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
