package lux.uno.anima.finance

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
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

private val monthNames = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private val shortMonthNames = monthNames.map { it.take(3) }

/** `12 March 2026` */
fun LocalDate.formatLong(): String = "$day ${monthNames[month.number - 1]} $year"

/** `12 Mar` — drops the year unless it differs from [relativeTo]. */
fun LocalDate.formatShort(relativeTo: LocalDate? = null): String {
    val base = "$day ${shortMonthNames[month.number - 1]}"
    return if (relativeTo != null && relativeTo.year == year) base else "$base $year"
}

/** `Mar '26` — for chart axes where space is tight. */
fun LocalDate.formatMonthYear(): String =
    "${shortMonthNames[month.number - 1]} '${(year % 100).toString().padStart(2, '0')}"

/** Turns a day count into the phrasing a person would use: `today`, `in 3 days`, `in 2 weeks`. */
fun relativeDayLabel(days: Int): String = when {
    days < 0 -> {
        val past = -days
        when {
            past == 1 -> "yesterday"
            past < 7 -> "$past days ago"
            past < 30 -> "${past / 7} ${if (past / 7 == 1) "week" else "weeks"} ago"
            else -> "${past / 30} ${if (past / 30 == 1) "month" else "months"} ago"
        }
    }
    days == 0 -> "today"
    days == 1 -> "tomorrow"
    days < 7 -> "in $days days"
    days < 14 -> "in a week"
    days < 60 -> "in ${days / 7} weeks"
    else -> "in ${days / 30} months"
}
