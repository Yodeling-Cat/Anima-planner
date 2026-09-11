package lux.uno.anima

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Everything the server needs to start, read from the environment so nothing that varies
 * between a laptop and a host ends up committed.
 */
data class ServerConfig(
    val port: Int,
    val host: String,
    val dataDirectory: Path,
    /** Closes registration once the accounts that should exist do. */
    val registrationOpen: Boolean,
) {
    val databaseFile: Path get() = dataDirectory.resolve("anima.db")
    val blobDirectory: Path get() = dataDirectory.resolve("blobs")

    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv) = ServerConfig(
            port = env("ANIMA_PORT")?.toIntOrNull() ?: 8080,
            host = env("ANIMA_HOST") ?: "0.0.0.0",
            dataDirectory = Paths.get(env("ANIMA_DATA_DIR") ?: "data").toAbsolutePath(),
            // Defaults closed. A server that quietly accepts signups is a server that
            // eventually holds someone else's data by accident.
            registrationOpen = env("ANIMA_REGISTRATION_OPEN")?.toBooleanStrictOrNull() ?: false,
        )
    }
}

/** Ceilings that keep one bad client from filling the disk. */
object Limits {
    const val MAX_CHANGES_PER_REQUEST = 1_000
    const val MAX_SYNC_BODY_BYTES = 16 * 1024 * 1024
    const val MAX_PAYLOAD_BYTES = 256 * 1024
    const val MAX_BLOB_BYTES = 5 * 1024 * 1024
    const val FEED_PAGE_SIZE = 500
    const val MAX_ID_LENGTH = 64
}
