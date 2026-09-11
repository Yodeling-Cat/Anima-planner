package lux.uno.anima.platform

import android.content.Context
import java.io.File

/**
 * Android has no ambient way to reach the app's sandbox or its notification services, so the
 * Activity hands us an application Context once at startup and everything downstream stays
 * Context-free.
 */
object AndroidPlatform {
    @Volatile
    internal var applicationContext: Context? = null

    @Volatile
    internal var filesDirectory: String? = null

    fun initialize(context: Context) {
        val application = context.applicationContext
        applicationContext = application
        filesDirectory = File(application.filesDir, "anima").absolutePath
    }

    internal fun requireContext(): Context = applicationContext
        ?: error("AndroidPlatform.initialize(context) must run before any platform access")
}

actual fun appDataDirectory(): String = AndroidPlatform.filesDirectory
    ?: error("AndroidPlatform.initialize(context) must run before any storage access")
