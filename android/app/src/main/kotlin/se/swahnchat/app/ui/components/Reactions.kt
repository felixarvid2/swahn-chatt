package se.swahnchat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.swahnchat.app.R
import se.swahnchat.app.ui.model.ReactionUi

/**
 * The emojis reactions are chosen from. A curated, offline list (no emoji library, no data to download): the quick bar of the
 * long-press menu and the grid behind its "+". Every entry is a single emoji of at most [se.swahnchat.core.model.Emoji.MAX_BYTES]
 * bytes, which `EmojiCatalogTest` checks against the same validation the engine applies.
 */
object EmojiCatalog {
    val quick: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    class Category(val title: Int, val emojis: List<String>)

    val categories: List<Category> = listOf(
        Category(
            R.string.reaction_category_faces,
            "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😍 🥰 😘 😋 😛 😜 🤪 🤔 🤨 😐 😑 😶 🙄 😏 😬 😮 😯 😲 😳 🥺 😢 😭 😱 😖 😞 😓 😩 😤 😡 🥳 😎 🤓 😴"
                .split(' '),
        ),
        Category(
            R.string.reaction_category_gestures,
            "👍 👎 👌 ✌️ 🤞 🤟 🤘 👈 👉 👆 👇 ☝️ 👋 🤚 ✋ 🖖 👏 🙌 🤝 🙏 💪 🫶 🤗 🤷 🤦 🙋".split(' '),
        ),
        Category(
            R.string.reaction_category_hearts,
            "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 ❣️ 💕 💖 💯 ✅ ❌ ❓ ❗ ⭐ ✨ 🔥 🎉 🎊".split(' '),
        ),
        Category(
            R.string.reaction_category_things,
            "🎂 🍕 🍔 🍻 ☕ 🌞 🌈 ⛄ 🐶 🐱 🐻 🦄 🌹 🌻 🍀 🚗 ✈️ 🏠 🎁 📷".split(' '),
        ),
    )

    val all: List<String> get() = quick + categories.flatMap { it.emojis }
}

/** The six quick reactions and "+" at the top of the long-press menu. [selected] is the emoji you already reacted with. */
@Composable
fun QuickReactionRow(selected: String?, onPick: (String) -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val bar = stringResource(R.string.reaction_bar_description)
    Row(
        modifier.padding(horizontal = 8.dp).semantics { contentDescription = bar },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (emoji in EmojiCatalog.quick) {
            val label = stringResource(R.string.reaction_set, emoji)
            val isSelected = emoji == selected
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Button, onClick = { onPick(emoji) })
                    .semantics { contentDescription = label; this.selected = isSelected },
                contentAlignment = Alignment.Center,
            ) { Text(emoji, fontSize = 26.sp, modifier = Modifier.clearAndSetSemantics {}) }
        }
        val more = stringResource(R.string.reaction_more)
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = more, onClick = onMore)
                .semantics { contentDescription = more },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, contentDescription = null) }
    }
}

/** The grid behind "+": every emoji of [EmojiCatalog], grouped under headings. */
@Composable
fun ReactionPickerDialog(selected: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reaction_picker_title)) },
        text = { ReactionPickerGrid(selected, onPick) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** The emoji grid itself (also what the screenshots show, since a dialog is a window of its own). */
@Composable
fun ReactionPickerGrid(selected: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(columns = GridCells.Adaptive(48.dp), modifier = modifier.heightIn(max = 360.dp)) {
        for (category in EmojiCatalog.categories) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "h${category.title}") {
                Text(
                    stringResource(category.title), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(category.emojis, key = { "${category.title}$it" }) { emoji ->
                val label = stringResource(R.string.reaction_set, emoji)
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(if (emoji == selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.Button, onClick = { onPick(emoji) })
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 26.sp, modifier = Modifier.clearAndSetSemantics {}) }
            }
        }
    }
}

/**
 * The reactions under a bubble: one chip per emoji with its count; yours is outlined. One tap target for the whole row (it
 * opens [ReactorsDialog]), so the row is read as one line.
 */
@Composable
fun ReactionsRow(reactions: List<ReactionUi>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (reactions.isEmpty()) return
    val description = stringResource(R.string.reactions_row_description)
    val spoken = reactions.map { stringResource(R.string.reaction_chip_description, it.emoji, it.count) }.joinToString(", ")
    Row(
        modifier.defaultMinSize(minHeight = 36.dp).clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (r in reactions) ReactionChip(r)
    }
}

@Composable
private fun ReactionChip(r: ReactionUi) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (r.mine) scheme.primaryContainer else scheme.surfaceContainerHighest)
            .border(1.dp, if (r.mine) scheme.primary else scheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(r.emoji, fontSize = 16.sp)
        Spacer(Modifier.width(4.dp))
        Text(r.count.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Who reacted with what. A tap on your own reaction takes it back. */
@Composable
fun ReactorsDialog(reactions: List<ReactionUi>, onRemoveMine: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reactions_title)) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    for (r in reactions) {
                        items(r.reactors.size, key = { "${r.emoji}${r.reactors[it].userId}" }) { i ->
                            val who = r.reactors[i]
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .then(if (who.isMe) Modifier.clickable(role = Role.Button, onClick = { onRemoveMine(r.emoji) }) else Modifier),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(who.avatar, size = 36.dp)
                                Text(
                                    if (who.isMe) stringResource(R.string.you) else who.name,
                                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f),
                                )
                                Text(r.emoji, fontSize = 22.sp)
                            }
                        }
                    }
                }
                if (reactions.any { it.mine }) {
                    Text(
                        stringResource(R.string.reactions_remove_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
