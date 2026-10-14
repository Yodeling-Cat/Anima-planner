package lux.uno.anima.finance.rates

import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExchangeRatesTest {

    private val table = ExchangeRates(
        date = LocalDate(2026, 9, 11),
        fetchedAtMillis = 1_000_000,
        rates = mapOf("USD" to 1.08, "CZK" to 24.5, "GBP" to 0.84),
    )

    @Test
    fun theBaseConvertsWithoutBeingListed() {
        assertEquals(1.0, table.rateFor("EUR"))
        assertTrue(table.covers("EUR"))
    }

    @Test
    fun convertsThroughTheBase() {
        // 24.50 Kč is one euro, which is 1.08 dollars.
        assertEquals(Money.of(1.08, "USD"), table.convert(Money.of(24.50, "CZK"), "USD"))
    }

    @Test
    fun convertingIntoTheSameCurrencyChangesNothing() {
        val amount = Money.of(9.99, "USD")
        assertEquals(amount, table.convert(amount, "USD"))
    }

    @Test
    fun refusesToConvertACurrencyItDoesNotCarry() {
        assertNull(table.convert(Money.of(5.0, "JPY"), "EUR"))
        assertNull(table.convert(Money.of(5.0, "EUR"), "JPY"))
    }

    /** A zero or negative rate is a broken table, not a free currency. */
    @Test
    fun treatsAnImpossibleRateAsMissing() {
        val broken = table.copy(rates = table.rates + ("PLN" to 0.0))
        assertNull(broken.convert(Money.of(5.0, "PLN"), "EUR"))
    }

    @Test
    fun freshnessIsMeasuredFromTheFetchNotThePublishedDate() {
        assertTrue(table.isFreshAt(1_000_000 + RATES_FRESH_FOR_MILLIS - 1))
        assertFalse(table.isFreshAt(1_000_000 + RATES_FRESH_FOR_MILLIS))
        // A clock that jumped backwards must not read as fresh forever.
        assertFalse(table.isFreshAt(999_999))
    }

    @Test
    fun summingReportsWhatItCouldNotConvert() {
        val result = table.sumInto(
            listOf(Money.of(10.0, "EUR"), Money.of(24.50, "CZK"), Money.of(3.0, "JPY")),
            target = "EUR",
        )
        assertEquals(Money.of(11.0, "EUR"), result.total)
        assertEquals(listOf(Money.of(3.0, "JPY")), result.unconvertible)
        assertFalse(result.isComplete)
    }

    /** With no table at all, only amounts already in the target survive the sum. */
    @Test
    fun summingWithoutATableKeepsOnlyMatchingAmounts() {
        val none: ExchangeRates? = null
        val result = none.sumInto(listOf(Money.of(10.0, "EUR"), Money.of(5.0, "USD")), "EUR")
        assertEquals(Money.of(10.0, "EUR"), result.total)
        assertFalse(result.isComplete)
    }
}
