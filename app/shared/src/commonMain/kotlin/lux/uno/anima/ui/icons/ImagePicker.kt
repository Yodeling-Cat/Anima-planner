package lux.uno.anima.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/** Opens the platform image chooser and hands back the chosen file. */
@Stable
fun interface ImagePicker {
    fun launch()
}

/**
 * [onPicked] receives the raw bytes of the chosen file, or null when the chooser was
 * dismissed or the file could not be read. Resizing happens later, in the icon store.
 */
@Composable
expect fun rememberImagePicker(onPicked: (ByteArray?) -> Unit): ImagePicker
