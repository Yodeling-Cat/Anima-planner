package lux.uno.anima.finance.model

import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.rates.ExchangeRates
import lux.uno.anima.finance.rates.sumInto

/** One charge that is going to land, with the amount already resolved from price history. */
data class UpcomingCharge(
    val subscription: Subscription,
    val date: LocalDate,
    val amount: Money,
    val daysAway: Int,
)

/** Running totals for one currency. */
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
    /** Per-currency subtotals, untouched by conversion. Kept for diagnostics and tests. */
    val totals: List<CurrencyTotal>,
    val primaryTotal: CurrencyTotal?,
    /**
     * True when [primaryTotal] was arrived at by converting across currencies, which makes
     * it an estimate against a daily reference rate rather than a sum of real charges. The
     * UI marks it; nothing else should have to care.
     */
    val isConverted: Boolean,
    /**
     * Currencies held by active subscriptions that no rate was available for, so their spend
     * is *not* in [primaryTotal]. Empty is the normal case and the only one worth trusting a
     * total from — see [lux.uno.anima.finance.rates.ConversionResult].
     */
    val unconvertedCurrencies: List<String>,
    val activeCount: Int,
    val upcoming: List<UpcomingCharge>,
    val trialsEndingSoon: List<Subscription>,
    val categories: List<CategorySlice>,
)

/**
 * Folds the whole subscription list into the numbers shown on the finance dashboard.
 *
 * [preferredCurrency] is the currency the user asked to see money in. Given [rates] that
 * cover every currency in use, the headline is every active subscription converted into it —
 * one number for the whole month, which is the question being asked. Without those rates the
 * headline falls back to the largest single currency's own subtotal, because a total that
 * silently omits a third of the subscriptions is worse than one that admits its scope.
 */
fun buildOverview(
    subscriptions: List<Subscription>,
    categories: List<Category>,
    today: LocalDate,
    preferredCurrency: String,
    rates: ExchangeRates? = null,
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

    val monthly = rates.sumInto(active.map { it.monthlyCost(today) }, preferredCurrency)
    val yearly = rates.sumInto(active.map { it.yearlyCost(today) }, preferredCurrency)
    val canConvertEverything = active.isNotEmpty() && monthly.isComplete && yearly.isComplete

    val primary = if (canConvertEverything) {
        CurrencyTotal(preferredCurrency, monthly.total, yearly.total, active.size)
    } else {
        // Nothing can set a preference the rates cannot honour, so the fallback goes to
        // where the money actually is; the preference only breaks a tie between equals.
        totals.maxWithOrNull(
            compareBy<CurrencyTotal> { it.monthly.minor }.thenBy { it.currency == preferredCurrency },
        )
    }

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

    // The breakdown has to add up to the headline, so it is drawn from the same population:
    // every active subscription when the headline converts, and only the headline currency's
    // own when it does not. Mixing the two would put slices under a total they are not part of.
    val categorySlices = primary?.let { headline ->
        active
            .filter { canConvertEverything || it.currency == headline.currency }
            .groupBy { categories.resolve(it.categoryId) }
            .map { (category, group) ->
                val slice = rates.sumInto(group.map { it.monthlyCost(today) }, headline.currency)
                CategorySlice(
                    category = category,
                    monthly = slice.total,
                    share = if (headline.monthly.minor == 0L) 0.0
                    else slice.total.minor.toDouble() / headline.monthly.minor.toDouble(),
                )
            }
            .sortedByDescending { it.monthly.minor }
    }.orEmpty()

    return FinanceOverview(
        today = today,
        totals = totals,
        primaryTotal = primary,
        // One currency that already is the preferred one is not a conversion, and marking it
        // as an estimate would be a lie about a figure that came straight off the record.
        isConverted = canConvertEverything && totals.any { it.currency != preferredCurrency },
        unconvertedCurrencies = if (canConvertEverything) emptyList()
        else totals.map { it.currency }.filter { it != primary?.currency },
        activeCount = active.size,
        upcoming = upcoming,
        trialsEndingSoon = trials,
        categories = categorySlices,
    )
}
