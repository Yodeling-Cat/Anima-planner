package lux.uno.anima.finance.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lux.uno.anima.platform.appDataDirectory
import lux.uno.anima.platform.newId
import lux.uno.anima.platform.platformFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Longest edge an uploaded icon is kept at. Anything larger is wasted on a 52dp tile. */
const val ICON_MAX_EDGE: Int = 192

/**
 * Holds the images used as subscription icons, one PNG per file beside the dataset.
 * The JSON record only ever stores the id, so the dataset stays small and text-diffable.
 */
class IconStore(
    private val fileSystem: FileSystem = platformFileSystem(),
    directory: String = appDataDirectory(),
) {
    private val iconDirectory: Path = directory.toPath().resolve("icons")

    private fun pathFor(id: String): Path = iconDirectory.resolve("$id.png")

    /**
     * Shrinks [originalBytes] to at most [ICON_MAX_EDGE] on its longest edge, writes it, and
     * returns the new id — or null when the bytes are not an image this platform can read.
     */
    suspend fun store(originalBytes: ByteArray): String? = withContext(Dispatchers.Default) {
        val optimised = resizeImageToPng(originalBytes, ICON_MAX_EDGE) ?: return@withContext null
        val id = newId("icon")
        fileSystem.createDirectories(iconDirectory)
        fileSystem.write(pathFor(id)) { write(optimised) }
        id
    }

    suspend fun load(id: String): ByteArray? = withContext(Dispatchers.Default) {
        val path = pathFor(id)
        if (!fileSystem.exists(path)) return@withContext null
        runCatching { fileSystem.read(path) { readByteArray() } }.getOrNull()
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
}
