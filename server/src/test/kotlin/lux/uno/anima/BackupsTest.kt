package lux.uno.anima

import kotlinx.coroutines.test.runTest
import lux.uno.anima.server.backup.Backups
import lux.uno.anima.server.db.Database
import lux.uno.anima.server.db.map
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backup is only worth having if it is readable, so these tests open what it wrote
 * rather than checking that a file of the right name appeared.
 */
class BackupsTest {

    private val directory: Path = Files.createTempDirectory("anima-backup")
    private val databaseFile = directory.resolve("anima.db")
    private val blobDirectory = directory.resolve("blobs")
    private val backupDirectory = directory.resolve("backups")

    private var today = LocalDate.of(2026, 9, 11)

    private fun backups(retentionDays: Int = 14) = Backups(
        databaseFile = databaseFile,
        blobDirectory = blobDirectory,
        directory = backupDirectory,
        retentionDays = retentionDays,
        today = { today },
    )

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `the copy holds the rows the database held`() = runTest {
        val database = Database(databaseFile)
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO users (id, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
            ).use {
                it.setString(1, "user-1")
                it.setString(2, "saved@example.com")
                it.setString(3, "hash")
                it.setLong(4, 0)
                it.executeUpdate()
            }
        }

        val copy = assertNotNull(backups().runOnce(), "no backup was written")
        database.close()

        assertEquals(listOf("saved@example.com"), emailsIn(copy))
    }

    @Test
    fun `a second run on the same day writes nothing`() = runTest {
        Database(databaseFile).use { }
        assertNotNull(backups().runOnce())
        assertNull(backups().runOnce(), "today's backup was taken twice")
    }

    @Test
    fun `blobs are linked rather than copied`() = runTest {
        Database(databaseFile).use { }
        val blob = blobDirectory.resolve("user-1").resolve("a".repeat(64))
        Files.createDirectories(blob.parent)
        Files.write(blob, "icon bytes".toByteArray())

        assertNotNull(backups().runOnce())

        val linked = backupDirectory.resolve("blobs-$today").resolve("user-1").resolve("a".repeat(64))
        assertTrue(Files.exists(linked), "the blob was not captured")
        assertEquals("icon bytes", Files.readString(linked))

        // The point of a link: losing the live copy does not lose the backup's.
        Files.delete(blob)
        assertEquals("icon bytes", Files.readString(linked))
    }

    @Test
    fun `copies older than the retention window are dropped`() = runTest {
        Database(databaseFile).use { }
        assertNotNull(backups(retentionDays = 3).runOnce())
        val first = backupDirectory.resolve("anima-$today.db")

        today = today.plusDays(4)
        assertNotNull(backups(retentionDays = 3).runOnce())

        assertFalse(Files.exists(first), "a copy past the retention window survived")
        assertTrue(Files.exists(backupDirectory.resolve("anima-$today.db")))
    }

    @Test
    fun `a database that cannot be read is logged rather than thrown`() = runTest {
        // A precaution that takes the server down with it is worse than no precaution.
        val rubbish = directory.resolve("rubbish.db")
        Files.write(rubbish, "this is not a database".toByteArray())

        assertNull(
            Backups(
                databaseFile = rubbish,
                blobDirectory = blobDirectory,
                directory = backupDirectory,
                retentionDays = 14,
                today = { today },
            ).runOnce(),
        )
    }

    private fun emailsIn(file: Path): List<String> =
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT email FROM users").map { it.getString(1) }
            }
        }
}
