package lux.uno.anima.finance.store

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

actual fun resizeImageToPng(bytes: ByteArray, maxEdge: Int): ByteArray? {
    val source = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null

    val longestEdge = max(source.width, source.height)
    val scale = if (longestEdge > maxEdge) maxEdge.toDouble() / longestEdge else 1.0
    val width = (source.width * scale).roundToInt().coerceAtLeast(1)
    val height = (source.height * scale).roundToInt().coerceAtLeast(1)

    // ARGB throughout so transparent logos keep their transparency.
    val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = scaled.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.drawImage(source, 0, 0, width, height, null)
    graphics.dispose()

    val output = ByteArrayOutputStream()
    return if (ImageIO.write(scaled, "png", output)) output.toByteArray() else null
}
