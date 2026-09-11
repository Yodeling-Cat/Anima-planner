package lux.uno.anima.ui.text

import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import lux.uno.anima.finance.RelativeDay
import lux.uno.anima.finance.relativeDay
import lux.uno.anima.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * Dates and day counts, in words.
 *
 * These used to live in `core` beside the arithmetic. They moved here when the app grew a
 * second and third language: a month name has a case, "in 2 weeks" has a plural form, and
 * both belong to the string file rather than to the domain. `core` still decides *what* to
 * say — which month, how many weeks — and this decides how it reads.
 */

/** `12 March 2026`, or `12. marca 2026` — the order and the case come from the resources. */
@Composable
fun LocalDate.formatLong(): String = stringResource(
    Res.string.date_long,
    day.toString(),
    stringArrayResource(Res.array.months_long)[month.number - 1],
    year.toString(),
)

/** `12 Mar` — drops the year unless it differs from [relativeTo]. */
@Composable
fun LocalDate.formatShort(relativeTo: LocalDate? = null): String {
    val month = stringArrayResource(Res.array.months_short)[month.number - 1]
    return if (relativeTo != null && relativeTo.year == year) {
        stringResource(Res.string.date_short, day.toString(), month)
    } else {
        stringResource(Res.string.date_short_with_year, day.toString(), month, year.toString())
    }
}

/** `Mar '26` — for chart axes where space is tight. */
@Composable
fun LocalDate.formatMonthYear(): String = stringResource(
    Res.string.date_month_year,
    stringArrayResource(Res.array.months_short)[month.number - 1],
    (year % 100).toString().padStart(2, '0'),
)

/** Turns a day count into the phrasing a person would use: `today`, `in 3 days`, `o 2 týždne`. */
@Composable
fun relativeDayLabel(days: Int): String = when (val relative = relativeDay(days)) {
    RelativeDay.Today -> stringResource(Res.string.relative_today)
    RelativeDay.Tomorrow -> stringResource(Res.string.relative_tomorrow)
    RelativeDay.Yesterday -> stringResource(Res.string.relative_yesterday)
    is RelativeDay.InDays ->
        pluralStringResource(Res.plurals.relative_in_days, relative.days, relative.days)
    is RelativeDay.InWeeks ->
        pluralStringResource(Res.plurals.relative_in_weeks, relative.weeks, relative.weeks)
    is RelativeDay.InMonths ->
        pluralStringResource(Res.plurals.relative_in_months, relative.months, relative.months)
    is RelativeDay.DaysAgo ->
        pluralStringResource(Res.plurals.relative_days_ago, relative.days, relative.days)
    is RelativeDay.WeeksAgo ->
        pluralStringResource(Res.plurals.relative_weeks_ago, relative.weeks, relative.weeks)
    is RelativeDay.MonthsAgo ->
        pluralStringResource(Res.plurals.relative_months_ago, relative.months, relative.months)
}
