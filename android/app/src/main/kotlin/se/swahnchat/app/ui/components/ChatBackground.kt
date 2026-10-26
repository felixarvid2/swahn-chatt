package se.swahnchat.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import se.swahnchat.app.data.ChatBackground
import se.swahnchat.app.ui.theme.LocalHighContrast

/** The background chosen in "Utseende", for the message list of every chat. */
val LocalChatBackground = staticCompositionLocalOf { ChatBackground.PLAIN }

/**
 * Draws [kind] behind the content: a faint tint, dots, a grid or diagonal lines, all in the theme's own colours at low opacity
 * (so they follow light/dark and the accent). Nothing is drawn with high contrast on: a pattern behind text is the opposite of it.
 */
@Composable
fun Modifier.chatBackground(kind: ChatBackground = LocalChatBackground.current): Modifier {
    if (kind == ChatBackground.PLAIN || LocalHighContrast.current) return this
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    return drawBehind {
        val step = 28.dp.toPx()
        when (kind) {
            ChatBackground.PLAIN -> Unit
            ChatBackground.TINT -> drawRect(accent.copy(alpha = 0.07f))
            ChatBackground.DOTS -> {
                var y = step / 2
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) {
                        drawCircle(ink.copy(alpha = 0.10f), radius = 1.5.dp.toPx(), center = Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
            ChatBackground.GRID -> {
                var x = 0f
                while (x < size.width) { drawLine(ink.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                var y = 0f
                while (y < size.height) { drawLine(ink.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
            }
            ChatBackground.DIAGONAL -> clipRect {
                var x = -size.height
                while (x < size.width) { drawLine(accent.copy(alpha = 0.10f), Offset(x, size.height), Offset(x + size.height, 0f), 1.5.dp.toPx()); x += step }
            }
        }
    }
}
