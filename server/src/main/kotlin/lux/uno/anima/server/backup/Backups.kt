package lux.uno.anima.server.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * A consistent copy of the server's whole state, taken on a timer by the server itself.
 *
 * This is deliberately not disaster recovery — the host's own volume snapshots cover losing
 * the disk, and they cover it better. What they cannot do is restore *one app* to *one
 * moment*: rolling back a volume rolls back everything sharing it. These copies exist for
 * the other kind of accident, the logical one — a bad deploy, a bug that deletes records,
 * someone asking for yesterday back — where the fix should touch nothing but Anima.
 *
 * That division is why writing them to the same volume is correct rather than lazy. Volume
 * loss is already somebody else's job. Doing it in-process is the same reasoning applied to
 * the two ways Anima is hosted: a self-hoster who never reads a word of documentation still
 * ends up with working, verified backups, and no second container to keep alive.
 */
class Backups(
    private val databaseFile: Path,
    private val blobDirectory: Path,
    private val directory: Path,
    private val retentionDays: Int,
    private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
) {

    private val log: Logger = LoggerFactory.getLogger(Backups::class.java)

    /**
     * Takes one backup, unless today's already exists.
     *
     * Returns the database copy it wrote, or null when there was nothing to do. Failure is
     * logged and swallowed: a server that will not serve because it could not back itself up
     * has turned a precaution into the outage.
     */
    suspend fun runOnce(force: Boolean = false): Path? = withContext(Dispatchers.IO) {
        try {
            val date = today()
            val target = directory.resolve("anima-$date.db")
            if (!force && Files.exists(target)) return@withContext null

            Files.createDirectories(directory)
            val snapshot = vacuumInto(directory.resolve("anima-$date.db.part"))
            verify(snapshot)
            Files.move(snapshot, target, StandardCopyOption.REPLACE_EXISTING)

            val blobs = snapshotBlobs(date)
            prune(date)

            log.info(
                "backup {} written: {} bytes of database and {} blob files linked",
                date, Files.size(target), blobs,
            )
            target
        } catch (failure: Throwable) {
            log.error("backup failed", failure)
            null
        }
    }

    /**
     * Runs a backup whenever the newest one is older than [every], starting immediately.
     *
     * Interval since the last copy rather than a wall-clock hour, because that survives
     * restarts without either skipping a day or taking a fresh copy every time the process
     * comes up. `VACUUM INTO` reads through its own connection and does not block writers,
     * so there is no quiet hour this has to be aimed at.
     */
    fun schedule(scope: CoroutineScope, every: Duration = 24.hours) {
        scope.launch {
            while (true) {
                runOnce()
                delay(every)
            }
        }
    }

    /**
     * A transactionally consistent copy, taken through a connection of its own.
     *
     * Not the server's shared connection: `VACUUM` cannot run inside a transaction, and
     * borrowing that connection would hold the write mutex for the length of the copy. A
     * second reader is free under WAL and sees one coherent snapshot regardless of what is
     * being committed while it reads.
     */
    private fun vacuumInto(target: Path): Path {
        Files.deleteIfExists(target)
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath()}").use { connection ->
            connection.prepareStatement("VACUUM INTO ?").use { statement ->
                statement.setString(1, target.toAbsolutePath().toString())
                statement.execute()
            }
        }
        return target
    }

    /** A copy nobody has opened is not yet a backup. */
    private fun verify(file: Path) {
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { rows ->
                    val answer = if (rows.next()) rows.getString(1) else "no answer"
                    if (!answer.equals("ok", ignoreCase = true)) {
                        error("integrity check on ${file.name} said: $answer")
                    }
                }
            }
        }
    }

    /**
     * Hard links, not copies.
     *
     * Blobs are content-addressed and written once, so a link can never come to describe
     * different bytes than the day it was made, and a daily snapshot of them costs an inode
     * apiece. It also means deleting a blob from the live tree leaves the backup's copy of
     * the data intact, which is the entire point.
     */
    private fun snapshotBlobs(date: LocalDate): Int {
        if (!Files.isDirectory(blobDirectory)) return 0
        val target = directory.resolve("blobs-$date")
        if (Files.exists(target)) return countFiles(target)

        val staging = directory.resolve("blobs-$date.part")
        if (Files.exists(staging)) staging.toFile().deleteRecursively()

        var linked = 0
        Files.walk(blobDirectory).use { paths ->
            paths.forEach { source ->
                val destination = staging.resolve(blobDirectory.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    try {
                        Files.createLink(destination, source)
                    } catch (unsupported: Exception) {
                        // Some filesystems refuse links. A copy is worse but still a backup.
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                    linked++
                }
            }
        }
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        return linked
    }

    /**
     * Drops everything older than the retention window.
     *
     * The window is a promise as much as a housekeeping setting: once an account can be
     * deleted, the data of a deleted account lives on in these copies until it ages out, so
     * "how long do backups last" is the same question as "how long until erasure is really
     * finished". Keep it bounded and keep it documented.
     */
    private fun prune(date: LocalDate) {
        val cutoff = date.minusDays(retentionDays.toLong())
        Files.list(directory).use { entries ->
            entries.forEach { entry ->
                val stamp = dateOf(entry) ?: return@forEach
                if (stamp.isBefore(cutoff)) {
                    runCatching { entry.toFile().deleteRecursively() }
                        .onFailure { log.warn("could not prune {}", entry.name) }
                }
            }
        }
    }

    private fun dateOf(entry: Path): LocalDate? {
        val name = entry.name
        val text = when {
            name.startsWith("anima-") && name.endsWith(".db") ->
                name.removePrefix("anima-").removeSuffix(".db")

            name.startsWith("blobs-") && !name.endsWith(".part") ->
                name.removePrefix("blobs-")

            else -> return null
        }
        return try {
            LocalDate.parse(text)
        } catch (malformed: DateTimeParseException) {
            null
        }
    }

    private fun countFiles(root: Path): Int =
        Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).count().toInt() }
}
