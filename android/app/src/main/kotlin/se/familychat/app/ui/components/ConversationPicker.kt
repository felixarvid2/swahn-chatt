package se.familychat.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.familychat.app.R
import se.familychat.app.ui.model.ConversationItemUi

/** The conversations that match what was typed (every word of [query] in the title), most recent first. */
fun filterConversations(items: List<ConversationItemUi>, query: String): List<ConversationItemUi> {
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    val matching = if (words.isEmpty()) items else items.filter { c -> words.all { it in c.title.lowercase() } }
    // most recent activity first; archived conversations last
    return matching.sortedWith(compareBy<ConversationItemUi> { it.archived }.thenByDescending { it.lastActivity ?: 0L })
}

/**
 * A searchable list of conversations to choose from, recent first. With [multiple] the rows have checkboxes (the caller
 * limits how many); without, a tap on a row calls [onToggle] once and the caller moves on.
 */
@Composable
fun ConversationPicker(
    items: List<ConversationItemUi>,
    selected: Set<String>,
    query: String,
    multiple: Boolean,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text(stringResource(R.string.picker_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val shown = filterConversations(items, query)
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.picker_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (query.isBlank()) {
                    item(key = "recent") {
                        Text(
                            stringResource(R.string.picker_recent), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(shown, key = { it.id }) { c ->
                    val isSelected = c.id in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .then(
                                if (multiple) Modifier.toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggle(c.id) })
                                else Modifier.clickable(role = Role.Button) { onToggle(c.id) },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(c.avatar, size = 44.dp)
                        Text(
                            c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        )
                        if (multiple) Checkbox(checked = isSelected, onCheckedChange = null)
                    }
                    HorizontalDivider(Modifier.padding(start = 76.dp))
                }
            }
        }
    }
}
