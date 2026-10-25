package se.swahnchat.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import se.swahnchat.app.R
import se.swahnchat.app.ui.model.MemberUi
import se.swahnchat.core.client.MAX_SCHEDULE_MS
import se.swahnchat.core.client.MIN_SCHEDULE_MS
import se.swahnchat.core.model.Content
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// --- mentions -------------------------------------------------------------------------------------------------------------

/**
 * The people who can be mentioned, shown above the composer while the text ends in `@` and a part of a name. A tap picks the
 * person (the screen then writes `@Name` into the message).
 */
@Composable
fun MentionPicker(suggestions: List<MemberUi>, onPick: (userId: String) -> Unit, modifier: Modifier = Modifier) {
    if (suggestions.isEmpty()) return
    val description = stringResource(R.string.mention_picker_description)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = description },
    ) {
        LazyColumn(Modifier.heightIn(max = 208.dp)) {
            items(suggestions, key = { it.userId }) { member ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onPick(member.userId) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(member.avatar, size = 32.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(member.name, style = MaterialTheme.typography.bodyLarge)
                        if (member.username.isNotBlank()) {
                            Text("@" + member.username, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private val MENTION_TOKEN = Regex("""(^|\s)@([^\s@]*)$""")

/** The part of [draft] after the `@` that is being typed at its end, or null when no mention is being written. */
fun mentionQuery(draft: String): String? = MENTION_TOKEN.find(draft)?.groupValues?.get(2)

/** Where the `@` of the token at the end of [draft] is (or -1). */
fun mentionTokenStart(draft: String): Int = MENTION_TOKEN.find(draft)?.let { it.groups[2]!!.range.first - 1 } ?: -1

/** The members whose name starts a word with [query] (case does not matter), best matches (names starting with it) first. */
fun mentionSuggestions(members: List<MemberUi>, query: String): List<MemberUi> {
    val q = query.trim().lowercase()
    val candidates = members.filter { !it.isMe }
    if (q.isEmpty()) return candidates.sortedBy { it.name.lowercase() }
    return candidates.filter { m ->
        m.name.lowercase().split(' ').any { it.startsWith(q) } || m.username.lowercase().startsWith(q)
    }.sortedWith(compareBy({ !it.name.lowercase().startsWith(q) }, { it.name.lowercase() }))
}

// --- polls ----------------------------------------------------------------------------------------------------------------

/** What is typed into the form of a new poll. */
class PollDraft {
    var question by mutableStateOf("")
    var multiple by mutableStateOf(false)
    val options = mutableStateListOf("", "")

    /** The options that have text. */
    val filled: List<String> get() = options.map { it.trim() }.filter { it.isNotEmpty() }
    val canCreate: Boolean get() = question.isNotBlank() && filled.size >= Content.MIN_POLL_OPTIONS
}

/** The question, 2 to 10 options and the "several choices" switch of a new poll. */
@Composable
fun PollCreateForm(draft: PollDraft, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.question, onValueChange = { draft.question = it.take(Content.MAX_POLL_QUESTION) },
            label = { Text(stringResource(R.string.poll_question_label)) },
            supportingText = { Text("${draft.question.length}/${Content.MAX_POLL_QUESTION}") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        draft.options.forEachIndexed { i, text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { draft.options[i] = it.take(Content.MAX_POLL_OPTION) },
                    label = { Text(stringResource(R.string.poll_option_label, i + 1)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                if (draft.options.size > Content.MIN_POLL_OPTIONS) {
                    IconButton(onClick = { draft.options.removeAt(i) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.poll_remove_option, i + 1))
                    }
                }
            }
        }
        if (draft.options.size < Content.MAX_POLL_OPTIONS) {
            TextButton(onClick = { draft.options.add("") }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.poll_add_option), modifier = Modifier.padding(start = 8.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = draft.multiple, role = Role.Switch, onValueChange = { draft.multiple = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.poll_multiple), modifier = Modifier.weight(1f))
            Switch(checked = draft.multiple, onCheckedChange = null)
        }
        Text(stringResource(R.string.poll_create_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A new poll: the form in a dialog. */
@Composable
fun PollCreateDialog(onCreate: (question: String, options: List<String>, multiple: Boolean) -> Unit, onDismiss: () -> Unit) {
    val draft = remember { PollDraft() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.poll_create_title)) },
        text = { PollCreateForm(draft) },
        confirmButton = {
            TextButton(onClick = { onCreate(draft.question.trim(), draft.filled, draft.multiple) }, enabled = draft.canCreate) {
                Text(stringResource(R.string.poll_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// --- scheduled messages -----------------------------------------------------------------------------------------------------

/** Why a time cannot be used for a scheduled message, or null when it can. [now] is the phone's clock. */
fun scheduleProblem(sendAt: Long, now: Long): ScheduleProblem? = when {
    sendAt < now + MIN_SCHEDULE_MS -> ScheduleProblem.TOO_EARLY
    sendAt > now + MAX_SCHEDULE_MS -> ScheduleProblem.TOO_LATE
    else -> null
}

enum class ScheduleProblem { TOO_EARLY, TOO_LATE }

/**
 * Date and time for a scheduled message: a minute to 30 days ahead. [initial] is shown first; the hint says that the message
 * goes out when the phone is online and may be a few minutes late.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDialog(
    initial: Long,
    now: () -> Long,
    onConfirm: (sendAt: Long) -> Unit,
    onDismiss: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    confirmLabel: String = stringResource(R.string.schedule_confirm),
) {
    val start = Instant.ofEpochMilli(initial).atZone(zone)
    var date by rememberSaveable { mutableStateOf(start.toLocalDate().toEpochDay()) }
    var minuteOfDay by rememberSaveable { mutableStateOf(start.hour * 60 + start.minute) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val sendAt = LocalDateTime.of(LocalDate.ofEpochDay(date), LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)).atZone(zone).toInstant().toEpochMilli()
    val problem = scheduleProblem(sendAt, now())
    val clock = rememberClockFormatter(zone)
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", currentLocale()).format(LocalDate.ofEpochDay(date)))
                    }
                    OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(clock(sendAt)) }
                }
                if (problem != null) {
                    Text(
                        stringResource(if (problem == ScheduleProblem.TOO_EARLY) R.string.schedule_error_early else R.string.schedule_error_late),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(stringResource(R.string.schedule_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (scheduleProblem(sendAt, now()) == null) onConfirm(sendAt) }, enabled = problem == null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (pickDate) {
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now()), zone)
        val first = today.toEpochDay() * DAY_MS
        val last = today.plusDays(31).toEpochDay() * DAY_MS
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date * DAY_MS,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in first..last
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = { state.selectedDateMillis?.let { date = it / DAY_MS }; pickDate = false }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(
            initialHour = minuteOfDay / 60, initialMinute = minuteOfDay % 60,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text(stringResource(R.string.schedule_pick_time)) },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state) } },
            confirmButton = {
                TextButton(onClick = { minuteOfDay = state.hour * 60 + state.minute; pickTime = false }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** The time proposed first for a new schedule: the next full hour that is at least [MIN_SCHEDULE_MS] away, or in an hour at the least. */
fun defaultScheduleTime(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val nextHour = Instant.ofEpochMilli(now).atZone(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
    return maxOf(nextHour, now + 2 * MIN_SCHEDULE_MS)
}
