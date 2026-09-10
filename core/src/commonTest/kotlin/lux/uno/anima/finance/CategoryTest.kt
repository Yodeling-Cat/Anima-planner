package lux.uno.anima.finance

import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.model.BillingCycle
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.model.buildOverview
import lux.uno.anima.finance.model.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CategoryTest {

    private val today = LocalDate(2026, 9, 10)

    private val coffee = Category("cat-coffee", "Coffee", "☕️", 0xFFE0A458)
    private val tools = Category("cat-tools", "Design tools", "🛠", 0xFFB07CF5)

    private fun subscription(id: String, categoryId: String?, monthly: Double) = Subscription(
        id = id,
        name = id,
        cycle = BillingCycle.Monthly,
        anchorDate = today,
        priceHistory = listOf(PricePoint(today, Money.of(monthly))),
        categoryId = categoryId,
    )

    @Test
    fun resolveFindsAStoredCategory() {
        assertEquals(coffee, listOf(coffee, tools).resolve("cat-coffee"))
    }

    @Test
    fun resolveFallsBackWhenTheCategoryIsMissing() {
        assertEquals(Category.Uncategorised, listOf(coffee).resolve(null))
        assertEquals(Category.Uncategorised, listOf(coffee).resolve("cat-deleted"))
        assertEquals(Category.Uncategorised, emptyList<Category>().resolve("cat-coffee"))
    }

    @Test
    fun overviewGroupsSpendByCategory() {
        val overview = buildOverview(
            subscriptions = listOf(
                subscription("a", coffee.id, 10.0),
                subscription("b", coffee.id, 5.0),
                subscription("c", tools.id, 25.0),
            ),
            categories = listOf(coffee, tools),
            today = today,
            preferredCurrency = "USD",
        )

        assertEquals(Money.of(40.0), overview.primaryTotal?.monthly)
        assertEquals(2, overview.categories.size)
        // Sorted by spend, so the design tools bucket leads.
        assertEquals(tools, overview.categories[0].category)
        assertEquals(Money.of(25.0), overview.categories[0].monthly)
        assertEquals(Money.of(15.0), overview.categories[1].monthly)
        assertEquals(0.625, overview.categories[0].share)
    }

    @Test
    fun subscriptionsWithADeletedCategoryLandInOneUncategorisedBucket() {
        val overview = buildOverview(
            subscriptions = listOf(
                subscription("a", "cat-gone", 10.0),
                subscription("b", null, 10.0),
            ),
            categories = listOf(coffee),
            today = today,
            preferredCurrency = "USD",
        )

        assertEquals(1, overview.categories.size)
        assertEquals(Category.Uncategorised, overview.categories.single().category)
        assertEquals(Money.of(20.0), overview.categories.single().monthly)
    }

    @Test
    fun migrationSeedsTheStartingCategoriesForAnOlderFile() {
        val legacy = FinanceData(schemaVersion = 1)
        val migrated = legacy.migrated()

        assertEquals(FINANCE_SCHEMA_VERSION, migrated.schemaVersion)
        assertTrue(migrated.categories.isNotEmpty())
    }

    @Test
    fun migrationLeavesACurrentFileAlone() {
        val current = FinanceData(categories = listOf(coffee))
        assertEquals(current, current.migrated())
    }
}
