package lux.uno.anima.finance

import lux.uno.anima.finance.model.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EnabledCurrenciesTest {

    @Test
    fun `every currency is offered until one is switched off`() {
        assertEquals(Currency.entries.toList(), FinanceSettings().availableCurrencies)
    }

    @Test
    fun `switching one off leaves the rest offered in enum order`() {
        val settings = FinanceSettings().withCurrencyEnabled(Currency.JPY.code, enabled = false)

        assertEquals(Currency.entries.filterNot { it == Currency.JPY }, settings.availableCurrencies)
    }

    @Test
    fun `switching one back on restores it`() {
        val settings = FinanceSettings()
            .withCurrencyEnabled(Currency.JPY.code, enabled = false)
            .withCurrencyEnabled(Currency.JPY.code, enabled = true)

        assertTrue(Currency.JPY in settings.availableCurrencies)
    }

    @Test
    fun `switching off the preferred currency moves the preference to another offered one`() {
        val settings = FinanceSettings(preferredCurrency = Currency.USD.code)
            .withCurrencyEnabled(Currency.USD.code, enabled = false)

        assertEquals(Currency.EUR.code, settings.preferredCurrency)
        assertTrue(Currency.USD !in settings.availableCurrencies)
    }

    @Test
    fun `switching off something else leaves the preference alone`() {
        val settings = FinanceSettings(preferredCurrency = Currency.EUR.code)
            .withCurrencyEnabled(Currency.USD.code, enabled = false)

        assertEquals(Currency.EUR.code, settings.preferredCurrency)
    }

    @Test
    fun `the last currency cannot be switched off`() {
        val onlyEur = FinanceSettings(enabledCurrencies = setOf(Currency.EUR.code))

        val after = onlyEur.withCurrencyEnabled(Currency.EUR.code, enabled = false)

        assertSame(onlyEur, after)
        assertEquals(listOf(Currency.EUR), after.availableCurrencies)
    }

    @Test
    fun `an empty set from another version falls back to every currency`() {
        val settings = FinanceSettings(enabledCurrencies = emptySet())

        assertEquals(Currency.entries.toList(), settings.availableCurrencies)
    }
}
