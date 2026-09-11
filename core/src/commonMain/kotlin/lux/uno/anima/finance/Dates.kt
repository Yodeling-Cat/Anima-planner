package lux.uno.anima.finance

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Today in the device's own timezone — the only clock reading in the app. */
@OptIn(ExperimentalTime::class)
fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/** Local hour of day, 0..23, used only to choose a greeting. */
@OptIn(ExperimentalTime::class)
fun currentHour(): Int =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour

/**
 * A day count reduced to the phrase a person would reach for, minus the words.
 *
 * Rounding to weeks and months is a judgement about how people talk about time, which makes
 * it domain logic and keeps it here. Turning it into "in 2 weeks" or "o 2 týždne" is not:
 * the wording and its plural forms belong to the language, so the UI renders it.
 */
sealed interface RelativeDay {
    data object Today : RelativeDay
    data object Tomorrow : RelativeDay
    data object Yesterday : RelativeDay
    data class InDays(val days: Int) : RelativeDay
    data class InWeeks(val weeks: Int) : RelativeDay
    data class InMonths(val months: Int) : RelativeDay
    data class DaysAgo(val days: Int) : RelativeDay
    data class WeeksAgo(val weeks: Int) : RelativeDay
    data class MonthsAgo(val months: Int) : RelativeDay
}

/** Negative [days] is the past, as everywhere else in the app. */
fun relativeDay(days: Int): RelativeDay = when {
    days < 0 -> {
        val past = -days
        when {
            past == 1 -> RelativeDay.Yesterday
            past < 7 -> RelativeDay.DaysAgo(past)
            past < 30 -> RelativeDay.WeeksAgo(past / 7)
            else -> RelativeDay.MonthsAgo(past / 30)
        }
    }
    days == 0 -> RelativeDay.Today
    days == 1 -> RelativeDay.Tomorrow
    days < 7 -> RelativeDay.InDays(days)
    days < 14 -> RelativeDay.InWeeks(1)
    days < 60 -> RelativeDay.InWeeks(days / 7)
    else -> RelativeDay.InMonths(days / 30)
}
