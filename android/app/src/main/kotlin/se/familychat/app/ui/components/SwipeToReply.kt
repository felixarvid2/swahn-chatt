package se.familychat.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far the message must be pulled to the right before letting go replies. */
private val SWIPE_THRESHOLD = 64.dp

/** A swipe must be this much more horizontal than vertical to count; everything else is a scroll of the list. */
private const val HORIZONTAL_DOMINANCE = 2f

/**
 * Swipe a message to the right to reply to it: it follows the finger up to a little past [SWIPE_THRESHOLD], a haptic tick
 * and the reply icon say "that is far enough", and letting go then calls [onReply]. Letting go earlier snaps back and does
 * nothing.
 *
 * The gesture only claims the touch once it is clearly horizontal and to the right ([HORIZONTAL_DOMINANCE]); a vertical
 * movement is left alone so the list scrolls as usual, and taps and long presses on the content are not affected. The same
 * action is in the message's long-press menu and in its accessibility actions: the swipe is only a shortcut.
 */
@Composable
fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val thresholdPx = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reply = rememberUpdatedState(onReply)
    Box(
        modifier.pointerInput(enabled, thresholdPx) {
            if (!enabled) return@pointerInput
            detectSwipeRight(
                thresholdPx,
                onDrag = { dx -> scope.launch { offset.snapTo(dx) } },
                onThreshold = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                onEnd = { replied ->
                    scope.launch { offset.animateTo(0f) }
                    if (replied) reply.value()
                },
            )
        },
    ) {
        val progress = (offset.value / thresholdPx).coerceIn(0f, 1f)
        if (progress > 0f) {
            Icon(
                Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(24.dp).clearAndSetSemantics {}
                    .graphicsLayer { alpha = progress; scaleX = 0.5f + 0.5f * progress; scaleY = 0.5f + 0.5f * progress },
            )
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

/**
 * Watches the touches of one gesture after the children have seen them (so a button, a link or a long press that took the
 * touch is left alone). Gives up at once when the movement turns out vertical or to the left. [onDrag] gets the distance
 * pulled so far (limited to 1.5 thresholds), [onThreshold] is called once when the distance first reaches [thresholdPx]
 * (again if the finger goes back and forth over it), [onEnd] when the finger lifts or the gesture is taken over, with
 * whether it ended beyond the threshold.
 */
@Suppress("LoopWithTooManyJumpStatements") // each exit is one way the gesture is not ours
private suspend fun PointerInputScope.detectSwipeRight(
    thresholdPx: Float,
    onDrag: (Float) -> Unit,
    onThreshold: () -> Unit,
    onEnd: (replied: Boolean) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        var dx = 0f
        var dy = 0f
        var swiping = false
        var beyond = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.changedToUpIgnoreConsumed()) break
            if (!swiping && change.isConsumed) return@awaitEachGesture // a child or the list took this touch
            val delta = change.positionChange()
            dx += delta.x
            dy += delta.y
            if (!swiping) {
                val vertical = abs(dy) > slop && abs(dy) * HORIZONTAL_DOMINANCE > abs(dx)
                if (vertical || dx < -slop) return@awaitEachGesture // a scroll, or a swipe to the left: not ours
                if (dx > slop) swiping = true
            }
            if (swiping) {
                change.consume()
                onDrag(dx.coerceIn(0f, thresholdPx * 1.5f))
                val now = dx >= thresholdPx
                if (now && !beyond) onThreshold()
                beyond = now
            }
        }
        if (swiping) onEnd(beyond)
    }
}
