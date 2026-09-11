package lux.uno.anima.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A row that scrolls sideways and can be operated with a mouse.
 *
 * The wheel is left alone on purpose. A chip strip that happens to sit under the pointer
 * should not swallow the gesture the page is expecting, so a plain wheel always scrolls the
 * screen. Sideways movement comes from dragging the row, from shift+wheel, or from a
 * trackpad's horizontal gesture — all of which say "sideways" explicitly.
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
    val dragState = rememberDraggableState { delta ->
        // Raw dispatch rather than scrollBy: each scrollBy starts its own scroll mutation and
        // cancels the one before it, so a stream of drag deltas would cancel itself to a halt.
        // Dragging content leftwards scrolls the viewport rightwards.
        state.dispatchRawDelta(-delta)
    }

    Row(
        modifier = modifier
            .horizontalScroll(state)
            .draggable(orientation = Orientation.Horizontal, state = dragState)
            .padding(contentPadding),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        content = content,
    )
}
