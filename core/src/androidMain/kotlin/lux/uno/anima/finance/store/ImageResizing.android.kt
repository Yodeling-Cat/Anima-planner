package lux.uno.anima.finance.store

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

actual fun resizeImageToPng(bytes: ByteArray, maxEdge: Int): ByteArray? {
    // Measure first, then decode at a power-of-two subsample so a large photo never has to be
    // fully materialised in memory just to become a 192px icon.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxEdge) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

    val longestEdge = max(decoded.width, decoded.height)
    val scaled = if (longestEdge > maxEdge) {
        val scale = maxEdge.toDouble() / longestEdge
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        decoded
    }

    val output = ByteArrayOutputStream()
    val ok = scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
    if (scaled !== decoded) decoded.recycle()
    return if (ok) output.toByteArray() else null
}
