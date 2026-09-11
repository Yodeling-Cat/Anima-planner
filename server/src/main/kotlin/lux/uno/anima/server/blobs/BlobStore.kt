package lux.uno.anima.server.blobs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lux.uno.anima.server.db.Database
import lux.uno.anima.server.db.map
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The icons subscriptions are drawn with, addressed by the SHA-256 of their bytes.
 *
 * Content addressing is what keeps binary data out of the conflict story entirely: the same
 * image uploaded from two devices lands on the same id, and an id can never refer to
 * different bytes later. So a blob is either present or it is not, and there is nothing for
 * last-write-wins to decide.
 *
 * Blobs are stored per user and deliberately not deduplicated across accounts. Sharing
 * storage between users would mean the server could tell which of them hold the same file,
 * which is a fact it has no business learning to save a few kilobytes.
 */
@OptIn(ExperimentalTime::class)
class BlobStore(private val database: Database, private val directory: Path) {

    /** Of [hashes], the ones this user has not uploaded. The client then sends only those. */
    suspend fun missing(userId: String, hashes: List<String>): List<String> {
        val valid = hashes.filter(::isHash).distinct()
        if (valid.isEmpty()) return emptyList()

        // Asked in batches because the list comes from the client and SQLite caps how many
        // parameters one statement may bind.
        val held = database.transaction { connection ->
            valid.chunked(PARAMETER_LIMIT).flatMapTo(mutableSetOf()) { batch ->
                connection.prepareStatement(
                    "SELECT hash FROM blobs WHERE user_id = ? AND hash IN " +
                        batch.joinToString(",", "(", ")") { "?" },
                ).use {
                    it.setString(1, userId)
                    batch.forEachIndexed { index, hash -> it.setString(index + 2, hash) }
                    it.executeQuery().map { rows -> rows.getString(1) }
                }
            }
        }
        return valid.filterNot { it in held }
    }

    /**
     * False when [bytes] do not hash to [hash]. The client names the address and the server
     * checks it, so a client cannot make one id resolve to something else later.
     */
    suspend fun put(userId: String, hash: String, bytes: ByteArray): Boolean {
        if (!isHash(hash) || sha256(bytes) != hash) return false

        withContext(Dispatchers.IO) {
            val target = pathFor(userId, hash)
            Files.createDirectories(target.parent)
            // Same bytes, same name — a repeat upload has nothing to change.
            if (!Files.exists(target)) {
                val temporary = target.resolveSibling("$hash.part")
                Files.write(temporary, bytes)
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO blobs (user_id, hash, size, created_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(user_id, hash) DO NOTHING",
            ).use {
                it.setString(1, userId)
                it.setString(2, hash)
                it.setLong(3, bytes.size.toLong())
                it.setLong(4, Clock.System.now().toEpochMilliseconds())
                it.executeUpdate()
            }
        }
        return true
    }

    suspend fun get(userId: String, hash: String): ByteArray? {
        if (!isHash(hash)) return null
        return withContext(Dispatchers.IO) {
            val path = pathFor(userId, hash)
            if (Files.exists(path)) Files.readAllBytes(path) else null
        }
    }

    /**
     * The hash is a filename, so it is checked before it is one. Sixty-four hex characters
     * cannot contain a separator or a `..`, which is the whole of the traversal defence.
     */
    private fun pathFor(userId: String, hash: String): Path =
        directory.resolve(userId).resolve(hash)

    private companion object {
        /** Comfortably under SQLite's default ceiling of 999 bound parameters. */
        const val PARAMETER_LIMIT = 500

        val HASH = Regex("[0-9a-f]{64}")

        fun isHash(value: String) = HASH.matches(value)

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
