package lux.uno.anima.finance.store

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalForeignApi::class)
actual fun resizeImageToPng(bytes: ByteArray, maxEdge: Int): ByteArray? {
    if (bytes.isEmpty()) return null
    val image = UIImage.imageWithData(bytes.toNSData()) ?: return null

    val (sourceWidth, sourceHeight) = image.size.useContents { width to height }
    if (sourceWidth <= 0.0 || sourceHeight <= 0.0) return null

    val longestEdge = max(sourceWidth, sourceHeight)
    val scale = if (longestEdge > maxEdge) maxEdge.toDouble() / longestEdge else 1.0
    val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1).toDouble()
    val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1).toDouble()

    // opaque = false keeps transparency; scale = 1.0 keeps the output at exactly these pixels
    // rather than multiplying them by the device scale factor.
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(width, height), false, 1.0)
    image.drawInRect(CGRectMake(0.0, 0.0, width, height))
    val scaled = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()

    return scaled?.let { UIImagePNGRepresentation(it) }?.toByteArray()
}

// NSData.create(bytes:length:) is a beta interop declaration. Opted in here rather than
// module-wide, so a future one shows up as a warning instead of passing unnoticed.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return result
}
