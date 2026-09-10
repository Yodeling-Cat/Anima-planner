package lux.uno.anima.finance

import kotlinx.serialization.Serializable
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.Subscription

/** The shape currently written to disk. Bumped whenever a migration is needed. */
const val FINANCE_SCHEMA_VERSION: Int = 2

/** Preferences that shape how money is shown, rather than what is owed. */
@Serializable
data class FinanceSettings(
    val preferredCurrency: String = "USD",
)

/** The whole finance dataset, and the shape written to disk. */
@Serializable
data class FinanceData(
    val subscriptions: List<Subscription> = emptyList(),
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val categories: List<Category> = emptyList(),
    val settings: FinanceSettings = FinanceSettings(),
    val schemaVersion: Int = FINANCE_SCHEMA_VERSION,
)

/**
 * Brings a loaded file up to the current shape. Version 1 had categories as a fixed enum on
 * each subscription; that field is dropped on read, so those records land uncategorised and
 * the built-in list is seeded for them to be sorted into again.
 */
fun FinanceData.migrated(): FinanceData = when {
    schemaVersion >= FINANCE_SCHEMA_VERSION && categories.isNotEmpty() -> this
    categories.isEmpty() -> copy(
        categories = Category.defaults(),
        schemaVersion = FINANCE_SCHEMA_VERSION,
    )
    else -> copy(schemaVersion = FINANCE_SCHEMA_VERSION)
}

/** A brand new dataset, with the starting categories already in place. */
fun emptyFinanceData(): FinanceData = FinanceData(categories = Category.defaults())
