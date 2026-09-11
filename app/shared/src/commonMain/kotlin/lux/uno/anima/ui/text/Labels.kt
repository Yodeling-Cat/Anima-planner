package lux.uno.anima.ui.text

import androidx.compose.runtime.Composable
import lux.uno.anima.dev.AppEnvironment
import lux.uno.anima.dev.Environments
import lux.uno.anima.finance.model.BillingCycle
import lux.uno.anima.finance.model.BillingPeriod
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.PaymentMethodKind
import lux.uno.anima.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The names `core` deliberately does not carry.
 *
 * Everything here reads as a label on screen, which makes it language rather than domain —
 * see the note at the top of `ui/text/Dates.kt`.
 */

/** "Monthly", "Every 3 months", "Každých 6 mesiacov". */
@Composable
fun BillingCycle.label(): String = if (interval == 1) {
    stringResource(
        when (period) {
            BillingPeriod.DAY -> Res.string.billing_daily
            BillingPeriod.WEEK -> Res.string.billing_weekly
            BillingPeriod.MONTH -> Res.string.billing_monthly
            BillingPeriod.YEAR -> Res.string.billing_yearly
        },
    )
} else {
    pluralStringResource(
        when (period) {
            BillingPeriod.DAY -> Res.plurals.billing_every_days
            BillingPeriod.WEEK -> Res.plurals.billing_every_weeks
            BillingPeriod.MONTH -> Res.plurals.billing_every_months
            BillingPeriod.YEAR -> Res.plurals.billing_every_years
        },
        interval,
        interval,
    )
}

/** "/mo", "/yr", "/mes" — the suffix that sits against a price with no space. */
@Composable
fun BillingCycle.shortSuffix(): String = if (interval == 1) {
    stringResource(
        when (period) {
            BillingPeriod.DAY -> Res.string.billing_suffix_day
            BillingPeriod.WEEK -> Res.string.billing_suffix_week
            BillingPeriod.MONTH -> Res.string.billing_suffix_month
            BillingPeriod.YEAR -> Res.string.billing_suffix_year
        },
    )
} else {
    stringResource(
        when (period) {
            BillingPeriod.DAY -> Res.string.billing_suffix_days
            BillingPeriod.WEEK -> Res.string.billing_suffix_weeks
            BillingPeriod.MONTH -> Res.string.billing_suffix_months
            BillingPeriod.YEAR -> Res.string.billing_suffix_years
        },
        interval,
    )
}

@Composable
fun PaymentMethodKind.label(): String = stringResource(
    when (this) {
        PaymentMethodKind.CARD -> Res.string.payment_kind_card
        PaymentMethodKind.BANK -> Res.string.payment_kind_bank
        PaymentMethodKind.WALLET -> Res.string.payment_kind_wallet
        PaymentMethodKind.CASH -> Res.string.payment_kind_cash
        PaymentMethodKind.OTHER -> Res.string.payment_kind_other
    },
)

/**
 * A category's name as it should appear.
 *
 * Every category but one is something the user named, so its label is shown as typed. The
 * stand-in for having none is the exception: nobody named it, it is never stored on a
 * record, and it has to read in the language of whoever is looking.
 */
@Composable
fun Category.displayLabel(): String =
    if (id == Category.Uncategorised.id) stringResource(Res.string.category_uncategorised) else label

/**
 * The built-in categories, with their labels translated.
 *
 * Ids, glyphs and colours come straight from [Category.defaults] and are identical in every
 * language, so two devices set up in different languages still seed the same eight records
 * and sync converges on one list rather than sixteen.
 */
@Composable
fun seededCategories(): List<Category> = Category.defaults().map { category ->
    val label = seededCategoryLabels[category.id]?.let { stringResource(it) }
    if (label == null) category else category.copy(label = label)
}

private val seededCategoryLabels: Map<String, StringResource> = mapOf(
    "cat-entertainment" to Res.string.category_default_entertainment,
    "cat-software" to Res.string.category_default_software,
    "cat-music" to Res.string.category_default_music,
    "cat-health" to Res.string.category_default_health,
    "cat-home" to Res.string.category_default_home,
    "cat-finance" to Res.string.category_default_finance,
    "cat-learning" to Res.string.category_default_learning,
    "cat-transport" to Res.string.category_default_transport,
)

@Composable
fun AppEnvironment.label(): String = stringResource(
    when (id) {
        Environments.Development.id -> Res.string.environment_dev_label
        Environments.Sandbox.id -> Res.string.environment_local_label
        else -> Res.string.environment_prod_label
    },
)

@Composable
fun AppEnvironment.description(): String = stringResource(
    when (id) {
        Environments.Development.id -> Res.string.environment_dev_description
        Environments.Sandbox.id -> Res.string.environment_local_description
        else -> Res.string.environment_prod_description
    },
)
