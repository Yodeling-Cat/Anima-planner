package lux.uno.anima.finance.reminders

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import lux.uno.anima.platform.appDataDirectory
import lux.uno.anima.platform.platformFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Which reminders this device has already shown, or already handed to its OS.
 *
 * Device state, not user data, which is why it sits here rather than in `FinanceData`:
 * a reminder is delivered by one device's notification system, so whether it has been
 * delivered is only ever true of that device. It also only grows, and a dataset that
 * rewrites itself every time a notification fires is a dataset that is always "edited"
 * and always asking to be synced.
 *
 * The accepted cost is that a reminder dismissed on the phone appears again on the
 * desktop. That is the right trade: mildly repetitive beats a record that churns.
 */
interface HandledReminderStore {
    suspend fun load(): Set<String>
    suspend fun save(ids: Set<String>)
}

/** Store used by tests, and by any platform with nowhere to write. */
class InMemoryHandledReminderStore(private var ids: Set<String> = emptySet()) : HandledReminderStore {
    override suspend fun load(): Set<String> = ids
    override suspend fun save(ids: Set<String>) {
        this.ids = ids
    }
}

/**
 * Keeps the list in its own small JSON file beside the dataset.
 *
 * A missing or unreadable file reads as "nothing handled yet". The cost of getting that
 * wrong is one repeated notification, so there is nothing here worth crashing a launch over.
 */
class JsonFileHandledReminderStore(
    private val fileSystem: FileSystem = platformFileSystem(),
    directory: String = appDataDirectory(),
    fileName: String = "handled-reminders.json",
) : HandledReminderStore {

    private val directoryPath: Path = directory.toPath()
    private val filePath: Path = directoryPath.resolve(fileName)
    private val temporaryPath: Path = directoryPath.resolve("$fileName.tmp")

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = SetSerializer(String.serializer())

    override suspend fun load(): Set<String> = withContext(Dispatchers.Default) {
        if (!fileSystem.exists(filePath)) return@withContext emptySet()
        runCatching {
            json.decodeFromString(serializer, fileSystem.read(filePath) { readUtf8() })
        }.getOrDefault(emptySet())
    }

    override suspend fun save(ids: Set<String>) = withContext(Dispatchers.Default) {
        fileSystem.createDirectories(directoryPath)
        val text = json.encodeToString(serializer, ids)
        fileSystem.write(temporaryPath) { writeUtf8(text) }
        fileSystem.atomicMove(temporaryPath, filePath)
    }
}
