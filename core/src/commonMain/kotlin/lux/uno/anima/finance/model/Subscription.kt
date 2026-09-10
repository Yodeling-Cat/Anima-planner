package lux.uno.anima.finance.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.serialization.Serializable

/** One price, and the day it took effect. The list of these is the price history. */
@Serializable
data class PricePoint(
    val effectiveFrom: LocalDate,
    val price: Money,
    val note: String? = null,
)

/** A free or discounted introductory period that turns into a real charge on [endsOn]. */
@Serializable
data class Trial(
    val endsOn: LocalDate,
    /** How many days ahead the app should warn. */
    val remindDaysBefore: Int = 3,
)

/**
 * How a subscription is pictured. An emoji is always present as the fallback; when
 * [imageId] points at a stored icon, that image is drawn instead. Keeping both means
 * deleting an image never leaves a record without an icon.
 */
@Serializable
data class IconRef(
    val emoji: String = "✦",
    val imageId: String? = null,
) {
    val usesImage: Boolean get() = imageId != null

    companion object {
        val Default = IconRef()
    }
}

/**
 * A recurring charge. Price is never stored as a single number — [priceHistory] holds every
 * price the service has had, so the chart and the "up 20% since you signed up" line come
 * straight from the record rather than from a separate log.
 */
@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val icon: IconRef = IconRef.Default,
    val colorArgb: Long = 0xFF6C7BFFL,
    val cycle: BillingCycle = BillingCycle.Monthly,
    /** The day the charge lands; every future renewal is this date advanced by whole cycles. */
    val anchorDate: LocalDate,
    val priceHistory: List<PricePoint> = emptyList(),
    val trial: Trial? = null,
    val paymentMethodId: String? = null,
    /** Null means uncategorised; a stale id is treated the same way. */
    val categoryId: String? = null,
    val notes: String = "",
    val isActive: Boolean = true,
) {
    /** Price history oldest-first, which is what both the chart and lookups expect. */
    val history: List<PricePoint> get() = priceHistory.sortedBy { it.effectiveFrom }

    val currency: String get() = history.lastOrNull()?.price?.currency ?: "USD"
}

/** The price in force on [on] — the most recent point that had already taken effect. */
fun Subscription.priceOn(on: LocalDate): Money {
    val sorted = history
    if (sorted.isEmpty()) return Money.zero()
    return sorted.lastOrNull { it.effectiveFrom <= on }?.price ?: sorted.first().price
}

fun Subscription.currentPrice(today: LocalDate): Money = priceOn(today)

/** What this subscription costs per average month, for comparing a yearly plan to a weekly one. */
fun Subscription.monthlyCost(today: LocalDate): Money {
    val price = currentPrice(today)
    return price.scaledBy(cycle.cyclesPerMonth)
}

fun Subscription.yearlyCost(today: LocalDate): Money =
    currentPrice(today).scaledBy(cycle.cyclesPerYear)

fun Subscription.nextRenewal(today: LocalDate): LocalDate = cycle.nextOccurrence(anchorDate, today)

fun Subscription.daysUntilRenewal(today: LocalDate): Int = today.daysUntil(nextRenewal(today))

/** True while the trial is still running on [today]. */
fun Subscription.isInTrial(today: LocalDate): Boolean = trial != null && trial.endsOn >= today

fun Subscription.daysUntilTrialEnds(today: LocalDate): Int? =
    trial?.let { today.daysUntil(it.endsOn) }

/** True when the trial ends soon enough that the user should see a warning. */
fun Subscription.trialNeedsAttention(today: LocalDate): Boolean {
    val trial = trial ?: return false
    val days = today.daysUntil(trial.endsOn)
    return days in 0..trial.remindDaysBefore
}

/** Change from the first recorded price to the price on [today], as a fraction (0.2 = up 20%). */
fun Subscription.priceChangeSinceStart(today: LocalDate): Double? {
    val sorted = history
    val first = sorted.firstOrNull()?.price ?: return null
    if (first.minor == 0L) return null
    val current = currentPrice(today)
    if (sorted.size < 2) return null
    return (current.minor - first.minor).toDouble() / first.minor.toDouble()
}

/** The most recent price rise or cut, if there has been one. */
fun Subscription.lastPriceChange(): Pair<PricePoint, PricePoint>? {
    val sorted = history
    if (sorted.size < 2) return null
    return sorted[sorted.size - 2] to sorted[sorted.size - 1]
}
