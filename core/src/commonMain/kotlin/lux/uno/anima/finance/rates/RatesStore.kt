package lux.uno.anima.finance.rates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import lux.uno.anima.platform.appDataDirectory
import lux.uno.anima.platform.platformFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The last rate table this device fetched.
 *
 * Device state rather than user data, so it sits here instead of in `FinanceData`: it is a
 * cache of a public table that every device can refill for itself, and syncing it would put
 * a record that churns hourly into a dataset whose whole point is that it only changes when
 * a person changes something.
 *
 * It is persisted at all so that a launch with no network still converts. Rates from
 * yesterday are the right answer offline; no rates at all is not.
 */
interface RatesStore {
    suspend fun load(): ExchangeRates?
    suspend fun save(rates: ExchangeRates)
}

/** Store used by tests, and by any platform with nowhere to write. */
class InMemoryRatesStore(private var rates: ExchangeRates? = null) : RatesStore {
    override suspend fun load(): ExchangeRates? = rates
    override suspend fun save(rates: ExchangeRates) {
        this.rates = rates
    }
}

/**
 * Keeps the table in its own small JSON file beside the dataset.
 *
 * An unreadable file reads as "no rates yet", which costs one fetch. There is nothing here
 * worth failing a launch over — every value in it can be asked for again.
 */
class JsonFileRatesStore(
    private val fileSystem: FileSystem = platformFileSystem(),
    directory: String = appDataDirectory(),
    fileName: String = "rates.json",
) : RatesStore {

    private val directoryPath: Path = directory.toPath()
    private val filePath: Path = directoryPath.resolve(fileName)
    private val temporaryPath: Path = directoryPath.resolve("$fileName.tmp")

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): ExchangeRates? = withContext(Dispatchers.Default) {
        if (!fileSystem.exists(filePath)) return@withContext null
        runCatching {
            json.decodeFromString(ExchangeRates.serializer(), fileSystem.read(filePath) { readUtf8() })
        }.getOrNull()
    }

    override suspend fun save(rates: ExchangeRates) = withContext(Dispatchers.Default) {
        fileSystem.createDirectories(directoryPath)
        val text = json.encodeToString(ExchangeRates.serializer(), rates)
        fileSystem.write(temporaryPath) { writeUtf8(text) }
        fileSystem.atomicMove(temporaryPath, filePath)
    }
}
