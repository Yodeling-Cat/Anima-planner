package lux.uno.anima.finance

import lux.uno.anima.finance.model.Currency
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.format
import lux.uno.anima.finance.model.formatCompact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The minor unit is the whole point of [Money], so most of this is about the amount
 * surviving arithmetic exactly, and about `format` following the currency rather than
 * the reader.
 */
class MoneyTest {

    private val nbsp = ' '
    private val narrowNbsp = ' '

    @Test
    fun majorRoundsToTheNearestMinorUnit() {
        assertEquals(1299, Money.of(12.99).minor)
        assertEquals(1000, Money.of(9.999).minor)
        assertEquals(-1299, Money.of(-12.99).minor)
    }

    @Test
    fun additionStaysExactWhereFloatingPointWouldNot() {
        val tenth = Money.of(0.1)
        val sum = (1..10).fold(Money.zero()) { acc, _ -> acc + tenth }
        assertEquals(Money.of(1.0), sum)
        assertEquals(100, sum.minor)
    }

    @Test
    fun addingAcrossCurrenciesIsRefused() {
        assertFailsWith<IllegalArgumentException> { Money(100, "USD") + Money(100, "EUR") }
        assertFailsWith<IllegalArgumentException> { Money(100, "USD") - Money(100, "EUR") }
    }

    @Test
    fun subtractionCanGoNegative() {
        assertEquals(Money(-500, "EUR"), Money(500, "EUR") - Money(1000, "EUR"))
    }

    @Test
    fun scalingRoundsRatherThanTruncating() {
        assertEquals(Money(333, "USD"), Money(1000, "USD").scaledBy(1.0 / 3.0))
        assertEquals(Money(667, "USD"), Money(1000, "USD").scaledBy(2.0 / 3.0))
    }

    @Test
    fun scalingKeepsTheCurrency() {
        assertEquals("CZK", Money(1000, "CZK").scaledBy(2.0).currency)
    }

    @Test
    fun leadingSymbolCurrenciesPutItInFront() {
        assertEquals("$12,345.67", Money(1_234_567, "USD").format())
        assertEquals("£9.50", Money(950, "GBP").format())
    }

    @Test
    fun trailingSymbolCurrenciesPutItAfterANonBreakingSpace() {
        assertEquals("12,345.67${nbsp}€", Money(1_234_567, "EUR").format())
    }

    @Test
    fun spaceGroupedCurrenciesSwapBothSeparators() {
        assertEquals("12${narrowNbsp}345,67${nbsp}Kč", Money(1_234_567, "CZK").format())
        assertEquals("1${narrowNbsp}000,00${nbsp}zł", Money(100_000, "PLN").format())
    }

    @Test
    fun amountsUnderAThousandAreNotGrouped() {
        assertEquals("$999.99", Money(99_999, "USD").format())
    }

    @Test
    fun droppingDecimalsDropsTheSeparatorWithThem() {
        assertEquals("$12,345", Money(1_234_567, "USD").format(withDecimals = false))
        assertEquals("12${narrowNbsp}345${nbsp}Kč", Money(1_234_567, "CZK").format(withDecimals = false))
    }

    @Test
    fun negativeAmountsUseATypographicMinus() {
        val formatted = Money(-1_000, "USD").format()
        assertEquals("−$10.00", formatted)
        assertTrue('-' !in formatted, "expected U+2212 rather than a hyphen")
    }

    @Test
    fun zeroCarriesNoSign() {
        assertEquals("$0.00", Money.zero().format())
    }

    @Test
    fun anUnknownCurrencyFallsBackToItsCode() {
        assertEquals("1,000.00 XYZ", Money(100_000, "XYZ").format())
        assertNull(Currency.fromCode("XYZ"))
    }

    @Test
    fun compactFormShortensThousandsAndMillions() {
        assertEquals("$12.3k", Money(1_234_567, "USD").formatCompact())
        assertEquals("$1.2M", Money(123_456_789, "USD").formatCompact())
    }

    @Test
    fun compactFormKeepsWholeUnitsInTheHundreds() {
        assertEquals("$500", Money(50_000, "USD").formatCompact())
    }

    @Test
    fun compactFormKeepsCentsBelowAHundred() {
        assertEquals("$12.34", Money(1_234, "USD").formatCompact())
    }

    @Test
    fun compactFormTrimsATrailingZeroDecimal() {
        assertEquals("$2k", Money(200_000, "USD").formatCompact())
    }

    @Test
    fun compactFormSignsNegativesAndFollowsSymbolPlacement() {
        assertEquals("−$12.3k", Money(-1_234_567, "USD").formatCompact())
        assertEquals("−12.3k${nbsp}€", Money(-1_234_567, "EUR").formatCompact())
    }

    @Test
    fun everyOfferedCurrencyHasADistinctCodeThatResolves() {
        val codes = Currency.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "duplicate currency code")
        Currency.entries.forEach { assertEquals(it, Currency.fromCode(it.code)) }
    }
}
