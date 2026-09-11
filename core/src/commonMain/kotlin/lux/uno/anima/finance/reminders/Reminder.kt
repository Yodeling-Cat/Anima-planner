package lux.uno.anima.finance.reminders

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.model.PaymentMethod

/** How far ahead of a card's expiry the first warning lands. */
const val CARD_EXPIRY_WARNING_MONTHS: Int = 1

/** The hour of the local day reminders are delivered at. */
const val REMINDER_HOUR: Int = 9

enum class ReminderKind { CARD_EXPIRING_SOON, CARD_EXPIRES_TODAY }

/** The two lines a notification shows. */
data class ReminderText(val title: String, val body: String)

/**
 * Reminder wording, in the reader's language.
 *
 * A notification is the one piece of user-facing text core produces, and core has no
 * language of its own — so the strings live in the UI module and arrive through here.
 * Suspending because reading a string resource is an I/O call on some platforms.
 */
interface ReminderStrings {

    /** The card is a month from expiring; [expiresOn] is the last day it works. */
    suspend fun cardExpiringSoon(card: String, expiresOn: LocalDate): ReminderText

    /** Today is the last day the card works. */
    suspend fun cardExpiresToday(card: String): ReminderText
}

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
suspend fun buildReminders(data: FinanceData, strings: ReminderStrings): List<Reminder> =
    data.paymentMethods
        .filter { it.tracksExpiry && it.expiry != null }
        .flatMap { it.expiryReminders(strings) }
        .sortedBy { it.date }

private suspend fun PaymentMethod.expiryReminders(strings: ReminderStrings): List<Reminder> {
    val expiry = expiry ?: return emptyList()
    val expiresOn = expiry.lastValidDay
    val warnOn = expiresOn.minus(CARD_EXPIRY_WARNING_MONTHS, DateTimeUnit.MONTH)
    val stamp = "${expiry.year}-${expiry.month}"

    val soon = strings.cardExpiringSoon(displayName, expiresOn)
    val today = strings.cardExpiresToday(displayName)

    return listOf(
        Reminder(
            id = "card-expiring-$id-$stamp",
            kind = ReminderKind.CARD_EXPIRING_SOON,
            title = soon.title,
            body = soon.body,
            date = warnOn,
        ),
        Reminder(
            id = "card-expired-$id-$stamp",
            kind = ReminderKind.CARD_EXPIRES_TODAY,
            title = today.title,
            body = today.body,
            date = expiresOn,
        ),
    )
}
