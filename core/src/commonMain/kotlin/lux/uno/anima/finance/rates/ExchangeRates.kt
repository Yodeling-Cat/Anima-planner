package lux.uno.anima.finance.rates

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import lux.uno.anima.finance.model.Money

/** The base every stored rate is quoted against. The ECB publishes against the euro. */
const val RATES_BASE: String = "EUR"

/**
 * How long a table is treated as current. The ECB publishes once per working day at about
 * 16:00 CET, so anything inside a day is the same table; a weekend or a holiday leaves the
 * newest reading two or three days old and that is still the real rate, not a failure.
 */
const val RATES_FRESH_FOR_MILLIS: Long = 6L * 60 * 60 * 1000

/**
 * One published table of reference rates, quoted as "units of this currency per one
 * [RATES_BASE]".
 *
 * Stored against a fixed base rather than against the user's preferred currency so that
 * changing the preference is a local recalculation rather than a network call — and so a
 * device that is offline when the preference changes still converts.
 *
 * [date] is the day the *provider* published, and [fetchedAtMillis] is when this device
 * asked. They answer different questions: the first is what the user is shown, the second
 * is what decides whether to ask again. A table fetched twice on a Sunday is fresh both
 * times and has Friday's date both times.
 */
@Serializable
data class ExchangeRates(
    val date: LocalDate,
    val fetchedAtMillis: Long,
    /** Keyed by currency code. [RATES_BASE] is absent — it is 1.0 by definition. */
    val rates: Map<String, Double> = emptyMap(),
) {
    /** Units of [currency] per one [RATES_BASE], or null where the table does not carry it. */
    fun rateFor(currency: String): Double? =
        if (currency == RATES_BASE) 1.0 else rates[currency]?.takeIf { it > 0.0 }

    fun covers(currency: String): Boolean = rateFor(currency) != null

    fun isFreshAt(nowMillis: Long): Boolean =
        nowMillis - fetchedAtMillis in 0 until RATES_FRESH_FOR_MILLIS

    /**
     * [money] expressed in [target], or null when either side is missing from the table.
     *
     * Null rather than the original amount, because a caller that cannot convert has to
     * say so — silently handing back an unconverted figure under a converted heading is
     * exactly the bug this whole feature exists to fix.
     */
    fun convert(money: Money, target: String): Money? {
        if (money.currency == target) return money
        val from = rateFor(money.currency) ?: return null
        val to = rateFor(target) ?: return null
        return Money.of(money.major / from * to, target)
    }
}

/**
 * Converts every amount it can and reports what it could not.
 *
 * Totalling is the one place a partial answer is worse than none: a month's outflow with
 * two subscriptions quietly dropped is a number the user will act on. Callers get both the
 * sum and the currencies missing from it, and decide what to say.
 */
data class ConversionResult(val total: Money, val unconvertible: List<Money>) {
    val isComplete: Boolean get() = unconvertible.isEmpty()
}

fun ExchangeRates?.sumInto(amounts: List<Money>, target: String): ConversionResult {
    var total = Money.zero(target)
    val missed = mutableListOf<Money>()
    amounts.forEach { amount ->
        val converted = if (amount.currency == target) amount else this?.convert(amount, target)
        if (converted == null) missed += amount else total += converted
    }
    return ConversionResult(total, missed)
}
