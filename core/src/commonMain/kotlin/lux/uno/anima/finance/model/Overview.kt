package lux.uno.anima.finance.model

import kotlinx.datetime.LocalDate

/** One charge that is going to land, with the amount already resolved from price history. */
data class UpcomingCharge(
    val subscription: Subscription,
    val date: LocalDate,
    val amount: Money,
    val daysAway: Int,
)

/** Running totals for one currency. Currencies are kept apart rather than converted. */
data class CurrencyTotal(
    val currency: String,
    val monthly: Money,
    val yearly: Money,
    val subscriptionCount: Int,
)

/** How much of the monthly total one category accounts for. */
data class CategorySlice(
    val category: Category,
    val monthly: Money,
    val share: Double,
)

/** Everything the finance home screen needs, computed in one pass. */
data class FinanceOverview(
    val today: LocalDate,
    val totals: List<CurrencyTotal>,
    val primaryTotal: CurrencyTotal?,
    val activeCount: Int,
    val upcoming: List<UpcomingCharge>,
    val trialsEndingSoon: List<Subscription>,
    val categories: List<CategorySlice>,
)

/**
 * Folds the whole subscription list into the numbers shown on the finance dashboard.
 * [preferredCurrency] decides which total gets the headline slot.
 */
fun buildOverview(
    subscriptions: List<Subscription>,
    categories: List<Category>,
    today: LocalDate,
    preferredCurrency: String,
    upcomingWindowDays: Int = 45,
): FinanceOverview {
    val active = subscriptions.filter { it.isActive }

    val totals = active
        .groupBy { it.currency }
        .map { (currency, group) ->
            CurrencyTotal(
                currency = currency,
                monthly = Money(group.sumOf { it.monthlyCost(today).minor }, currency),
                yearly = Money(group.sumOf { it.yearlyCost(today).minor }, currency),
                subscriptionCount = group.size,
            )
        }
        .sortedByDescending { it.monthly.minor }

    val primary = totals.firstOrNull { it.currency == preferredCurrency } ?: totals.firstOrNull()

    val upcoming = active
        .map { subscription ->
            val date = subscription.nextRenewal(today)
            UpcomingCharge(
                subscription = subscription,
                date = date,
                amount = subscription.currentPrice(today),
                daysAway = subscription.daysUntilRenewal(today),
            )
        }
        .filter { it.daysAway <= upcomingWindowDays }
        .sortedBy { it.date }

    val trials = active
        .filter { it.trial != null && it.daysUntilTrialEnds(today).let { days -> days != null && days >= 0 } }
        .sortedBy { it.trial?.endsOn }

    val categorySlices = primary?.let { headline ->
        active
            .filter { it.currency == headline.currency }
            .groupBy { categories.resolve(it.categoryId) }
            .map { (category, group) ->
                val monthly = Money(group.sumOf { it.monthlyCost(today).minor }, headline.currency)
                CategorySlice(
                    category = category,
                    monthly = monthly,
                    share = if (headline.monthly.minor == 0L) 0.0
                    else monthly.minor.toDouble() / headline.monthly.minor.toDouble(),
                )
            }
            .sortedByDescending { it.monthly.minor }
    }.orEmpty()

    return FinanceOverview(
        today = today,
        totals = totals,
        primaryTotal = primary,
        activeCount = active.size,
        upcoming = upcoming,
        trialsEndingSoon = trials,
        categories = categorySlices,
    )
}
