package lux.uno.anima.platform

import android.content.Context
import java.io.File

/**
 * Android has no ambient way to find the app's sandbox, so the Application hands us a
 * Context once at startup and everything downstream stays Context-free.
 */
object AndroidPlatform {
    @Volatile
    internal var filesDirectory: String? = null

    fun initialize(context: Context) {
        filesDirectory = File(context.applicationContext.filesDir, "anima").absolutePath
    }
}

actual fun appDataDirectory(): String = AndroidPlatform.filesDirectory
    ?: error("AndroidPlatform.initialize(context) must run before any storage access")
