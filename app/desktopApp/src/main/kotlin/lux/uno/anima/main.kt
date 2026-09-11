package lux.uno.anima

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.debounce

fun main() = application {
    val geometryStore = remember { WindowGeometryStore() }
    val restored = remember { geometryStore.read() }

    val state = rememberWindowState(
        size = restored?.size ?: DpSize(460.dp, 900.dp),
        position = restored?.position ?: WindowPosition(Alignment.Center),
        placement = if (restored?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Anima",
        state = state,
    ) {
        // Windows will not let a process take the foreground away from the one the user is
        // actually in, so a run started from the IDE opens behind it and `toFront` alone is
        // ignored. A momentary always-on-top is the one route to the front that is still
        // granted, and dropping it again leaves the window ordinary.
        LaunchedEffect(Unit) {
            window.isAlwaysOnTop = true
            window.toFront()
            window.requestFocus()
            window.isAlwaysOnTop = false
        }

        // Written as the window moves rather than on close, because a crash or a stopped
        // Gradle run never reaches a close handler. Debounced so a drag is one write.
        LaunchedEffect(state) {
            var floating = restored
            snapshotFlow { state.toSnapshot() }
                .debounce(GEOMETRY_WRITE_DELAY_MILLIS)
                .collect { snapshot ->
                    snapshot.floating?.let { floating = it }
                    val bounds = floating ?: return@collect
                    geometryStore.write(bounds.copy(maximized = snapshot.maximized))
                }
        }

        App()
    }
}

private data class WindowSnapshot(
    /**
     * Null while the window is maximised or minimised: those bounds are the screen's rather
     * than a position the user chose, so the last floating ones stay the ones to keep.
     */
    val floating: WindowGeometry?,
    val maximized: Boolean,
)

private fun WindowState.toSnapshot(): WindowSnapshot = WindowSnapshot(
    floating = if (placement == WindowPlacement.Floating && !isMinimized) toGeometry() else null,
    maximized = placement == WindowPlacement.Maximized,
)

private const val GEOMETRY_WRITE_DELAY_MILLIS = 400L
