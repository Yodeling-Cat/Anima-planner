package lux.uno.anima

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import lux.uno.anima.platform.appDataDirectory
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File
import java.util.Properties

/**
 * Where the window was when it was last closed.
 *
 * Deliberately not suffixed per environment, for the same reason `environment.json` is not:
 * this belongs to the desktop install rather than to a dataset, and switching environments
 * should not move the window.
 */
class WindowGeometryStore(directory: String = appDataDirectory()) {

    private val file = File(directory, "window.properties")

    fun read(): WindowGeometry? {
        val properties = Properties()
        runCatching { file.inputStream().use(properties::load) }.getOrElse { return null }

        val x = properties.float("x") ?: return null
        val y = properties.float("y") ?: return null
        val width = properties.float("width") ?: return null
        val height = properties.float("height") ?: return null
        if (width <= 0f || height <= 0f) return null

        val geometry = WindowGeometry(
            x = x,
            y = y,
            width = width,
            height = height,
            maximized = properties.getProperty("maximized").toBoolean(),
        )
        // A monitor that is no longer attached would put the window somewhere the user
        // cannot reach it, which looks exactly like the app failing to start.
        return geometry.takeIf { it.isOnAScreen() }
    }

    fun write(geometry: WindowGeometry) {
        val properties = Properties().apply {
            setProperty("x", geometry.x.toString())
            setProperty("y", geometry.y.toString())
            setProperty("width", geometry.width.toString())
            setProperty("height", geometry.height.toString())
            setProperty("maximized", geometry.maximized.toString())
        }
        // Losing the last position is not worth a crash on a read-only or full disk.
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { properties.store(it, "Anima desktop window geometry") }
        }
    }

    private fun Properties.float(key: String): Float? = getProperty(key)?.toFloatOrNull()
}

data class WindowGeometry(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** The other four stay the bounds to un-maximise back to, which is what Windows does. */
    val maximized: Boolean = false,
) {
    val position: WindowPosition get() = WindowPosition(x.dp, y.dp)
    val size: DpSize get() = DpSize(width.dp, height.dp)

    /** True when enough of the title bar sits on an attached display to drag the window by. */
    fun isOnAScreen(): Boolean {
        val titleBar = Rectangle(x.toInt(), y.toInt(), width.toInt(), TITLE_BAR_HEIGHT)
        return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
            device.defaultConfiguration.bounds.intersects(titleBar)
        }
    }

    companion object {
        private const val TITLE_BAR_HEIGHT = 32
    }
}

fun WindowState.toGeometry(): WindowGeometry? {
    // Aligned until the window has actually been placed, and an alignment is not somewhere
    // to return to.
    val position = position as? WindowPosition.Absolute ?: return null
    return WindowGeometry(
        x = position.x.value,
        y = position.y.value,
        width = size.width.value,
        height = size.height.value,
    )
}
