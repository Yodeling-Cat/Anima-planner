package lux.uno.anima.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** Pixels moved per wheel notch when a vertical wheel is redirected sideways. */
private const val WheelStep = 90f

/**
 * A row that scrolls sideways and can actually be operated with a mouse.
 *
 * `Modifier.horizontalScroll` alone only answers to a horizontal wheel or shift+wheel, which
 * on a desktop with an ordinary mouse means the row looks stuck. This adds the two gestures
 * people reach for: dragging the content directly, and a plain vertical wheel while the
 * pointer is over the row.
 */
@Composable
fun HorizontalScrollRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val state = rememberScrollState()
    val scope = rememberCoroutineScope()
    val dragState = rememberDraggableState { delta ->
        // Raw dispatch rather than scrollBy: each scrollBy starts its own scroll mutation and
        // cancels the one before it, so a stream of drag deltas would cancel itself to a halt.
        // Dragging content leftwards scrolls the viewport rightwards.
        state.dispatchRawDelta(-delta)
    }

    Row(
        modifier = modifier
            .horizontalScroll(state)
            .wheelScrollsSideways(state, scope)
            .draggable(orientation = Orientation.Horizontal, state = dragState)
            .padding(contentPadding),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

/**
 * Turns vertical wheel movement into horizontal scrolling, but only while this row still has
 * somewhere to go. At either end the event is left alone so the page underneath keeps
 * scrolling and the pointer never feels like it is stuck in a trap.
 */
@Composable
private fun Modifier.wheelScrollsSideways(state: ScrollState, scope: CoroutineScope): Modifier {
    val handler = remember(state, scope) {
        Modifier.pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue

                    val delta = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
                    if (delta == 0f) continue

                    val canMove = if (delta > 0f) state.canScrollForward else state.canScrollBackward
                    if (!canMove) continue

                    event.changes.forEach { it.consume() }
                    scope.launch { state.scrollBy(delta * WheelStep) }
                }
            }
        }
    }
    return this.then(handler)
}
