package lux.uno.anima.finance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.store.FinanceStore

/**
 * The single source of truth for finance data. Everything the UI does goes through here,
 * and every mutation is written straight back to [store] so nothing is lost on kill.
 */
class FinanceRepository(
    private val store: FinanceStore,
    private val scope: CoroutineScope,
    private val seedWhenEmpty: () -> FinanceData? = { null },
) {
    private val _data = MutableStateFlow(FinanceData())
    val data: StateFlow<FinanceData> = _data.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val writeLock = Mutex()

    suspend fun load() {
        val loaded = store.load()?.migrated()
            ?: seedWhenEmpty()?.also { store.save(it) }
            ?: emptyFinanceData()
        _data.value = loaded
        _isLoaded.value = true
    }

    // region subscriptions

    fun upsertSubscription(subscription: Subscription) = mutate { current ->
        val index = current.subscriptions.indexOfFirst { it.id == subscription.id }
        val next = if (index >= 0) {
            current.subscriptions.toMutableList().apply { this[index] = subscription }
        } else {
            current.subscriptions + subscription
        }
        current.copy(subscriptions = next)
    }

    fun deleteSubscription(id: String) = mutate { current ->
        current.copy(subscriptions = current.subscriptions.filterNot { it.id == id })
    }

    fun setSubscriptionActive(id: String, active: Boolean) = mutate { current ->
        current.copy(
            subscriptions = current.subscriptions.map {
                if (it.id == id) it.copy(isActive = active) else it
            },
        )
    }

    /**
     * Records a new price taking effect on [effectiveFrom]. Replaces an existing point on the
     * same day rather than stacking two prices that both claim to start together.
     */
    fun recordPriceChange(id: String, price: Money, effectiveFrom: LocalDate, note: String? = null) =
        mutate { current ->
            current.copy(
                subscriptions = current.subscriptions.map { subscription ->
                    if (subscription.id != id) return@map subscription
                    val history = subscription.priceHistory
                        .filterNot { it.effectiveFrom == effectiveFrom }
                        .plus(PricePoint(effectiveFrom, price, note?.takeIf { it.isNotBlank() }))
                        .sortedBy { it.effectiveFrom }
                    subscription.copy(priceHistory = history)
                },
            )
        }

    fun deletePricePoint(id: String, effectiveFrom: LocalDate) = mutate { current ->
        current.copy(
            subscriptions = current.subscriptions.map { subscription ->
                if (subscription.id != id) return@map subscription
                // A subscription with no price at all would render as free; keep the last point.
                if (subscription.priceHistory.size <= 1) return@map subscription
                subscription.copy(
                    priceHistory = subscription.priceHistory.filterNot { it.effectiveFrom == effectiveFrom },
                )
            },
        )
    }

    fun endTrial(id: String) = mutate { current ->
        current.copy(
            subscriptions = current.subscriptions.map {
                if (it.id == id) it.copy(trial = null) else it
            },
        )
    }

    // endregion

    // region payment methods

    fun upsertPaymentMethod(method: PaymentMethod) = mutate { current ->
        val index = current.paymentMethods.indexOfFirst { it.id == method.id }
        val next = if (index >= 0) {
            current.paymentMethods.toMutableList().apply { this[index] = method }
        } else {
            current.paymentMethods + method
        }
        current.copy(paymentMethods = next)
    }

    /** Removing a method also clears it from the subscriptions that pointed at it. */
    fun deletePaymentMethod(id: String) = mutate { current ->
        current.copy(
            paymentMethods = current.paymentMethods.filterNot { it.id == id },
            subscriptions = current.subscriptions.map {
                if (it.paymentMethodId == id) it.copy(paymentMethodId = null) else it
            },
        )
    }

    // endregion

    // region categories

    fun upsertCategory(category: Category) = mutate { current ->
        val index = current.categories.indexOfFirst { it.id == category.id }
        val next = if (index >= 0) {
            current.categories.toMutableList().apply { this[index] = category }
        } else {
            current.categories + category
        }
        current.copy(categories = next)
    }

    /** Deleting a category leaves its subscriptions uncategorised rather than reassigning them. */
    fun deleteCategory(id: String) = mutate { current ->
        current.copy(
            categories = current.categories.filterNot { it.id == id },
            subscriptions = current.subscriptions.map {
                if (it.categoryId == id) it.copy(categoryId = null) else it
            },
        )
    }

    // endregion

    /** Replaces the entire dataset — used to drop the sample data in on a fresh install. */
    fun replaceAll(data: FinanceData) = mutate { data }

    fun setPreferredCurrency(currency: String) = mutate { current ->
        current.copy(settings = current.settings.copy(preferredCurrency = currency))
    }

    private fun mutate(transform: (FinanceData) -> FinanceData) {
        _data.update(transform)
        val snapshot = _data.value
        scope.launch { writeLock.withLock { store.save(snapshot) } }
    }
}
