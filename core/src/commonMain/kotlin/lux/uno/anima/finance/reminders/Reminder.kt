package lux.uno.anima.finance.reminders

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.formatLong
import lux.uno.anima.finance.model.PaymentMethod

/** How far ahead of a card's expiry the first warning lands. */
const val CARD_EXPIRY_WARNING_MONTHS: Int = 1

/** The hour of the local day reminders are delivered at. */
const val REMINDER_HOUR: Int = 9

enum class ReminderKind { CARD_EXPIRING_SOON, CARD_EXPIRES_TODAY }

/**
 * One dated notification. Ids are derived from the data rather than generated, so editing a
 * card's expiry produces new ids and re-arms the reminders, while leaving it alone keeps the
 * same ids and never fires twice.
 */
data class Reminder(
    val id: String,
    val kind: ReminderKind,
    val title: String,
    val body: String,
    val date: LocalDate,
    val hour: Int = REMINDER_HOUR,
)

/** Every reminder the current data implies, whether or not its date has passed. */
fun buildReminders(data: FinanceData): List<Reminder> =
    data.paymentMethods
        .filter { it.tracksExpiry && it.expiry != null }
        .flatMap { it.expiryReminders() }
        .sortedBy { it.date }

private fun PaymentMethod.expiryReminders(): List<Reminder> {
    val expiry = expiry ?: return emptyList()
    val expiresOn = expiry.lastValidDay
    val warnOn = expiresOn.minus(CARD_EXPIRY_WARNING_MONTHS, DateTimeUnit.MONTH)
    val stamp = "${expiry.year}-${expiry.month}"

    return listOf(
        Reminder(
            id = "card-expiring-$id-$stamp",
            kind = ReminderKind.CARD_EXPIRING_SOON,
            title = "$displayName expires in a month",
            body = "It stops working after ${expiresOn.formatLong()}. "
                + "Order the replacement now so nothing you pay for fails.",
            date = warnOn,
        ),
        Reminder(
            id = "card-expired-$id-$stamp",
            kind = ReminderKind.CARD_EXPIRES_TODAY,
            title = "$displayName expires today",
            body = "Today is the last day it works. Update every subscription charged to it.",
            date = expiresOn,
        ),
    )
}
