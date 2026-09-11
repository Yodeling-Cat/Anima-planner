package lux.uno.anima.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import lux.uno.anima.platform.appDataDirectory
import lux.uno.anima.platform.platformFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Keeps the sync state in its own small JSON file beside the dataset.
 *
 * A corrupt or hand-edited file reads as "no state", which starts the device over with a
 * new node id and a cursor of zero. That is a full re-pull rather than a loss: every record
 * comes back from the server, and anything this device holds that the server does not is
 * pushed under the new id.
 */
class JsonFileSyncStateStore(
    private val fileSystem: FileSystem = platformFileSystem(),
    directory: String = appDataDirectory(),
    fileName: String = "sync-state.json",
) : SyncStateStore {

    private val directoryPath: Path = directory.toPath()
    private val filePath: Path = directoryPath.resolve(fileName)
    private val temporaryPath: Path = directoryPath.resolve("$fileName.tmp")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun load(): SyncState? = withContext(Dispatchers.Default) {
        if (!fileSystem.exists(filePath)) return@withContext null
        runCatching {
            json.decodeFromString(SyncState.serializer(), fileSystem.read(filePath) { readUtf8() })
        }.getOrNull()
    }

    override suspend fun save(state: SyncState) = withContext(Dispatchers.Default) {
        fileSystem.createDirectories(directoryPath)
        val text = json.encodeToString(SyncState.serializer(), state)
        // Written beside the real file first, so losing power mid-write cannot leave a
        // half-written node id behind — which would read as corrupt and reset the device.
        fileSystem.write(temporaryPath) { writeUtf8(text) }
        fileSystem.atomicMove(temporaryPath, filePath)
    }
}
