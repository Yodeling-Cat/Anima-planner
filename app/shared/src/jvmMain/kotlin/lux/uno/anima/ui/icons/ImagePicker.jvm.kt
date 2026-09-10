package lux.uno.anima.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberImagePicker(onPicked: (ByteArray?) -> Unit): ImagePicker {
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onPicked)

    return remember(scope) {
        ImagePicker {
            scope.launch {
                // FileDialog blocks until dismissed, so it never runs on the UI dispatcher.
                val bytes = withContext(Dispatchers.IO) {
                    val dialog = FileDialog(null as Frame?, "Choose an icon image", FileDialog.LOAD)
                    // Windows reads the wildcard list; the other platforms use the filter.
                    dialog.file = "*.png;*.jpg;*.jpeg;*.gif;*.bmp"
                    dialog.setFilenameFilter { _, name -> name.isImageFileName() }
                    dialog.isVisible = true

                    val chosen = dialog.file ?: return@withContext null
                    runCatching { File(dialog.directory ?: "", chosen).readBytes() }.getOrNull()
                }
                callback(bytes)
            }
        }
    }
}

private fun String.isImageFileName(): Boolean =
    lowercase().substringAfterLast('.', "") in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
