package se.swahnchat.app.ui.screens

import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import se.swahnchat.app.R
import se.swahnchat.app.data.Accent
import se.swahnchat.app.data.ChatBackground
import se.swahnchat.app.data.QuietHours
import se.swahnchat.app.data.QuietHoursSettings
import se.swahnchat.app.ui.components.chatBackground
import se.swahnchat.app.ui.theme.accentSwatch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** "Tysta timmar": on/off, from and to (the system's time picker), the days and whether calls are silenced too. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuietHoursDialog(settings: QuietHoursSettings, onChange: (QuietHoursSettings) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun pick(initial: Int, done: (Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> done(h * 60 + m) }, initial / 60, initial % 60, android.text.format.DateFormat.is24HourFormat(context)).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quiet_hours)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.quiet_hours_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                DialogSwitch(stringResource(R.string.quiet_hours_enable), settings.enabled) { onChange(settings.copy(enabled = it)) }
                if (settings.enabled) {
                    TimeRow(stringResource(R.string.quiet_from), settings.startMinutes) {
                        pick(settings.startMinutes) { m -> onChange(settings.copy(startMinutes = m)) }
                    }
                    TimeRow(stringResource(R.string.quiet_to), settings.endMinutes) {
                        pick(settings.endMinutes) { m -> onChange(settings.copy(endMinutes = m)) }
                    }
                    Text(stringResource(R.string.quiet_days), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            val on = day in settings.days
                            FilterChip(
                                selected = on, modifier = Modifier.heightIn(min = 48.dp),
                                onClick = { onChange(settings.copy(days = if (on) settings.days - day else settings.days + day)) },
                                label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                            )
                        }
                    }
                    Text(stringResource(R.string.quiet_days_note), style = MaterialTheme.typography.bodySmall)
                    DialogSwitch(stringResource(R.string.quiet_calls), settings.muteCalls, stringResource(R.string.quiet_calls_hint)) {
                        onChange(settings.copy(muteCalls = it))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun DialogSwitch(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun TimeRow(label: String, minutes: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = false, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(QuietHours.clock(minutes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

internal fun accentLabel(accent: Accent): Int = when (accent) {
    Accent.SYSTEM -> R.string.accent_system
    Accent.TEAL -> R.string.accent_teal
    Accent.BLUE -> R.string.accent_blue
    Accent.PURPLE -> R.string.accent_purple
    Accent.ROSE -> R.string.accent_rose
    Accent.ORANGE -> R.string.accent_orange
    Accent.GREEN -> R.string.accent_green
}

internal fun backgroundLabel(kind: ChatBackground): Int = when (kind) {
    ChatBackground.PLAIN -> R.string.chat_bg_plain
    ChatBackground.TINT -> R.string.chat_bg_tint
    ChatBackground.DOTS -> R.string.chat_bg_dots
    ChatBackground.GRID -> R.string.chat_bg_grid
    ChatBackground.DIAGONAL -> R.string.chat_bg_diagonal
}

/** The accent colours: [Accent.SYSTEM] ("Systemets färger", Android 12+ only) as a row, the six presets as swatches. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentDialog(selected: Accent, systemAvailable: Boolean, onPick: (Accent) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accent_color)) },
        text = {
            Column {
                if (systemAvailable) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = selected == Accent.SYSTEM, role = Role.RadioButton) { onPick(Accent.SYSTEM) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == Accent.SYSTEM, onClick = null)
                        Text(stringResource(R.string.accent_system), modifier = Modifier.padding(start = 12.dp))
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Accent.entries.filter { it != Accent.SYSTEM }.forEach { accent ->
                        val picked = selected == accent || (selected == Accent.SYSTEM && !systemAvailable && accent == Accent.TEAL)
                        val name = stringResource(accentLabel(accent))
                        Column(
                            Modifier.widthIn(min = 72.dp).heightIn(min = 48.dp).selectable(selected = picked, role = Role.RadioButton) { onPick(accent) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(accentSwatch(accent) ?: MaterialTheme.colorScheme.primary)
                                    .then(if (picked) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), CircleShape) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (picked) Icon(Icons.Filled.Check, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White)
                            }
                            Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) } },
    )
}

/** The chat backgrounds, each with a small preview drawn the same way as in a chat. */
@Composable
fun ChatBackgroundDialog(selected: ChatBackground, onPick: (ChatBackground) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_background)) },
        text = {
            Column {
                ChatBackground.entries.forEach { kind ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected == kind, role = Role.RadioButton) { onPick(kind) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == kind, onClick = null)
                        Box(
                            Modifier.padding(horizontal = 12.dp, vertical = 4.dp).size(48.dp).clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.background).chatBackground(kind)
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.small),
                        )
                        Text(stringResource(backgroundLabel(kind)))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) } },
    )
}
