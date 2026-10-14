package lux.uno.anima.finance.rates

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.model.Currency
import lux.uno.anima.finance.model.Money
import lux.uno.anima.sync.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real response, trimmed only of currencies the app never offers. */
private const val REAL_RESPONSE = """
{"amount":1.0,"base":"EUR","date":"2026-09-11","rates":{
"AUD":1.6161,"CAD":1.6064,"CHF":0.9451,"CZK":24.264,"DKK":7.4748,"GBP":0.85815,
"INR":110.7675,"JPY":178.56,"NOK":10.7805,"PLN":4.325,"SEK":11.2373,"USD":1.1592}}
"""

class FrankfurterRatesApiTest {

    private fun api(handler: MockEngine) = FrankfurterRatesApi(
        http = HttpClient(handler),
        nowMillis = { 42 },
    )

    @Test
    fun readsThePublishedTable() = runTest {
        val engine = MockEngine {
            respond(
                content = REAL_RESPONSE,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val result = api(engine).latest()

        val rates = (result as ApiResult.Ok).value
        assertEquals(LocalDate(2026, 9, 11), rates.date)
        assertEquals(42, rates.fetchedAtMillis)
        assertEquals(24.264, rates.rates["CZK"])
    }

    /** The base is implicit, so it must not also be sitting in the map. */
    @Test
    fun theBaseIsNotStoredAsAnEntry() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"base":"EUR","date":"2026-09-11","rates":{"EUR":1.0,"USD":1.1592}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val rates = (api(engine).latest() as ApiResult.Ok).value

        assertEquals(setOf("USD"), rates.rates.keys)
        assertEquals(1.0, rates.rateFor("EUR"))
    }

    /** Every currency the picker offers has to be convertible, or the setting is a trap. */
    @Test
    fun coversEveryCurrencyTheAppOffers() = runTest {
        val engine = MockEngine {
            respond(
                content = REAL_RESPONSE,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val rates = (api(engine).latest() as ApiResult.Ok).value

        val missing = Currency.entries.map { it.code }.filterNot { rates.covers(it) }
        assertEquals(emptyList(), missing, "no rate for: $missing")
    }

    @Test
    fun convertsAgainstTheRealTable() = runTest {
        val engine = MockEngine {
            respond(
                content = REAL_RESPONSE,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val rates = (api(engine).latest() as ApiResult.Ok).value

        // One dollar is 1/1.1592 of a euro, and a euro is 24.264 koruna.
        assertEquals(Money.of(20.93, "CZK"), rates.convert(Money.of(1.0, "USD"), "CZK"))
    }

    @Test
    fun aServerErrorIsRefusedRatherThanParsed() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }

        assertTrue(api(engine).latest() is ApiResult.Refused)
    }

    /** A body that is not the expected shape must not become an empty table of rates. */
    @Test
    fun anUnreadableBodyIsRefused() = runTest {
        val engine = MockEngine {
            respond(
                content = "<html>rate limited</html>",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        assertTrue(api(engine).latest() is ApiResult.Refused)
    }
}
