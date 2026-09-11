package lux.uno.anima.platform

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `FileSecretStore.restrict` is best-effort and wrapped in `runCatching`, which means a
 * silent failure looks exactly like success. The session token is the one thing on a
 * desktop that is worth stealing, so the claim `SecretProtection.FILE_PERMISSIONS` makes
 * has to be checked against what actually reached the disk.
 */
class FileSecretStoreTest {

    private val directory: Path = Files.createTempDirectory("anima-secrets")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() = directory.deleteRecursively()

    private fun store() = FileSecretStore(directory)

    private fun credentials(): Path = directory.resolve("credentials.json")

    @Test
    fun `a stored value comes back`() = runTest {
        store().put("session-token", "a-token")

        assertEquals("a-token", store().get("session-token"))
    }

    @Test
    fun `a second key does not displace the first`() = runTest {
        // Both the token and the address live here, and a login writes them one after the
        // other through separate read-modify-write cycles.
        val store = store()
        store.put("session-token", "a-token")
        store.put("account-email", "someone@example.com")

        assertEquals("a-token", store.get("session-token"))
        assertEquals("someone@example.com", store.get("account-email"))
    }

    @Test
    fun `removing takes the entry and leaves the rest`() = runTest {
        val store = store()
        store.put("session-token", "a-token")
        store.put("account-email", "someone@example.com")

        store.remove("session-token")

        assertNull(store.get("session-token"))
        assertEquals("someone@example.com", store.get("account-email"))
    }

    @Test
    fun `a file that cannot be parsed reads as signed out rather than crashing`() = runTest {
        Files.createDirectories(directory)
        Files.writeString(credentials(), "{ this is not json")

        assertNull(store().get("session-token"))
    }

    @Test
    fun `nothing but the owner can read the credentials file`() = runTest {
        store().put("session-token", "a-token")
        val path = credentials()

        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)

        when {
            posix != null -> assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                posix.readAttributes().permissions(),
                "the file should be 0600",
            )

            acl != null -> {
                // Inherited entries for BUILTIN\Administrators or NT AUTHORITY\SYSTEM mean
                // the replacement did not take, and every local admin can read the token.
                val owner = Files.getOwner(path)
                val entries = acl.acl
                assertEquals(1, entries.size, "expected one entry, got ${entries.map { it.principal().name }}")
                assertEquals(owner, entries.single().principal())
                assertEquals(AclEntryType.ALLOW, entries.single().type())
            }

            // Neither view means a filesystem that can express no protection at all. Worth
            // failing on rather than passing quietly, because the claim is then untrue.
            else -> assertTrue(false, "no permission view on ${path.fileSystem}; the file is unprotected")
        }
    }
}
