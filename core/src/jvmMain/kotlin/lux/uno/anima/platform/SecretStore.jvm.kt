package lux.uno.anima.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

actual fun createSecretStore(): SecretStore = FileSecretStore()

/**
 * Desktop has no keystore reachable without shipping native code, so the token lives in a
 * file that only this user account may open — owner-only mode bits on macOS and Linux, a
 * single-entry ACL on Windows.
 *
 * This is plaintext at rest, and deliberately so. A desktop app holds no secret of its own
 * that an attacker already running as the user could not read just as easily, so encrypting
 * the file with a key stored beside it would buy nothing but the appearance of safety. It is
 * the same posture as the credential files kept by `gh`, `aws` and `docker`. What actually
 * limits the damage is on the server: sessions are revocable, so a leaked token can be cut
 * off without the user changing anything here.
 *
 * Binding to the OS keyring (`security` on macOS, `secret-tool` on Linux, DPAPI on Windows)
 * is the upgrade, and every one of those costs either a subprocess or a native dependency.
 */
internal class FileSecretStore(
    /** Injectable so the permission checks below can be asserted against a scratch dir. */
    private val directory: Path = Paths.get(appDataDirectory()),
) : SecretStore {

    override val protection = SecretProtection.FILE_PERMISSIONS

    private val file: Path = directory.resolve("credentials.json")
    private val temporary: Path = directory.resolve("credentials.json.tmp")

    /** Serialises read-modify-write, so two logins cannot drop each other's entry. */
    private val lock = Mutex()

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        lock.withLock { read()[key] }
    }

    override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        lock.withLock { write(read() + (key to value)) }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        lock.withLock { write(read() - key) }
    }

    private fun read(): Map<String, String> {
        if (!Files.exists(file)) return emptyMap()
        // A hand-edited or truncated file means signed out, not a crash on launch.
        return runCatching {
            json.decodeFromString(serializer, Files.readString(file))
        }.getOrDefault(emptyMap())
    }

    private fun write(entries: Map<String, String>) {
        Files.createDirectories(directory)
        restrict(directory)

        // Lock the file down before anything secret goes into it, then swap it into place.
        // Explicit permissions and ACL entries travel with the file across the move.
        Files.deleteIfExists(temporary)
        Files.createFile(temporary)
        restrict(temporary)
        Files.writeString(temporary, json.encodeToString(serializer, entries))
        runCatching {
            Files.move(
                temporary, file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }

    /**
     * Best effort by design: a filesystem that supports neither view (a network share, some
     * container mounts) leaves the file readable by anyone who can already read the user's
     * home directory, and there is nothing useful to do about that beyond not pretending
     * otherwise — which is what [protection] is for.
     */
    private fun restrict(path: Path) {
        val isDirectory = Files.isDirectory(path)

        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.let { posix ->
            val permissions = if (isDirectory) {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
            runCatching { posix.setPermissions(permissions) }
            return
        }

        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
        runCatching {
            // Replacing the list rather than appending drops the inherited entries that
            // would otherwise let every local administrator read this.
            val owner = Files.getOwner(path)
            acl.acl = listOf(
                AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(AclEntryPermission.entries.toSet())
                    .build(),
            )
        }
    }
}
