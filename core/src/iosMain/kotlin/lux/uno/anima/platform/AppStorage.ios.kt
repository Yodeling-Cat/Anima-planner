package lux.uno.anima.platform

import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual fun appDataDirectory(): String {
    val directories = NSFileManager.defaultManager.URLsForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomains = NSUserDomainMask,
    )
    val base = (directories.firstOrNull() as? NSURL)?.path ?: NSTemporaryDirectory()
    return "$base/Anima"
}
