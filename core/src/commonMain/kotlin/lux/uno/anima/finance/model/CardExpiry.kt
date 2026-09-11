package lux.uno.anima.finance.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

/**
 * The MM/YY printed on a card. Cards stay usable through the whole of their final month,
 * so this is a month rather than a day — [lastValidDay] turns it into one.
 */
@Serializable
data class CardExpiry(val year: Int, val month: Int) {
    init {
        require(month in 1..12) { "Card expiry month must be 1..12, was $month" }
    }

    /** `09/26` */
    val label: String get() = "${month.toString().padStart(2, '0')}/${(year % 100).toString().padStart(2, '0')}"

    /** The last day the card still works. */
    val lastValidDay: LocalDate
        get() = LocalDate(year, month, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)

    companion object {
        /** Parses the four digits of an `MMYY` entry field. Returns null while incomplete. */
        fun fromDigits(digits: String): CardExpiry? {
            if (digits.length != 4 || !digits.all { it.isDigit() }) return null
            val month = digits.substring(0, 2).toInt()
            if (month !in 1..12) return null
            return CardExpiry(year = 2000 + digits.substring(2, 4).toInt(), month = month)
        }
    }
}

/** `0926` — the inverse of [CardExpiry.fromDigits], for prefilling the entry field. */
fun CardExpiry.toDigits(): String =
    month.toString().padStart(2, '0') + (year % 100).toString().padStart(2, '0')

fun CardExpiry.hasExpired(today: LocalDate): Boolean = today > lastValidDay

/** Negative once the card is dead. Zero on the last day it works. */
fun CardExpiry.daysUntilExpiry(today: LocalDate): Int = today.daysUntil(lastValidDay)

/**
 * True once the app has started warning about this card — from a month before it expires,
 * and for ever after it has. Matches the moment the first reminder fires.
 */
fun PaymentMethod.expiryNeedsAttention(today: LocalDate, warningMonths: Int = 1): Boolean {
    val expiry = expiry?.takeIf { tracksExpiry } ?: return false
    return today >= expiry.lastValidDay.minus(warningMonths, DateTimeUnit.MONTH)
}

/** The cards worth putting in front of someone on the finance screen. */
fun List<PaymentMethod>.cardsNeedingAttention(today: LocalDate): List<PaymentMethod> =
    filter { it.expiryNeedsAttention(today) }.sortedBy { it.expiry?.lastValidDay }
