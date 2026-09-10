package lux.uno.anima.finance.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlin.math.max

/** Average length of a Gregorian month, used to normalise every cycle onto one axis. */
const val DAYS_PER_MONTH: Double = 30.436875

enum class BillingPeriod { DAY, WEEK, MONTH, YEAR }

/**
 * "Every [interval] [period]" — covers weekly, fortnightly, monthly, quarterly and annual
 * plans without a separate enum entry for each.
 */
@Serializable
data class BillingCycle(
    val period: BillingPeriod = BillingPeriod.MONTH,
    val interval: Int = 1,
) {
    init {
        require(interval >= 1) { "Billing interval must be at least 1" }
    }

    /** How many of these cycles land in an average month. */
    val cyclesPerMonth: Double
        get() = when (period) {
            BillingPeriod.DAY -> DAYS_PER_MONTH / interval
            BillingPeriod.WEEK -> DAYS_PER_MONTH / (7.0 * interval)
            BillingPeriod.MONTH -> 1.0 / interval
            BillingPeriod.YEAR -> 1.0 / (12.0 * interval)
        }

    val cyclesPerYear: Double get() = cyclesPerMonth * 12.0

    /** "Monthly", "Every 3 months", "Yearly". */
    val label: String
        get() = if (interval == 1) {
            when (period) {
                BillingPeriod.DAY -> "Daily"
                BillingPeriod.WEEK -> "Weekly"
                BillingPeriod.MONTH -> "Monthly"
                BillingPeriod.YEAR -> "Yearly"
            }
        } else {
            val unit = when (period) {
                BillingPeriod.DAY -> "days"
                BillingPeriod.WEEK -> "weeks"
                BillingPeriod.MONTH -> "months"
                BillingPeriod.YEAR -> "years"
            }
            "Every $interval $unit"
        }

    /** "/mo", "/yr" — the suffix next to a price. */
    val shortSuffix: String
        get() = if (interval == 1) {
            when (period) {
                BillingPeriod.DAY -> "/day"
                BillingPeriod.WEEK -> "/wk"
                BillingPeriod.MONTH -> "/mo"
                BillingPeriod.YEAR -> "/yr"
            }
        } else {
            val unit = when (period) {
                BillingPeriod.DAY -> "d"
                BillingPeriod.WEEK -> "w"
                BillingPeriod.MONTH -> "mo"
                BillingPeriod.YEAR -> "yr"
            }
            "/$interval$unit"
        }

    companion object {
        val Monthly = BillingCycle(BillingPeriod.MONTH, 1)
        val Yearly = BillingCycle(BillingPeriod.YEAR, 1)
        val Weekly = BillingCycle(BillingPeriod.WEEK, 1)
        val Quarterly = BillingCycle(BillingPeriod.MONTH, 3)

        /** The choices offered in the editor, in the order people usually reach for them. */
        val presets = listOf(
            Monthly,
            Yearly,
            Weekly,
            Quarterly,
            BillingCycle(BillingPeriod.WEEK, 2),
            BillingCycle(BillingPeriod.MONTH, 6),
            BillingCycle(BillingPeriod.DAY, 1),
        )
    }
}

/** Advances [anchor] by whole cycles until it lands on or after [from]. */
fun BillingCycle.nextOccurrence(anchor: LocalDate, from: LocalDate): LocalDate {
    if (anchor >= from) return anchor

    // Estimate how many cycles have already elapsed, then nudge by at most a step or two.
    val elapsed = when (period) {
        BillingPeriod.DAY -> anchor.daysUntil(from) / interval
        BillingPeriod.WEEK -> anchor.daysUntil(from) / (7 * interval)
        BillingPeriod.MONTH -> monthsBetween(anchor, from) / interval
        BillingPeriod.YEAR -> (from.year - anchor.year) / interval
    }

    var candidate = advance(anchor, max(0, elapsed))
    var steps = max(0, elapsed)
    while (candidate < from) {
        steps++
        candidate = advance(anchor, steps)
    }
    return candidate
}

/** The occurrence immediately before [before], or null if the cycle has not run yet. */
fun BillingCycle.previousOccurrence(anchor: LocalDate, before: LocalDate): LocalDate? {
    if (anchor >= before) return null
    var candidate = nextOccurrence(anchor, before)
    var steps = stepsBetween(anchor, candidate)
    while (candidate >= before && steps > 0) {
        steps--
        candidate = advance(anchor, steps)
    }
    return candidate.takeIf { it < before }
}

/** How many whole cycles separate [anchor] from [date], assuming [date] sits on the cycle. */
private fun BillingCycle.stepsBetween(anchor: LocalDate, date: LocalDate): Int = when (period) {
    BillingPeriod.DAY -> anchor.daysUntil(date) / interval
    BillingPeriod.WEEK -> anchor.daysUntil(date) / (7 * interval)
    BillingPeriod.MONTH -> ((date.year - anchor.year) * 12 + (date.month.number - anchor.month.number)) / interval
    BillingPeriod.YEAR -> (date.year - anchor.year) / interval
}

/** [anchor] moved forward by [steps] whole cycles. */
fun BillingCycle.advance(anchor: LocalDate, steps: Int): LocalDate = when (period) {
    BillingPeriod.DAY -> anchor.plus(steps * interval, DateTimeUnit.DAY)
    BillingPeriod.WEEK -> anchor.plus(steps * interval * 7, DateTimeUnit.DAY)
    BillingPeriod.MONTH -> anchor.plus(steps * interval, DateTimeUnit.MONTH)
    BillingPeriod.YEAR -> anchor.plus(steps * interval, DateTimeUnit.YEAR)
}

private fun monthsBetween(from: LocalDate, to: LocalDate): Int =
    (to.year - from.year) * 12 + (to.month.number - from.month.number)
