package lux.uno.anima.finance.rates

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import lux.uno.anima.sync.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeRatesApi(var answer: () -> ApiResult<ExchangeRates>) : RatesApi {
    var calls = 0
    override suspend fun latest(): ApiResult<ExchangeRates> {
        calls++
        return answer()
    }
}

class RatesRepositoryTest {

    private fun table(fetchedAt: Long) = ExchangeRates(
        date = LocalDate(2026, 9, 11),
        fetchedAtMillis = fetchedAt,
        rates = mapOf("USD" to 1.08),
    )

    @Test
    fun publishesTheCachedTableBeforeAskingTheNetwork() = runTest {
        val cached = table(fetchedAt = 500)
        val api = FakeRatesApi { ApiResult.Unreachable(RuntimeException("offline")) }
        val repository = RatesRepository(api, InMemoryRatesStore(cached), nowMillis = { 600 })

        repository.start()

        // Offline, and still converting: what is on disk is what the UI reads.
        assertEquals(cached, repository.rates)
    }

    @Test
    fun aFreshCachedTableIsNotRefetched() = runTest {
        val api = FakeRatesApi { ApiResult.Ok(table(fetchedAt = 9_000)) }
        val repository = RatesRepository(
            api,
            InMemoryRatesStore(table(fetchedAt = 1_000)),
            nowMillis = { 1_000 + RATES_FRESH_FOR_MILLIS - 1 },
        )

        repository.start()

        assertEquals(0, api.calls)
    }

    @Test
    fun aStaleCachedTableIsReplaced() = runTest {
        val store = InMemoryRatesStore(table(fetchedAt = 1_000))
        val fetched = table(fetchedAt = 9_000).copy(rates = mapOf("USD" to 1.11))
        val api = FakeRatesApi { ApiResult.Ok(fetched) }
        val repository = RatesRepository(
            api,
            store,
            nowMillis = { 1_000 + RATES_FRESH_FOR_MILLIS },
        )

        repository.start()

        assertEquals(1, api.calls)
        assertEquals(fetched, repository.rates)
        // Written through, so the next launch starts from it rather than from the old one.
        assertEquals(fetched, store.load())
    }

    /** A failed refresh must never leave the app unable to convert. */
    @Test
    fun aFailedRefreshKeepsTheTableItAlreadyHad() = runTest {
        val cached = table(fetchedAt = 1_000)
        val api = FakeRatesApi { ApiResult.Unreachable(RuntimeException("offline")) }
        val repository = RatesRepository(
            api,
            InMemoryRatesStore(cached),
            nowMillis = { 1_000 + RATES_FRESH_FOR_MILLIS },
        )

        repository.start()

        assertEquals(cached, repository.rates)
        assertEquals(RatesProblem.UNREACHABLE, repository.status.value.problem)
    }

    /** The sandbox environment has no provider, and that is a state rather than a failure. */
    @Test
    fun withoutAProviderItSaysSoAndAsksNothing() = runTest {
        val repository = RatesRepository(api = null, store = InMemoryRatesStore(), nowMillis = { 0 })

        repository.start()
        repository.refreshNow()

        assertNull(repository.rates)
        assertEquals(RatesProblem.NOT_CONFIGURED, repository.status.value.problem)
        assertTrue(repository.status.value.refreshing.not())
    }
}
