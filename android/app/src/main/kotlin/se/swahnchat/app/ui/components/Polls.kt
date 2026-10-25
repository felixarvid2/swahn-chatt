package se.swahnchat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.swahnchat.app.R
import se.swahnchat.app.ui.model.MessageUi
import se.swahnchat.app.ui.model.PollOptionUi
import se.swahnchat.app.ui.model.PollUi

/** The choice that results from tapping [optionId]: in a poll with several choices it toggles, otherwise it picks or withdraws. */
fun nextPollChoice(poll: PollUi, optionId: Int): List<Int> {
    val mine = poll.myChoice
    return when {
        poll.multiple -> if (optionId in mine) mine - optionId else (mine + optionId).sorted()
        optionId in mine -> emptyList()
        else -> listOf(optionId)
    }
}

/**
 * The poll of a message: the question, every option with a bar, its count and your choice, who voted (tap the count). Tapping
 * an option votes, or takes the vote back; a closed poll only shows the result.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PollView(
    m: MessageUi,
    poll: PollUi,
    content: Color,
    onVote: (List<Int>) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var votersOf by remember { mutableStateOf<PollOptionUi?>(null) }
    val scheme = MaterialTheme.colorScheme
    val top = poll.options.maxOfOrNull { it.count } ?: 0
    Column(modifier.defaultMinSize(minWidth = 230.dp).padding(top = 2.dp)) {
        Text(
            m.text.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = content,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            if (poll.closed) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = content.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                stringResource(if (poll.closed) R.string.poll_closed else if (poll.multiple) R.string.poll_multiple_hint else R.string.poll_single_hint),
                style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.75f),
            )
        }
        for (o in poll.options) {
            val votesText = pluralStringResource(R.plurals.poll_votes, o.count, o.count)
            val selectedWord = stringResource(R.string.poll_option_selected)
            val spoken = stringResource(R.string.poll_option_description, o.text, if (o.mine) "$votesText, $selectedWord" else votesText)
            Column(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                    // a long press on an option opens the menu of the message, as anywhere else on the bubble
                    .combinedClickable(
                        role = if (poll.multiple) Role.Checkbox else Role.RadioButton,
                        onClick = { if (poll.canVote) onVote(nextPollChoice(poll, o.id)) },
                        onLongClick = onLongPress,
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = spoken
                        selected = o.mine
                        if (!poll.canVote) disabled()
                    },
            ) {
                Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            poll.multiple && o.mine -> Icons.Filled.CheckBox
                            poll.multiple -> Icons.Filled.CheckBoxOutlineBlank
                            o.mine -> Icons.Filled.RadioButtonChecked
                            else -> Icons.Filled.RadioButtonUnchecked
                        },
                        contentDescription = null, tint = if (o.mine) scheme.primary else content.copy(alpha = 0.7f), modifier = Modifier.size(20.dp),
                    )
                    Text(
                        o.text, style = MaterialTheme.typography.bodyMedium, color = content,
                        fontWeight = if (o.mine) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(
                        o.count.toString(), style = MaterialTheme.typography.labelLarge, color = content,
                        fontWeight = if (o.count > 0 && o.count == top) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .heightIn(min = 36.dp).defaultMinSize(minWidth = 28.dp)
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.poll_show_voters)) { votersOf = o }
                            .padding(horizontal = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
                PollBar(if (poll.voterCount == 0) 0f else o.count.toFloat() / poll.voterCount, o.mine, content)
            }
        }
        Text(
            if (poll.voterCount == 0) {
                stringResource(R.string.poll_no_votes)
            } else {
                pluralStringResource(R.plurals.poll_voter_total, poll.voterCount, poll.voterCount)
            },
            style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.75f), modifier = Modifier.padding(top = 4.dp),
        )
    }
    votersOf?.let { PollVotersDialog(it, onDismiss = { votersOf = null }) }
}

@Composable
private fun PollBar(fraction: Float, mine: Boolean, content: Color) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(content.copy(alpha = 0.15f)),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                    .background(if (mine) scheme.primary else content.copy(alpha = 0.55f)),
            )
        }
    }
}

/** Who chose an option. */
@Composable
fun PollVotersDialog(option: PollOptionUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(option.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            if (option.voters.isEmpty()) {
                Text(stringResource(R.string.poll_voters_none))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (v in option.voters) {
                        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(v.avatar, size = 32.dp)
                            Text(
                                if (v.isMe) stringResource(R.string.you) else v.name, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
