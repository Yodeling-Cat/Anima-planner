package lux.uno.anima.ui.text

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import lux.uno.anima.finance.reminders.ReminderStrings
import lux.uno.anima.finance.reminders.ReminderText
import lux.uno.anima.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray

/**
 * Reminder wording, read from the same string file as the rest of the app.
 *
 * A notification is written long before it is shown — a card expiring in a month is handed
 * to the OS today — so the text is rendered here, once, at the moment it is scheduled. That
 * means a reminder speaks the language the device was in when it was armed, and changing
 * the language re-arms the schedule on the next sync.
 */
object ResourceReminderStrings : ReminderStrings {

    override suspend fun cardExpiringSoon(card: String, expiresOn: LocalDate) = ReminderText(
        title = getString(Res.string.reminder_card_expiring_title, card),
        body = getString(Res.string.reminder_card_expiring_body, expiresOn.longDate()),
    )

    override suspend fun cardExpiresToday(card: String) = ReminderText(
        title = getString(Res.string.reminder_card_today_title, card),
        body = getString(Res.string.reminder_card_today_body),
    )

    /** The composable [formatLong] in the same package, outside composition. */
    private suspend fun LocalDate.longDate(): String = getString(
        Res.string.date_long,
        day.toString(),
        getStringArray(Res.array.months_long)[month.number - 1],
        year.toString(),
    )
}
