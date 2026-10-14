package lux.uno.anima.finance

import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.model.BillingCycle
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.model.buildOverview
import lux.uno.anima.finance.rates.ExchangeRates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The headline total, and the count that has to agree with it. */
class OverviewTotalsTest {

    private val today = LocalDate(2026, 9, 10)

    private fun subscription(
        id: String,
        amount: Double,
        currency: String,
        cycle: BillingCycle = BillingCycle.Monthly,
    ) = Subscription(
        id = id,
        name = id,
        cycle = cycle,
        anchorDate = today,
        priceHistory = listOf(PricePoint(today, Money.of(amount, currency))),
    )

    /** One stray dollar plan must not outrank a year of euro spend just because USD is seeded. */
    @Test
    fun headlineFollowsTheLargestSpendNotTheSeededDefault() {
        val overview = buildOverview(
            subscriptions = listOf(
                subscription("netflix", 34.90, "USD", BillingCycle.Yearly),
                subscription("rent-a-server", 400.0, "EUR"),
                subscription("figma", 319.07, "EUR"),
            ),
            categories = emptyList(),
            today = today,
            preferredCurrency = "USD",
        )

        assertEquals("EUR", overview.primaryTotal?.currency)
        assertEquals(Money.of(719.07, "EUR"), overview.primaryTotal?.monthly)
    }

    /** The line under the amount counts what went into it, not every subscription there is. */
    @Test
    fun theCountBelongsToTheHeadlineCurrency() {
        val overview = buildOverview(
            subscriptions = listOf(
                subscription("a", 10.0, "EUR"),
                subscription("b", 5.0, "EUR"),
                subscription("c", 1.0, "CZK"),
            ),
            categories = emptyList(),
            today = today,
            preferredCurrency = "EUR",
        )

        assertEquals(2, overview.primaryTotal?.subscriptionCount)
        assertEquals(3, overview.activeCount)
    }

    /** A yearly plan divides onto the monthly axis, and the two figures stay in step. */
    @Test
    fun yearlyPlansNormaliseOntoTheMonthlyAxis() {
        val overview = buildOverview(
            subscriptions = listOf(subscription("jetbrains", 34.90, "USD", BillingCycle.Yearly)),
            categories = emptyList(),
            today = today,
            preferredCurrency = "USD",
        )

        assertEquals(Money.of(2.91), overview.primaryTotal?.monthly)
        assertEquals(Money.of(34.90), overview.primaryTotal?.yearly)
    }

    private val rates = ExchangeRates(
        date = LocalDate(2026, 9, 11),
        fetchedAtMillis = 0,
        rates = mapOf("USD" to 1.08, "CZK" to 24.5),
    )

    /** Given rates for everything, the headline is the whole month in one currency. */
    @Test
    fun ratesFoldEveryCurrencyIntoThePreferredOne() {
        val overview = buildOverview(
            subscriptions = listOf(
                subscription("a", 10.80, "USD"),
                subscription("b", 24.50, "CZK"),
                subscription("c", 5.0, "EUR"),
            ),
            categories = emptyList(),
            today = today,
            preferredCurrency = "EUR",
            rates = rates,
        )

        assertEquals("EUR", overview.primaryTotal?.currency)
        assertEquals(Money.of(16.0, "EUR"), overview.primaryTotal?.monthly)
        assertEquals(3, overview.primaryTotal?.subscriptionCount)
        assertTrue(overview.isConverted)
        assertEquals(emptyList(), overview.unconvertedCurrencies)
    }

    /** A currency the table does not carry must not be silently dropped from the total. */
    @Test
    fun anUncoveredCurrencyFallsBackRatherThanUndercounting() {
        val overview = buildOverview(
            subscriptions = listOf(
                subscription("a", 400.0, "EUR"),
                subscription("b", 50.0, "JPY"),
            ),
            categories = emptyList(),
            today = today,
            preferredCurrency = "EUR",
            rates = rates,
        )

        assertEquals(Money.of(400.0, "EUR"), overview.primaryTotal?.monthly)
        assertFalse(overview.isConverted)
        assertEquals(listOf("JPY"), overview.unconvertedCurrencies)
    }

    /** One currency that is already the preferred one is a sum, not an estimate. */
    @Test
    fun asingleCurrencyIsNeverMarkedConverted() {
        val overview = buildOverview(
            subscriptions = listOf(subscription("a", 10.0, "EUR")),
            categories = emptyList(),
            today = today,
            preferredCurrency = "EUR",
            rates = rates,
        )

        assertFalse(overview.isConverted)
    }

    /** The breakdown has to add up to the headline it sits under. */
    @Test
    fun categorySlicesConvertAlongsideTheHeadline() {
        val overview = buildOverview(
            subscriptions = listOf(
                subscription("a", 10.80, "USD"),
                subscription("b", 24.50, "CZK"),
            ),
            categories = emptyList(),
            today = today,
            preferredCurrency = "EUR",
            rates = rates,
        )

        assertEquals(Money.of(11.0, "EUR"), overview.categories.single().monthly)
        assertEquals(1.0, overview.categories.single().share)
    }
}
