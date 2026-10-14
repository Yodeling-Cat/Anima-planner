package lux.uno.anima.finance.rates

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lux.uno.anima.sync.ApiResult
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Where a rate table comes from.
 *
 * An interface for the same reason [lux.uno.anima.sync.SyncApi] is one: the repository has
 * to be drivable without a network in tests. It also leaves room for a self-hoster to point
 * at their own endpoint without the rest of the app knowing.
 */
interface RatesApi {
    suspend fun latest(): ApiResult<ExchangeRates>
}

/**
 * European Central Bank reference rates, by way of Frankfurter.
 *
 * Chosen because it needs no API key and no signup: a self-hoster who never reads the docs
 * still gets working conversion, which nothing behind a key can promise. The table is quoted
 * against the euro, which is [RATES_BASE], so no rebasing happens on the way in.
 *
 * Rates are a public table that says nothing about the person asking, so fetching them
 * directly from the device — rather than through Anima's own server — keeps to the rule
 * that the server has no domain endpoints and never learns anything about a payload.
 */
@OptIn(ExperimentalTime::class)
class FrankfurterRatesApi(
    private val http: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : RatesApi {

    private val format = Json { ignoreUnknownKeys = true }

    override suspend fun latest(): ApiResult<ExchangeRates> {
        val response = try {
            http.get("$baseUrl/v1/latest")
        } catch (failure: Throwable) {
            return ApiResult.Unreachable(failure)
        }
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        if (response.status.value !in 200..299) {
            return ApiResult.Refused(response.status.value, body)
        }
        val parsed = runCatching { format.decodeFromString(Payload.serializer(), body) }
            .getOrElse { return ApiResult.Refused(response.status.value, "unreadable rates") }
        val date = runCatching { LocalDate.parse(parsed.date) }
            .getOrElse { return ApiResult.Refused(response.status.value, "unreadable rates date") }

        return ApiResult.Ok(
            ExchangeRates(
                date = date,
                fetchedAtMillis = nowMillis(),
                // The base is dropped rather than stored as 1.0: one currency carrying its
                // rate implicitly is one fewer place for a stale entry to disagree with it.
                rates = parsed.rates.filterKeys { it != parsed.base },
            ),
        )
    }

    @Serializable
    private data class Payload(
        val base: String = RATES_BASE,
        val date: String,
        val rates: Map<String, Double> = emptyMap(),
    )

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.frankfurter.dev"
    }
}
