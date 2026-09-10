package lux.uno.anima.platform

/**
 * Directory the app owns for its own files. Each platform answers with the place its
 * sandbox rules expect: app files dir on Android, Application Support on iOS,
 * a dotfolder in the user's home on desktop.
 */
expect fun appDataDirectory(): String

/** Multiplatform id generator — good enough to key local records. */
fun newId(prefix: String): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    val body = buildString { repeat(12) { append(chars.random()) } }
    return "$prefix-$body"
}
