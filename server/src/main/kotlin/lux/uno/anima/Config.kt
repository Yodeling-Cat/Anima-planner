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
    /**
     * The shared secret someone must present to create an account here — the "join
     * password", never an account password, and the two must stay distinguishable
     * everywhere they are named.
     *
     * Null means there is none, which on a private instance means no one new can join at
     * all. Optional, but a private server without one has only its obscurity between it
     * and a stranger who finds the address.
     */
    val joinPassword: String?,
    /**
     * Whether this deployment is a service other people subscribe to.
     *
     * It does **not** mean "registration is open" — [joinPassword] decides that. What it
     * declares is the obligations that come with holding strangers' data: erasure, export,
     * per-user limits, and a named controller. See the hosting section of `plan/later.md`.
     */
    val publicInstance: Boolean,
    /**
     * Whether `X-Forwarded-*` may be believed.
     *
     * Only true when something in front of the server is guaranteed to overwrite those
     * headers. Trusting them on a directly exposed port would let any client name its own
     * address and step around the login rate limit by changing it every attempt.
     */
    val trustForwardedHeaders: Boolean = false,
    /**
     * Whether the server takes its own periodic backup.
     *
     * On by default, and that is the point: the host's volume snapshots are somebody's
     * deliberate decision, while a self-hoster's are whatever their provider happens to do.
     * A precaution nobody has to switch on is the only kind that is reliably on.
     */
    val backupEnabled: Boolean = true,
    /** How long a copy — and so anything deleted from the live data — survives. */
    val backupRetentionDays: Int = 14,
    val backupIntervalHours: Int = 24,
) {
    val databaseFile: Path get() = dataDirectory.resolve("anima.db")
    val blobDirectory: Path get() = dataDirectory.resolve("blobs")
    val backupDirectory: Path get() = dataDirectory.resolve("backups")

    /**
     * Whether this server will create an account for someone presenting [offered].
     *
     * One rule, in one place, because "who may join" is the question a mistake here answers
     * wrongly. A join password always applies when set — a public instance may still want
     * one during a soft launch — and only its absence lets [publicInstance] decide between
     * admitting everyone and admitting nobody.
     */
    fun admits(offered: String?): Admission = when {
        joinPassword != null ->
            if (secretsMatch(offered.orEmpty(), joinPassword)) Admission.ALLOWED
            else Admission.WRONG_JOIN_PASSWORD

        publicInstance -> Admission.ALLOWED
        else -> Admission.CLOSED
    }

    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv) = ServerConfig(
            port = env("ANIMA_PORT")?.toIntOrNull() ?: 8080,
            host = env("ANIMA_HOST") ?: "0.0.0.0",
            dataDirectory = Paths.get(env("ANIMA_DATA_DIR") ?: "data").toAbsolutePath(),
            // Blank is the same as unset. An empty string as a shared secret would admit
            // anybody who sent an empty field, which is the opposite of what typing one in
            // is meant to achieve.
            joinPassword = env("ANIMA_JOIN_PASSWORD")?.takeIf { it.isNotBlank() },
            // Defaults private. Declaring a public service is a decision someone makes on
            // purpose, never a state a deployment drifts into.
            publicInstance = env("ANIMA_PUBLIC_INSTANCE")?.toBooleanStrictOrNull() ?: false,
            // Also defaults closed, and for the same reason: the unsafe setting is the one
            // that has to be asked for.
            trustForwardedHeaders = env("ANIMA_TRUST_FORWARDED_HEADERS")?.toBooleanStrictOrNull() ?: false,
            backupEnabled = env("ANIMA_BACKUP_ENABLED")?.toBooleanStrictOrNull() ?: true,
            backupRetentionDays = env("ANIMA_BACKUP_RETENTION_DAYS")?.toIntOrNull() ?: 14,
            backupIntervalHours = env("ANIMA_BACKUP_INTERVAL_HOURS")?.toIntOrNull() ?: 24,
        )
    }
}

/** The three answers to "may this person create an account here". */
enum class Admission { ALLOWED, WRONG_JOIN_PASSWORD, CLOSED }

/**
 * Compares without letting how long it took say how much of it matched.
 *
 * A join password is shared, typed by hand, and therefore shorter and more guessable than
 * anything generated. The login rate limiter is the real defence; this removes the free
 * side channel that would otherwise let each attempt reveal a prefix.
 */
private fun secretsMatch(offered: String, expected: String): Boolean =
    java.security.MessageDigest.isEqual(offered.toByteArray(), expected.toByteArray())

/** Ceilings that keep one bad client from filling the disk. */
object Limits {
    const val MAX_CHANGES_PER_REQUEST = 1_000
    const val MAX_SYNC_BODY_BYTES = 16 * 1024 * 1024
    const val MAX_PAYLOAD_BYTES = 256 * 1024
    const val MAX_BLOB_BYTES = 5 * 1024 * 1024
    const val FEED_PAGE_SIZE = 500
    const val MAX_ID_LENGTH = 64
}
