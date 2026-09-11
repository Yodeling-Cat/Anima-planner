package lux.uno.anima.dev

import kotlinx.serialization.Serializable

/**
 * Which server the app talks to, and which copy of the data it keeps.
 *
 * Environments never share storage. Every file name carries [storageSuffix], so switching
 * to Development cannot show — or worse, later push — anything from Production. That
 * separation is the whole point: a sandbox you can wreck freely is only useful if wrecking
 * it is guaranteed not to touch the real thing.
 */
@Serializable
data class AppEnvironment(
    val id: String,
    val label: String,
    /** What the switch actually does, in the words of someone about to tap it. */
    val description: String,
    /** Null means this environment never syncs; the device is the only copy. */
    val serverUrl: String?,
    /**
     * The shared secret a private server asks for when creating an account on it.
     *
     * Never part of a built-in environment: it belongs to whoever runs that server, so it
     * is typed on the device beside the address and kept in `EnvironmentStore`. Null means
     * none has been entered, which is correct for a public instance and for a server that
     * asks for none.
     */
    val joinPassword: String? = null,
    /**
     * Appended to every file and directory this environment owns.
     *
     * Production's is empty on purpose. The app shipped before environments existed, so the
     * files already on disk are the real ones — giving Production a suffix would mean moving
     * a user's actual data on upgrade, and a migration that can go wrong is a poor trade for
     * a tidier filename.
     */
    val storageSuffix: String,
) {
    val syncs: Boolean get() = serverUrl != null

    val dataFileName: String get() = "finance$storageSuffix.json"
    val iconFolderName: String get() = "icons$storageSuffix"
    val syncStateFileName: String get() = "sync-state$storageSuffix.json"
    val handledRemindersFileName: String get() = "handled-reminders$storageSuffix.json"

    /** Keeps one environment's session out of another's. */
    val sessionTokenKey: String get() = "session-token$storageSuffix"
}

object Environments {

    val Production = AppEnvironment(
        id = "prod",
        label = "Production",
        description = "Your real data, synced to the live server.",
        serverUrl = "https://anima.lux.uno",
        storageSuffix = "",
    )

    val Development = AppEnvironment(
        id = "dev",
        label = "Development",
        description = "A separate copy, synced to a server you are running.",
        // Points at the machine running the server by default, which on the Android
        // emulator is not localhost — see localhostForDevelopment().
        serverUrl = "http://${localhostForDevelopment()}:8080",
        storageSuffix = "-dev",
    )

    val Sandbox = AppEnvironment(
        id = "local",
        label = "On device only",
        description = "A scratch copy that never leaves this device and never syncs.",
        serverUrl = null,
        storageSuffix = "-local",
    )

    /** Production first: the list reads as "safest at the top". */
    val all: List<AppEnvironment> = listOf(Production, Development, Sandbox)

    val default: AppEnvironment = Production

    fun byId(id: String): AppEnvironment = all.firstOrNull { it.id == id } ?: default
}

/**
 * The host a development server is reachable at from this device.
 *
 * The Android emulator forwards `10.0.2.2` to the host machine's loopback, so `localhost`
 * there means the emulator itself and nothing is listening. Getting this wrong costs an
 * afternoon of "why does it not connect", which is why it is a platform decision rather
 * than something to remember to type.
 */
expect fun localhostForDevelopment(): String
