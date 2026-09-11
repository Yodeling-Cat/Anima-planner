package lux.uno.anima.finance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
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
import lux.uno.anima.finance.sync.applying
import lux.uno.anima.sync.HybridClock
import lux.uno.anima.sync.SyncRecord

/**
 * The single source of truth for finance data. Everything the UI does goes through here,
 * and every mutation is written straight back to [store] so nothing is lost on kill.
 */
class FinanceRepository(
    private val store: FinanceStore,
    private val scope: CoroutineScope,
    private val seedWhenEmpty: () -> FinanceData? = { null },
    /**
     * The starting categories a brand new dataset is seeded with.
     *
     * Passed in rather than read from [Category.defaults] directly so the UI can hand over
     * the same ids, glyphs and colours with the labels in the reader's language. Ids and
     * stamps are what sync cares about, and those are identical either way, so two devices
     * set up in different languages still converge on one list.
     */
    private val seedCategories: List<Category> = Category.defaults(),
    /**
     * Stamps every change so another device can tell which version is newer.
     *
     * Deliberately has no default. Its node id has to be the one stored in `SyncState`, and
     * a default would quietly hand out a fresh one per instance — which reads as a brand new
     * device every launch, owing the server everything it had already sent.
     */
    val clock: HybridClock,
) {
    private val _data = MutableStateFlow(FinanceData())
    val data: StateFlow<FinanceData> = _data.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _localEdits = MutableStateFlow(0L)

    /**
     * Counts changes made *here*, so a sync can be triggered by them.
     *
     * Watching the data itself would also fire for records just pulled from the server,
     * and each pull would schedule a sync that had nothing to send.
     */
    val localEdits: StateFlow<Long> = _localEdits.asStateFlow()

    private val writeLock = Mutex()

    suspend fun load() {
        val onDisk = store.load()
        val stored = onDisk?.migrated(seedCategories)
        val migrating = (onDisk?.schemaVersion ?: FINANCE_SCHEMA_VERSION) < STABLE_NODE_SCHEMA_VERSION
        val loaded = stored ?: seedWhenEmpty() ?: emptyFinanceData(seedCategories)

        // Carry the clock past every reading in the file before issuing any of its own. The
        // highest stamp on disk is in effect the last reading this device gave out, so it
        // resumes above them even though nothing records it separately — and a wall clock
        // that jumped backwards while the app was closed cannot reissue one.
        loaded.meta.values.forEach { clock.observe(it.hlc) }

        // Records stamped before the node id was persisted name a device that no longer
        // exists, and nothing would ever recognise them as this one's to send.
        val stamped = (if (migrating) loaded.adoptedBy(clock) else loaded).stamped(clock)
        _data.value = stamped
        _isLoaded.value = true

        // Seeded, migrated, or newly stamped: whichever it was, the file is now behind.
        if (stamped != stored) writeLock.withLock { store.save(stamped) }
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

    /**
     * Folds records from the server in.
     *
     * Deliberately not [mutate]: that stamps whatever changed under this device's clock,
     * which would rewrite every incoming reading and make each pull look like a fresh local
     * edit — the two devices would then push newer versions at each other forever. The
     * readings are set by `applying` instead, from the records themselves.
     */
    suspend fun applyRemote(records: List<SyncRecord>): FinanceData {
        if (records.isEmpty()) return _data.value
        val snapshot = _data.updateAndGet { it.applying(records, clock) }
        writeLock.withLock { store.save(snapshot) }
        return snapshot
    }

    /**
     * Points subscriptions at the new ids their icons were renamed to.
     *
     * A real edit, stamped like any other: the record genuinely changed, and every device
     * has to learn the new id. Two devices migrating independently arrive at the same hash,
     * so whichever stamp wins the content is identical either way.
     */
    fun applyIconMigration(moved: Map<String, String>) {
        if (moved.isEmpty()) return
        if (_data.value.subscriptions.none { it.icon.imageId in moved }) return
        mutate { current ->
            current.copy(
                subscriptions = current.subscriptions.map { subscription ->
                    val replacement = moved[subscription.icon.imageId] ?: return@map subscription
                    subscription.copy(icon = subscription.icon.copy(imageId = replacement))
                },
            )
        }
    }

    /**
     * Throws this environment's dataset away and starts over.
     *
     * Only ever reached by someone signing out and explicitly asking for it. The reset is
     * seeded exactly as a brand new install would be — defaults stamped so they lose — so
     * signing back in pulls the real data down rather than pushing a blank slate over it.
     */
    suspend fun resetLocalData() {
        val fresh = emptyFinanceData(seedCategories)
        _data.value = fresh
        writeLock.withLock { store.save(fresh) }
    }

    private fun mutate(transform: (FinanceData) -> FinanceData) {
        // Every mutator goes through here, so this is the one place stamping has to happen —
        // and the one place it cannot be forgotten.
        val snapshot = _data.updateAndGet { previous ->
            transform(previous).restamped(previous, clock)
        }
        _localEdits.value += 1
        scope.launch { writeLock.withLock { store.save(snapshot) } }
    }
}
