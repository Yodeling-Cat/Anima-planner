package lux.uno.anima.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lux.uno.anima.finance.model.IconRef
import lux.uno.anima.finance.store.IconStore
import lux.uno.anima.ui.components.GlyphTile

/**
 * Reads icon images off disk once and keeps the decoded bitmaps for the life of the app.
 * Icons are tiny and few, so a plain map beats an eviction policy here.
 */
@Stable
class IconImages(private val store: IconStore) {
    private val decoded = mutableStateMapOf<String, ImageBitmap>()

    /** Null while the image is still loading, or when the file has gone missing. */
    @Composable
    fun bitmapFor(imageId: String?): ImageBitmap? {
        if (imageId == null) return null
        var bitmap by remember(imageId) { mutableStateOf(decoded[imageId]) }
        LaunchedEffect(imageId) {
            if (decoded.containsKey(imageId)) {
                bitmap = decoded[imageId]
                return@LaunchedEffect
            }
            val bytes = store.load(imageId) ?: return@LaunchedEffect
            val image = runCatching { bytes.decodeToImageBitmap() }.getOrNull() ?: return@LaunchedEffect
            decoded[imageId] = image
            bitmap = image
        }
        return bitmap
    }

    /** Optimises and saves [bytes], returning the new id, or null if it was not an image. */
    suspend fun add(bytes: ByteArray): String? = store.store(bytes)

    suspend fun remove(imageId: String) {
        decoded.remove(imageId)
        store.delete(imageId)
    }

    suspend fun pruneExcept(keep: Set<String>) = store.pruneExcept(keep)
}

val LocalIconImages = staticCompositionLocalOf<IconImages> {
    error("LocalIconImages was read outside of the Anima app tree")
}

/**
 * A subscription's icon: the uploaded image when there is one, otherwise the emoji on a
 * tinted tile. Falls back to the emoji while the image loads, so the row never flickers empty.
 */
@Composable
fun SubscriptionIcon(
    icon: IconRef,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val bitmap = LocalIconImages.current.bitmapFor(icon.imageId)
    if (bitmap == null) {
        GlyphTile(icon.emoji, accent, modifier, size)
        return
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
