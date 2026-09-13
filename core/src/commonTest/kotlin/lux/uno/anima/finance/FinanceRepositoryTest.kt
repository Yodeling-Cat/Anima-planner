package lux.uno.anima.finance

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.IconRef
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.model.Trial
import lux.uno.anima.finance.store.InMemoryFinanceStore
import lux.uno.anima.sync.HybridClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mutators, and the two things they all have to get right: a dangling id is cleared
 * rather than left pointing at nothing, and every local change is stamped and counted so a
 * sync knows there is something to send.
 */
class FinanceRepositoryTest {

    private val day = LocalDate(2026, 9, 11)

    private fun TestScope.repository(data: FinanceData = FinanceData()) = FinanceRepository(
        store = InMemoryFinanceStore(data),
        scope = this,
        clock = HybridClock("test-device"),
    )

    private fun subscription(
        id: String = "sub-1",
        categoryId: String? = null,
        paymentMethodId: String? = null,
        prices: List<PricePoint> = listOf(PricePoint(day, Money.of(9.99))),
    ) = Subscription(
        id = id,
        name = "Netflix",
        anchorDate = day,
        categoryId = categoryId,
        paymentMethodId = paymentMethodId,
        priceHistory = prices,
    )

    @Test
    fun upsertAddsThenReplacesRatherThanAppendingTwice() = runTest {
        val repository = repository().also { it.load() }

        repository.upsertSubscription(subscription())
        repository.upsertSubscription(subscription().copy(name = "Netflix Standard"))

        val held = repository.data.value.subscriptions.single()
        assertEquals("Netflix Standard", held.name)
    }

    @Test
    fun upsertKeepsTheOrderOfTheRecordsAroundIt() = runTest {
        val repository = repository().also { it.load() }
        repository.upsertSubscription(subscription(id = "a"))
        repository.upsertSubscription(subscription(id = "b"))
        repository.upsertSubscription(subscription(id = "c"))

        repository.upsertSubscription(subscription(id = "b").copy(name = "Edited"))

        assertEquals(listOf("a", "b", "c"), repository.data.value.subscriptions.map { it.id })
    }

    @Test
    fun deletingAPaymentMethodClearsItFromEverySubscription() = runTest {
        val repository = repository(
            FinanceData(
                paymentMethods = listOf(PaymentMethod(id = "pm", name = "Visa")),
                subscriptions = listOf(
                    subscription(id = "a", paymentMethodId = "pm"),
                    subscription(id = "b", paymentMethodId = "other"),
                ),
            ),
        ).also { it.load() }

        repository.deletePaymentMethod("pm")

        val subscriptions = repository.data.value.subscriptions.associateBy { it.id }
        assertNull(subscriptions.getValue("a").paymentMethodId)
        assertEquals("other", subscriptions.getValue("b").paymentMethodId)
        assertTrue(repository.data.value.paymentMethods.isEmpty())
    }

    @Test
    fun deletingACategoryLeavesItsSubscriptionsUncategorised() = runTest {
        val repository = repository(
            FinanceData(
                categories = listOf(Category(id = "cat", label = "Streaming")),
                subscriptions = listOf(subscription(categoryId = "cat")),
            ),
        ).also { it.load() }

        repository.deleteCategory("cat")

        assertNull(repository.data.value.subscriptions.single().categoryId)
        assertTrue(repository.data.value.categories.isEmpty())
    }

    @Test
    fun recordingAPriceOnADayThatAlreadyHasOneReplacesIt() = runTest {
        val repository = repository(FinanceData(subscriptions = listOf(subscription()))).also { it.load() }

        repository.recordPriceChange("sub-1", Money.of(12.99), day, note = "raise")

        val history = repository.data.value.subscriptions.single().priceHistory
        assertEquals(1, history.size)
        assertEquals(Money.of(12.99), history.single().price)
        assertEquals("raise", history.single().note)
    }

    @Test
    fun priceHistoryStaysInDateOrder() = runTest {
        val repository = repository(FinanceData(subscriptions = listOf(subscription()))).also { it.load() }

        repository.recordPriceChange("sub-1", Money.of(14.99), LocalDate(2027, 1, 1))
        repository.recordPriceChange("sub-1", Money.of(11.99), LocalDate(2026, 6, 1))

        val dates = repository.data.value.subscriptions.single().priceHistory.map { it.effectiveFrom }
        assertEquals(dates.sorted(), dates)
    }

    @Test
    fun aBlankNoteIsStoredAsNoNote() = runTest {
        val repository = repository(FinanceData(subscriptions = listOf(subscription()))).also { it.load() }

        repository.recordPriceChange("sub-1", Money.of(12.99), LocalDate(2027, 1, 1), note = "   ")

        assertNull(repository.data.value.subscriptions.single().priceHistory.last().note)
    }

    @Test
    fun theLastPricePointCannotBeDeleted() = runTest {
        // A subscription with no price at all would render as free, which is worse than
        // refusing the delete.
        val repository = repository(FinanceData(subscriptions = listOf(subscription()))).also { it.load() }

        repository.deletePricePoint("sub-1", day)

        assertEquals(1, repository.data.value.subscriptions.single().priceHistory.size)
    }

    @Test
    fun anEarlierPricePointCanBeDeletedOnceThereAreTwo() = runTest {
        val history = listOf(PricePoint(day, Money.of(9.99)), PricePoint(LocalDate(2027, 1, 1), Money.of(12.99)))
        val repository = repository(FinanceData(subscriptions = listOf(subscription(prices = history))))
            .also { it.load() }

        repository.deletePricePoint("sub-1", day)

        assertEquals(
            listOf(LocalDate(2027, 1, 1)),
            repository.data.value.subscriptions.single().priceHistory.map { it.effectiveFrom },
        )
    }

    @Test
    fun endingATrialLeavesTheRestOfTheSubscriptionAlone() = runTest {
        val trialling = subscription().copy(trial = Trial(endsOn = LocalDate(2026, 10, 1)))
        val repository = repository(FinanceData(subscriptions = listOf(trialling))).also { it.load() }

        repository.endTrial("sub-1")

        val held = repository.data.value.subscriptions.single()
        assertNull(held.trial)
        assertEquals(trialling.copy(trial = null), held)
    }

    @Test
    fun everyLocalEditIsCountedSoASyncKnowsThereIsSomethingToSend() = runTest {
        val repository = repository().also { it.load() }
        val before = repository.localEdits.value

        repository.upsertSubscription(subscription())
        repository.setSubscriptionActive("sub-1", active = false)
        repository.deleteSubscription("sub-1")

        assertEquals(before + 3, repository.localEdits.value)
    }

    @Test
    fun everyLocalEditMovesTheStamp() = runTest {
        val repository = repository().also { it.load() }
        repository.upsertSubscription(subscription())
        val first = repository.data.value.meta.values.map { it.hlc }

        repository.upsertSubscription(subscription().copy(name = "Edited"))

        assertNotEquals(first, repository.data.value.meta.values.map { it.hlc })
    }

    @Test
    fun anIconMigrationThatTouchesNothingIsNotAnEdit() = runTest {
        // It runs on every launch, and counting it would have a sync scheduled each time for
        // a dataset nobody changed.
        val repository = repository(FinanceData(subscriptions = listOf(subscription()))).also { it.load() }
        val before = repository.localEdits.value

        repository.applyIconMigration(emptyMap())
        repository.applyIconMigration(mapOf("some-other-image" to "renamed"))

        assertEquals(before, repository.localEdits.value)
    }

    @Test
    fun anIconMigrationThatMatchesRepointsTheSubscription() = runTest {
        val withIcon = subscription().copy(icon = IconRef(imageId = "old-hash"))
        val repository = repository(FinanceData(subscriptions = listOf(withIcon))).also { it.load() }

        repository.applyIconMigration(mapOf("old-hash" to "new-hash"))

        assertEquals("new-hash", repository.data.value.subscriptions.single().icon.imageId)
    }

    @Test
    fun switchingOffThePreferredCurrencyMovesThePreference() = runTest {
        val repository = repository().also { it.load() }
        repository.setPreferredCurrency("USD")

        repository.setCurrencyEnabled("USD", enabled = false)

        val settings = repository.data.value.settings
        assertNotEquals("USD", settings.preferredCurrency)
        assertTrue(settings.preferredCurrency in settings.availableCurrencies.map { it.code })
    }

    @Test
    fun aResetLeavesADatasetThatLosesToWhateverTheServerHolds() = runTest {
        // Signing back in has to pull the real data down rather than push a blank slate over
        // it, which is what the zero stamps on a fresh dataset buy.
        val repository = repository(FinanceData(subscriptions = listOf(subscription()))).also { it.load() }

        repository.resetLocalData()

        val fresh = repository.data.value
        assertTrue(fresh.subscriptions.isEmpty())
        assertEquals(emptyFinanceData(Category.defaults()), fresh)
    }

    @Test
    fun loadingMarksTheRepositoryReady() = runTest {
        val repository = repository()
        assertTrue(!repository.isLoaded.value)

        repository.load()

        assertTrue(repository.isLoaded.value)
    }

    @Test
    fun loadingCarriesTheClockPastEveryStampOnDisk() = runTest {
        // Otherwise a wall clock that went backwards while the app was closed would reissue a
        // reading the file already holds, and the older edit would win.
        val first = repository(FinanceData(subscriptions = listOf(subscription()))).also { it.load() }
        first.upsertSubscription(subscription().copy(name = "Edited"))
        val onDisk = first.data.value
        val highest = onDisk.meta.values.maxOf { it.hlc }

        val reopened = FinanceRepository(
            store = InMemoryFinanceStore(onDisk),
            scope = this,
            clock = HybridClock("test-device"),
        ).also { it.load() }
        reopened.upsertSubscription(subscription().copy(name = "Edited again"))

        assertTrue(reopened.data.value.meta.values.maxOf { it.hlc } > highest)
    }
}
