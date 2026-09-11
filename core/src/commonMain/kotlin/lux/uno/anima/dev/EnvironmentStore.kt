package lux.uno.anima.dev

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lux.uno.anima.platform.appDataDirectory
import lux.uno.anima.platform.platformFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

@Serializable
private data class StoredChoice(
    val activeId: String = Environments.default.id,
    /** Server addresses edited on the device, by environment id. */
    val serverUrls: Map<String, String> = emptyMap(),
)

/**
 * Remembers which environment is in use, and any server address typed on this device.
 *
 * Device configuration, so it is never synced and never suffixed — this is the file that
 * decides what the suffixes are.
 */
class EnvironmentStore(
    private val fileSystem: FileSystem = platformFileSystem(),
    directory: String = appDataDirectory(),
) {
    private val directoryPath: Path = directory.toPath()
    private val filePath: Path = directoryPath.resolve("environment.json")

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private var choice = StoredChoice()

    private val _active = MutableStateFlow(Environments.default)

    /** The environment in force. Everything downstream keys off this. */
    val active: StateFlow<AppEnvironment> = _active.asStateFlow()

    private val _isReady = MutableStateFlow(false)

    /**
     * False until [load] has answered. [active] reports the default in the meantime, which
     * is a guess — callers that open files must wait rather than act on it.
     */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    suspend fun load() {
        choice = read() ?: StoredChoice()
        _active.value = resolve(choice.activeId)
        _isReady.value = true
    }

    /** Takes effect immediately; callers observing [active] rebuild against the new one. */
    suspend fun select(id: String) {
        if (id == _active.value.id) return
        choice = choice.copy(activeId = id)
        write(choice)
        _active.value = resolve(id)
    }

    /** Points an environment at a different server — typically a laptop on the same network. */
    suspend fun setServerUrl(id: String, url: String) {
        val cleaned = url.trim().trimEnd('/')
        choice = choice.copy(
            // A blank field means "back to the built-in address", not "no server".
            serverUrls = if (cleaned.isEmpty()) choice.serverUrls - id
            else choice.serverUrls + (id to cleaned),
        )
        write(choice)
        if (id == _active.value.id) _active.value = resolve(id)
    }

    /** The address this environment would use, whether or not it is the active one. */
    fun serverUrlFor(id: String): String? = resolve(id).serverUrl

    private fun resolve(id: String): AppEnvironment {
        val environment = Environments.byId(id)
        val override = choice.serverUrls[id]
        // An environment with no server is one that must never gain one by configuration —
        // the sandbox's guarantee is that its data cannot leave the device.
        return if (override != null && environment.syncs) environment.copy(serverUrl = override)
        else environment
    }

    private suspend fun read(): StoredChoice? = withContext(Dispatchers.Default) {
        if (!fileSystem.exists(filePath)) return@withContext null
        runCatching {
            json.decodeFromString(StoredChoice.serializer(), fileSystem.read(filePath) { readUtf8() })
        }.getOrNull()
    }

    private suspend fun write(value: StoredChoice) = withContext(Dispatchers.Default) {
        fileSystem.createDirectories(directoryPath)
        val temporary = directoryPath.resolve("environment.json.tmp")
        fileSystem.write(temporary) { writeUtf8(json.encodeToString(StoredChoice.serializer(), value)) }
        fileSystem.atomicMove(temporary, filePath)
    }
}
