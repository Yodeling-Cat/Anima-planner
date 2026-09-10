package lux.uno.anima.finance.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.platform.appDataDirectory
import lux.uno.anima.platform.platformFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Persists the dataset as one pretty-printed JSON document. A single file keeps the data
 * portable and hand-inspectable; the volumes here (tens of subscriptions) never justify a
 * database, and a corrupt read falls back to "no data" rather than crashing the app.
 */
class JsonFileFinanceStore(
    private val fileSystem: FileSystem = platformFileSystem(),
    directory: String = appDataDirectory(),
    fileName: String = "finance.json",
) : FinanceStore {

    private val directoryPath: Path = directory.toPath()
    private val filePath: Path = directoryPath.resolve(fileName)
    private val temporaryPath: Path = directoryPath.resolve("$fileName.tmp")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun load(): FinanceData? = withContext(Dispatchers.Default) {
        if (!fileSystem.exists(filePath)) return@withContext null
        runCatching {
            val text = fileSystem.read(filePath) { readUtf8() }
            json.decodeFromString(FinanceData.serializer(), text)
        }.getOrNull()
    }

    override suspend fun save(data: FinanceData) = withContext(Dispatchers.Default) {
        fileSystem.createDirectories(directoryPath)
        val text = json.encodeToString(FinanceData.serializer(), data)
        // Write beside the real file first so a failure mid-write cannot truncate good data.
        fileSystem.write(temporaryPath) { writeUtf8(text) }
        fileSystem.atomicMove(temporaryPath, filePath)
    }
}
