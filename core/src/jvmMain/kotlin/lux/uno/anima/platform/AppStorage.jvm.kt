package lux.uno.anima.platform

import java.io.File

actual fun appDataDirectory(): String {
    val home = System.getProperty("user.home") ?: "."
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val base = when {
        os.contains("win") -> System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path
        os.contains("mac") -> File(home, "Library/Application Support").path
        else -> System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path
    }
    return File(base, "Anima").absolutePath
}
