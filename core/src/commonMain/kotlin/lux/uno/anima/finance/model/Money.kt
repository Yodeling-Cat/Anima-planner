package lux.uno.anima.finance.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * An exact amount of money, held in the currency's minor unit (cents, pence, haléře…)
 * so arithmetic never drifts the way floating point does.
 */
@Serializable
data class Money(val minor: Long, val currency: String = "USD") {

    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Cannot add $currency to ${other.currency}" }
        return copy(minor = minor + other.minor)
    }

    operator fun minus(other: Money): Money {
        require(currency == other.currency) { "Cannot subtract ${other.currency} from $currency" }
        return copy(minor = minor - other.minor)
    }

    fun scaledBy(factor: Double): Money = copy(minor = (minor * factor).roundToLong())

    val major: Double get() = minor / 100.0

    companion object {
        fun of(major: Double, currency: String = "USD") = Money((major * 100).roundToLong(), currency)
        fun zero(currency: String = "USD") = Money(0, currency)
    }
}

/** Currencies offered in the picker. Symbol placement follows local convention. */
enum class Currency(val code: String, val symbol: String, val symbolLeads: Boolean) {
    USD("USD", "$", true),
    EUR("EUR", "€", false),
    GBP("GBP", "£", true),
    CZK("CZK", "Kč", false),
    PLN("PLN", "zł", false),
    CHF("CHF", "CHF", false),
    SEK("SEK", "kr", false),
    NOK("NOK", "kr", false),
    DKK("DKK", "kr", false),
    CAD("CAD", "CA$", true),
    AUD("AUD", "A$", true),
    JPY("JPY", "¥", true),
    INR("INR", "₹", true);

    companion object {
        fun fromCode(code: String): Currency? = entries.firstOrNull { it.code == code }
    }
}

/** Formats `1234567` USD as `$12,345.67`, and the same amount in CZK as `12 345,67 Kč`. */
fun Money.format(withDecimals: Boolean = true): String {
    val currency = Currency.fromCode(this.currency)
    val negative = minor < 0
    val cents = abs(minor)
    val whole = cents / 100
    val fraction = (cents % 100).toString().padStart(2, '0')

    val useSpaceGrouping = currency in setOf(Currency.CZK, Currency.PLN, Currency.SEK, Currency.NOK)
    val groupSeparator = if (useSpaceGrouping) '\u202F' else ','
    val decimalSeparator = if (useSpaceGrouping) ',' else '.'

    val grouped = buildString {
        val digits = whole.toString()
        digits.forEachIndexed { index, char ->
            if (index > 0 && (digits.length - index) % 3 == 0) append(groupSeparator)
            append(char)
        }
    }

    val number = if (withDecimals && fraction != "00") "$grouped$decimalSeparator$fraction"
    else if (withDecimals) "$grouped$decimalSeparator$fraction"
    else grouped

    val symbol = currency?.symbol ?: this.currency
    val body = when {
        currency == null -> "$number $symbol"
        currency.symbolLeads -> "$symbol$number"
        else -> "$number\u00A0$symbol"
    }
    return if (negative) "−$body" else body
}

/** Compact form for dense rows and chart axes: `$12.3k`. */
fun Money.formatCompact(): String {
    val currency = Currency.fromCode(this.currency)
    val symbol = currency?.symbol ?: this.currency
    val value = abs(major)
    val short = when {
        value >= 1_000_000 -> "${(value / 1_000_000).roundTo(1)}M"
        value >= 1_000 -> "${(value / 1_000).roundTo(1)}k"
        value >= 100 -> value.roundToLong().toString()
        else -> value.roundTo(2)
    }
    val signed = if (minor < 0) "−$short" else short
    return if (currency == null || !currency.symbolLeads) "$signed\u00A0$symbol" else "$symbol$signed"
}

private fun Double.roundTo(decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    val rounded = (this * factor).roundToLong() / factor
    val text = rounded.toString()
    return if (text.endsWith(".0")) text.dropLast(2) else text
}
