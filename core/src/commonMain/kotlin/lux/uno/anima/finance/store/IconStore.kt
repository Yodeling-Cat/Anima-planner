package lux.uno.anima.finance.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lux.uno.anima.platform.appDataDirectory
import lux.uno.anima.platform.platformFileSystem
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Longest edge an uploaded icon is kept at. Anything larger is wasted on a 52dp tile. */
const val ICON_MAX_EDGE: Int = 192

/** What a content-addressed id looks like: the SHA-256 of the stored bytes, in hex. */
private val CONTENT_ID = Regex("[0-9a-f]{64}")

fun isContentAddressed(id: String): Boolean = CONTENT_ID.matches(id)

/**
 * Holds the images used as subscription icons, one PNG per file beside the dataset.
 *
 * Icons are **content-addressed**: the file is named after the SHA-256 of its own bytes.
 * That is what keeps binary data out of the conflict story altogether. The same picture
 * added on a phone and on a desktop lands on the same id, so the two never have to be
 * reconciled, and an id can never come to mean different bytes later — which means a cached
 * image is never stale and an upload is never ambiguous.
 *
 * The hash is taken of the *resized* bytes, not the original, so the id always describes
 * exactly what is on disk and what the server will be asked to verify.
 */
class IconStore(
    private val fileSystem: FileSystem = platformFileSystem(),
    directory: String = appDataDirectory(),
    /** Carries the environment suffix, so a sandbox never shows production's icons. */
    folderName: String = "icons",
) {
    private val iconDirectory: Path = directory.toPath().resolve(folderName)

    private fun pathFor(id: String): Path = iconDirectory.resolve("$id.png")

    /**
     * Shrinks [originalBytes] to at most [ICON_MAX_EDGE] on its longest edge, writes it, and
     * returns its id — or null when the bytes are not an image this platform can read.
     */
    suspend fun store(originalBytes: ByteArray): String? = withContext(Dispatchers.Default) {
        val optimised = resizeImageToPng(originalBytes, ICON_MAX_EDGE) ?: return@withContext null
        write(optimised)
    }

    /**
     * Files bytes that have already been through [store] somewhere else — a download, or the
     * migration below. Refuses anything whose hash does not match [id], because an id that
     * stopped describing its contents would poison every device that later asked for it.
     */
    suspend fun put(id: String, bytes: ByteArray): Boolean = withContext(Dispatchers.Default) {
        if (!isContentAddressed(id) || idFor(bytes) != id) return@withContext false
        write(bytes)
        true
    }

    suspend fun load(id: String): ByteArray? = withContext(Dispatchers.Default) {
        val path = pathFor(id)
        if (!fileSystem.exists(path)) return@withContext null
        runCatching { fileSystem.read(path) { readByteArray() } }.getOrNull()
    }

    /** Every icon on this device, by id. */
    suspend fun stored(): Set<String> = withContext(Dispatchers.Default) {
        if (!fileSystem.exists(iconDirectory)) return@withContext emptySet()
        runCatching {
            fileSystem.list(iconDirectory)
                .map { it.name.removeSuffix(".png") }
                .toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun delete(id: String) = withContext(Dispatchers.Default) {
        runCatching { fileSystem.delete(pathFor(id)) }
        Unit
    }

    /** Removes icon files no subscription points at any more. */
    suspend fun pruneExcept(keep: Set<String>) = withContext(Dispatchers.Default) {
        if (!fileSystem.exists(iconDirectory)) return@withContext
        runCatching {
            fileSystem.list(iconDirectory).forEach { path ->
                val id = path.name.removeSuffix(".png")
                if (id !in keep) fileSystem.delete(path)
            }
        }
        Unit
    }

    /**
     * Renames every icon still carrying a random id to the hash of its contents, and reports
     * what moved where so the records pointing at them can be corrected.
     *
     * Safe to run again: a file already named after its own hash is left alone, so an
     * interrupted migration simply carries on where it stopped. Nothing is deleted until the
     * new name is on disk.
     */
    suspend fun migrateToContentAddressing(): Map<String, String> = withContext(Dispatchers.Default) {
        if (!fileSystem.exists(iconDirectory)) return@withContext emptyMap()

        val moved = mutableMapOf<String, String>()
        val files = runCatching { fileSystem.list(iconDirectory) }.getOrDefault(emptyList())
        for (path in files) {
            val oldId = path.name.removeSuffix(".png")
            if (isContentAddressed(oldId)) continue

            val bytes = runCatching { fileSystem.read(path) { readByteArray() } }.getOrNull()
            if (bytes == null) continue

            val newId = idFor(bytes)
            write(bytes)
            runCatching { fileSystem.delete(path) }
            moved[oldId] = newId
        }
        moved
    }

    private fun write(bytes: ByteArray): String {
        val id = idFor(bytes)
        fileSystem.createDirectories(iconDirectory)
        // Same bytes, same name: a repeat is already exactly the file that would be written.
        val path = pathFor(id)
        if (!fileSystem.exists(path)) fileSystem.write(path) { write(bytes) }
        return id
    }

    private fun idFor(bytes: ByteArray): String = bytes.toByteString().sha256().hex()
}
