package lux.uno.anima.finance

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.store.InMemoryFinanceStore
import lux.uno.anima.sync.Hlc
import lux.uno.anima.sync.HybridClock
import lux.uno.anima.sync.RecordKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordMetaTest {

    private val today = LocalDate(2026, 9, 11)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    /** A clock the test advances by hand, so readings are predictable and never equal. */
    private class TestClock {
        var millis = 1_757_000_000_000L
        val clock = HybridClock("test-device", wallClockMillis = { millis })
        fun advance() {
            millis += 1_000
        }
    }

    private fun subscription(id: String, name: String = "Netflix") = Subscription(
        id = id,
        name = name,
        anchorDate = today,
        priceHistory = listOf(PricePoint(today, Money.of(9.99))),
    )

    private fun FinanceData.metaOf(kind: String, id: String) = meta[metaKey(kind, id)]

    // region stamping on load

    @Test
    fun `a file written before sync existed gets every record stamped`() = runTest {
        val legacy = FinanceData(
            subscriptions = listOf(subscription("sub-1")),
            paymentMethods = listOf(PaymentMethod(id = "pm-1", name = "Visa")),
            categories = Category.defaults(),
            schemaVersion = 3,
        )
        val store = InMemoryFinanceStore(legacy)
        val repository = FinanceRepository(store, this, clock = TestClock().clock)

        repository.load()

        val data = repository.data.value
        assertNotNull(data.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))
        assertNotNull(data.metaOf(RecordKind.PAYMENT_METHOD, "pm-1"))
        assertNotNull(data.metaOf(RecordKind.CATEGORY, Category.defaults().first().id))
        assertNotNull(data.metaOf(RecordKind.SETTINGS, RecordKind.SETTINGS_ID))
        assertEquals(FINANCE_SCHEMA_VERSION, data.schemaVersion)
    }

    @Test
    fun `stamping a legacy file is written back rather than just held in memory`() = runTest {
        val store = InMemoryFinanceStore(
            FinanceData(subscriptions = listOf(subscription("sub-1")), schemaVersion = 3),
        )
        val repository = FinanceRepository(store, this, clock = TestClock().clock)

        repository.load()

        val saved = assertNotNull(store.load())
        assertNotNull(saved.meta[metaKey(RecordKind.SUBSCRIPTION, "sub-1")])
    }

    @Test
    fun `no record is stamped below the readings already in the file`() = runTest {
        // The case this guards: the app is closed, the wall clock moves backwards, and the
        // app reopens. Without taking the clock past what is on disk, the next edit would be
        // stamped into the past and lose to the version it was meant to replace.
        val future = Hlc.of(1_757_000_900_000, 0, "other-device")
        val store = InMemoryFinanceStore(
            FinanceData(
                subscriptions = listOf(subscription("sub-1")),
                meta = mapOf(metaKey(RecordKind.SUBSCRIPTION, "sub-1") to RecordMeta(future)),
            ),
        )
        val repository = FinanceRepository(store, this, clock = TestClock().clock)

        repository.load()
        repository.upsertSubscription(subscription("sub-2", name = "Spotify"))

        val stamped = assertNotNull(
            repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-2"),
        )
        assertTrue(stamped.hlc > future, "${stamped.hlc} should sort above $future")
    }

    // endregion

    // region stamping on change

    @Test
    fun `a new subscription is stamped`() = runTest {
        val repository = repository()
        repository.upsertSubscription(subscription("sub-1"))

        val meta = assertNotNull(repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))
        assertFalse(meta.deleted)
    }

    @Test
    fun `editing a record moves its reading forward`() = runTest {
        val clock = TestClock()
        val repository = repository(clock)
        repository.upsertSubscription(subscription("sub-1"))
        val before = assertNotNull(repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))

        clock.advance()
        repository.upsertSubscription(subscription("sub-1", name = "Netflix Premium"))

        val after = assertNotNull(repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))
        assertTrue(after.hlc > before.hlc)
    }

    @Test
    fun `saving a record unchanged leaves its reading alone`() = runTest {
        // Otherwise opening an editor and pressing save would make an untouched record look
        // newer than a real edit made on another device.
        val repository = repository()
        repository.upsertSubscription(subscription("sub-1"))
        val before = assertNotNull(repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))

        repository.upsertSubscription(subscription("sub-1"))

        assertEquals(before, repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))
    }

    @Test
    fun `an unrelated record keeps its reading`() = runTest {
        val repository = repository()
        repository.upsertSubscription(subscription("sub-1"))
        repository.upsertSubscription(subscription("sub-2", name = "Spotify"))
        val untouched = assertNotNull(repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))

        repository.upsertSubscription(subscription("sub-2", name = "Spotify Family"))

        assertEquals(untouched, repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))
    }

    // endregion

    // region deletion

    @Test
    fun `deleting leaves a tombstone and drops the record from the list`() = runTest {
        val repository = repository()
        repository.upsertSubscription(subscription("sub-1"))

        repository.deleteSubscription("sub-1")

        val data = repository.data.value
        assertTrue(data.subscriptions.isEmpty(), "the UI must not see a deleted record")
        assertTrue(assertNotNull(data.metaOf(RecordKind.SUBSCRIPTION, "sub-1")).deleted)
    }

    @Test
    fun `bringing an id back clears its tombstone`() = runTest {
        val clock = TestClock()
        val repository = repository(clock)
        repository.upsertSubscription(subscription("sub-1"))
        repository.deleteSubscription("sub-1")
        val tombstone = assertNotNull(repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))

        clock.advance()
        repository.upsertSubscription(subscription("sub-1", name = "Back again"))

        val revived = assertNotNull(repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))
        assertFalse(revived.deleted)
        assertTrue(revived.hlc > tombstone.hlc, "the revival has to out-vote the delete")
    }

    @Test
    fun `deleting a payment method restamps the subscriptions it cleared`() = runTest {
        // The reason stamping is worked out by comparison rather than declared: this change
        // to the subscriptions is a side effect nobody would think to report.
        val clock = TestClock()
        val repository = repository(clock)
        repository.upsertPaymentMethod(PaymentMethod(id = "pm-1", name = "Visa"))
        repository.upsertSubscription(subscription("sub-1").copy(paymentMethodId = "pm-1"))
        val before = assertNotNull(repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))

        clock.advance()
        repository.deletePaymentMethod("pm-1")

        val data = repository.data.value
        assertNull(data.subscriptions.single().paymentMethodId)
        val after = assertNotNull(data.metaOf(RecordKind.SUBSCRIPTION, "sub-1"))
        assertTrue(after.hlc > before.hlc, "the subscription changed, so it needs a new reading")
        assertTrue(assertNotNull(data.metaOf(RecordKind.PAYMENT_METHOD, "pm-1")).deleted)
    }

    @Test
    fun `stamps from before the node id was stable are adopted`() = runTest {
        // Otherwise these records would never be offered to the server: nothing recognises
        // a reading from a device that only existed for one launch as this device's to send.
        val stranded = Hlc.of(1_757_000_000_000, 0, "node-gone-forever")
        val store = InMemoryFinanceStore(
            FinanceData(
                subscriptions = listOf(subscription("sub-1")),
                meta = mapOf(metaKey(RecordKind.SUBSCRIPTION, "sub-1") to RecordMeta(stranded)),
                schemaVersion = 4,
            ),
        )
        val clock = HybridClock("node-here", wallClockMillis = { 1_757_000_500_000 })
        val repository = FinanceRepository(store, this, clock = clock)

        repository.load()

        val adopted = assertNotNull(
            repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1"),
        )
        assertEquals("node-here", adopted.hlc.nodeId)
        assertTrue(adopted.hlc > stranded)
    }

    @Test
    fun `a tombstone is adopted without being revived`() = runTest {
        val store = InMemoryFinanceStore(
            FinanceData(
                meta = mapOf(
                    metaKey(RecordKind.SUBSCRIPTION, "sub-gone") to
                        RecordMeta(Hlc.of(1, 0, "node-gone-forever"), deleted = true),
                ),
                schemaVersion = 4,
            ),
        )
        val repository = FinanceRepository(store, this, clock = HybridClock("node-here"))

        repository.load()

        val tombstone = assertNotNull(
            repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-gone"),
        )
        assertTrue(tombstone.deleted, "adoption must not resurrect anything")
        assertEquals("node-here", tombstone.hlc.nodeId)
    }

    @Test
    fun `a dataset already on the current version is left alone`() = runTest {
        // Adoption runs once. Repeating it would rewrite every reading on every launch, and
        // after syncing starts that would mean re-pushing the whole dataset each time.
        val settled = Hlc.of(1_757_000_000_000, 0, "node-elsewhere")
        val store = InMemoryFinanceStore(
            FinanceData(
                subscriptions = listOf(subscription("sub-1")),
                meta = mapOf(metaKey(RecordKind.SUBSCRIPTION, "sub-1") to RecordMeta(settled)),
                schemaVersion = FINANCE_SCHEMA_VERSION,
                categories = Category.defaults(),
            ),
        )
        val repository = FinanceRepository(store, this, clock = HybridClock("node-here"))

        repository.load()

        assertEquals(settled, repository.data.value.metaOf(RecordKind.SUBSCRIPTION, "sub-1")?.hlc)
    }

    // endregion

    @Test
    fun `settings carry a reading of their own`() = runTest {
        val clock = TestClock()
        val repository = repository(clock)
        repository.load()
        val before = assertNotNull(
            repository.data.value.metaOf(RecordKind.SETTINGS, RecordKind.SETTINGS_ID),
        )

        clock.advance()
        repository.setPreferredCurrency("EUR")

        val after = assertNotNull(
            repository.data.value.metaOf(RecordKind.SETTINGS, RecordKind.SETTINGS_ID),
        )
        assertTrue(after.hlc > before.hlc)
    }

    @Test
    fun `metadata survives a round trip through the file format`() = runTest {
        val repository = repository()
        repository.upsertSubscription(subscription("sub-1"))
        repository.deleteSubscription("sub-1")
        val original = repository.data.value

        val restored = json.decodeFromString(
            FinanceData.serializer(),
            json.encodeToString(FinanceData.serializer(), original),
        )

        assertEquals(original, restored)
        assertTrue(assertNotNull(restored.metaOf(RecordKind.SUBSCRIPTION, "sub-1")).deleted)
    }

    @Test
    fun `replacing everything stamps what arrived and buries what left`() = runTest {
        val clock = TestClock()
        val repository = repository(clock)
        repository.upsertSubscription(subscription("sub-old"))

        clock.advance()
        repository.replaceAll(SampleData.build(today))

        val data = repository.data.value
        assertTrue(assertNotNull(data.metaOf(RecordKind.SUBSCRIPTION, "sub-old")).deleted)
        data.subscriptions.forEach {
            assertNotNull(data.metaOf(RecordKind.SUBSCRIPTION, it.id), "unstamped: ${it.id}")
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.repository(
        clock: TestClock = TestClock(),
    ): FinanceRepository = FinanceRepository(InMemoryFinanceStore(), this, clock = clock.clock)
        .also { it.load() }
}
