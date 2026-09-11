package lux.uno.anima.sync

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.FinanceRepository
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.store.InMemoryFinanceStore
import lux.uno.anima.platform.SESSION_TOKEN_KEY
import lux.uno.anima.platform.SecretProtection
import lux.uno.anima.platform.SecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private val today = LocalDate(2026, 9, 11)

    private class Secrets(private val values: MutableMap<String, String> = mutableMapOf()) : SecretStore {
        override val protection = SecretProtection.FILE_PERMISSIONS
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(key: String, value: String) {
            values[key] = value
        }

        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    /** A server that keeps records in a map and answers the way the real one does. */
    private class FakeApi : SyncApi {
        val held = mutableMapOf<RecordKey, SyncRecord>()
        var nextSeq = 1L
        var answer: (() -> ApiResult<SyncResponse>)? = null
        var requests = 0
        var pageSize = Int.MAX_VALUE

        override suspend fun sync(token: String, request: SyncRequest): ApiResult<SyncResponse> {
            requests++
            answer?.let { return it() }

            for (incoming in request.changes) {
                val existing = held[incoming.key]
                if (existing != null && existing.hlc >= incoming.hlc) continue
                held[incoming.key] = incoming.copy(seq = nextSeq++)
            }

            val feed = held.values.filter { it.seq > request.since }.sortedBy { it.seq }
            val page = feed.take(pageSize)
            val touched = request.changes.map { it.key }.toSet()
            val returned = page.map { it.key }.toSet()
            val alsoSend = (touched - returned).mapNotNull { held[it] }

            return ApiResult.Ok(
                SyncResponse(
                    cursor = maxOf(request.since, page.maxOfOrNull { it.seq } ?: 0L),
                    changes = page + alsoSend,
                    more = feed.size > page.size,
                ),
            )
        }

        override suspend fun register(email: String, password: String, joinPassword: String?) =
            ApiResult.Ok(Session("t", 0))
        override suspend fun logIn(email: String, password: String) = ApiResult.Ok(Session("t", 0))
        override suspend fun logOut(token: String) = ApiResult.Ok(Unit)

        /** Blobs, kept in a map so the engine's icon pass has something real to talk to. */
        val blobs = mutableMapOf<String, ByteArray>()
        override suspend fun missingBlobs(token: String, hashes: List<String>) =
            ApiResult.Ok(hashes.filterNot { it in blobs })

        override suspend fun putBlob(token: String, hash: String, bytes: ByteArray): ApiResult<Unit> {
            blobs[hash] = bytes
            return ApiResult.Ok(Unit)
        }

        override suspend fun getBlob(token: String, hash: String): ApiResult<ByteArray> =
            blobs[hash]?.let { ApiResult.Ok(it) } ?: ApiResult.Refused(404, "no such blob")
    }

    private class Device(val scope: TestScope, node: String, val api: SyncApi?) {
        val store = InMemoryFinanceStore()
        val state = InMemorySyncStateStore(SyncState(nodeId = node))
        val secrets = Secrets(mutableMapOf(SESSION_TOKEN_KEY to "a-token"))
        val repository = FinanceRepository(store, scope, clock = HybridClock(node))
        val engine = SyncEngine(repository, state, secrets, api)

        suspend fun start() = repository.load()
        val data: FinanceData get() = repository.data.value
    }

    private fun subscription(name: String = "Netflix", prices: List<PricePoint>) =
        Subscription(id = "sub-1", name = name, anchorDate = LocalDate(2026, 9, 11), priceHistory = prices)

    @Test
    fun `a local change reaches the server and a second device`() = runTest {
        val api = FakeApi()
        val phone = Device(this, "phone", api).also { it.start() }
        val desktop = Device(this, "desktop", api).also { it.start() }

        phone.repository.upsertSubscription(subscription(prices = listOf(PricePoint(today, Money.of(9.99)))))
        testScheduler.runCurrent()

        assertTrue(phone.engine.syncNow() is SyncOutcome.Synced)
        assertTrue(desktop.engine.syncNow() is SyncOutcome.Synced)

        assertEquals("Netflix", desktop.data.subscriptions.single().name)
    }

    @Test
    fun `a settled device sends nothing and asks for nothing`() = runTest {
        val api = FakeApi()
        val phone = Device(this, "phone", api).also { it.start() }
        phone.repository.upsertSubscription(subscription(prices = listOf(PricePoint(today, Money.of(9.99)))))
        testScheduler.runCurrent()
        phone.engine.syncNow()

        val outcome = phone.engine.syncNow()

        assertEquals(SyncOutcome.Synced(pushed = 0, received = 0), outcome)
    }

    @Test
    fun `records taken from the server are not sent straight back`() = runTest {
        val api = FakeApi()
        val phone = Device(this, "phone", api).also { it.start() }
        phone.repository.upsertSubscription(subscription(prices = listOf(PricePoint(today, Money.of(9.99)))))
        testScheduler.runCurrent()
        phone.engine.syncNow()

        val desktop = Device(this, "desktop", api).also { it.start() }
        desktop.engine.syncNow()
        val afterCatchUp = desktop.engine.syncNow()

        assertEquals(0, (afterCatchUp as SyncOutcome.Synced).pushed)
    }

    @Test
    fun `a merge made while applying still reaches the server`() = runTest {
        // The multi-pass case. The desktop's edit wins, so the phone unions the price
        // histories while taking it in — which happens after the phone already pushed.
        val api = FakeApi()
        val phone = Device(this, "phone", api).also { it.start() }
        val desktop = Device(this, "desktop", api).also { it.start() }
        val base = PricePoint(today, Money.of(9.99))

        phone.repository.upsertSubscription(subscription(prices = listOf(base)))
        testScheduler.runCurrent()
        phone.engine.syncNow()
        desktop.engine.syncNow()

        val phoneOnly = PricePoint(LocalDate(2026, 10, 1), Money.of(11.99))
        phone.repository.upsertSubscription(subscription(prices = listOf(base, phoneOnly)))
        val desktopOnly = PricePoint(LocalDate(2026, 11, 1), Money.of(13.99))
        desktop.repository.upsertSubscription(subscription("Renamed", listOf(base, desktopOnly)))
        testScheduler.runCurrent()

        phone.engine.syncNow()
        desktop.engine.syncNow()
        phone.engine.syncNow()
        desktop.engine.syncNow()

        val expected = listOf(base, phoneOnly, desktopOnly).map { it.effectiveFrom }
        assertEquals(expected, phone.data.subscriptions.single().priceHistory.map { it.effectiveFrom })
        assertEquals(expected, desktop.data.subscriptions.single().priceHistory.map { it.effectiveFrom })
    }

    @Test
    fun `a truncated feed is followed to the end in one call`() = runTest {
        val api = FakeApi().apply { pageSize = 1 }
        val phone = Device(this, "phone", api).also { it.start() }
        phone.repository.upsertSubscription(subscription(prices = listOf(PricePoint(today, Money.of(9.99)))))
        phone.repository.upsertPaymentMethod(
            lux.uno.anima.finance.model.PaymentMethod(id = "pm-1", name = "Visa"),
        )
        testScheduler.runCurrent()
        phone.engine.syncNow()

        val desktop = Device(this, "desktop", api).also { it.start() }
        assertTrue(desktop.engine.syncNow() is SyncOutcome.Synced)

        assertEquals(1, desktop.data.subscriptions.size)
        assertEquals(1, desktop.data.paymentMethods.size)
    }

    // region failures must never cost data

    @Test
    fun `being offline changes nothing`() = runTest {
        val api = FakeApi().apply { answer = { ApiResult.Unreachable(RuntimeException("no network")) } }
        val phone = Device(this, "phone", api).also { it.start() }
        phone.repository.upsertSubscription(subscription(prices = listOf(PricePoint(today, Money.of(9.99)))))
        testScheduler.runCurrent()
        val before = phone.data

        assertEquals(SyncOutcome.Offline, phone.engine.syncNow())

        assertEquals(before, phone.data)
        assertEquals(SyncStatus.Offline, phone.engine.status.value)
        assertEquals(0L, assertNotNull(phone.state.load()).cursor, "nothing may be marked done")
    }

    @Test
    fun `a rejected session drops the token and keeps everything else`() = runTest {
        val api = FakeApi().apply { answer = { ApiResult.Unauthorized } }
        val phone = Device(this, "phone", api).also { it.start() }
        phone.repository.upsertSubscription(subscription(prices = listOf(PricePoint(today, Money.of(9.99)))))
        testScheduler.runCurrent()

        assertEquals(SyncOutcome.SignedOut, phone.engine.syncNow())

        assertNull(phone.secrets.get(SESSION_TOKEN_KEY), "the dead token has to go")
        assertEquals(1, phone.data.subscriptions.size, "the data has not been signed out of")
        assertEquals(SyncStatus.SignedOut, phone.engine.status.value)
    }

    @Test
    fun `no token means signed out rather than an error`() = runTest {
        val phone = Device(this, "phone", FakeApi()).also { it.start() }
        phone.secrets.remove(SESSION_TOKEN_KEY)

        assertEquals(SyncOutcome.SignedOut, phone.engine.syncNow())
    }

    @Test
    fun `an environment with no server does not try`() = runTest {
        val phone = Device(this, "phone", api = null).also { it.start() }

        assertEquals(SyncOutcome.NotConfigured, phone.engine.syncNow())
        assertEquals(SyncStatus.Idle, phone.engine.status.value, "a sandbox is not a failure")
    }

    @Test
    fun `a refusal is reported rather than retried forever`() = runTest {
        val api = FakeApi().apply { answer = { ApiResult.Refused(500, "boom") } }
        val phone = Device(this, "phone", api).also { it.start() }

        val outcome = phone.engine.syncNow()

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(SyncStatus.Failed, phone.engine.status.value)
        assertEquals(1, api.requests, "one attempt, not a loop")
    }

    // endregion

    // region a new device must not overwrite what is already there

    @Test
    fun `a fresh device does not push its idea of the defaults`() = runTest {
        // Install on a second phone and it seeds the same eight categories under the same
        // ids. If those went up stamped now, they would land on top of whatever the first
        // phone had renamed them to — everywhere.
        val api = FakeApi()
        val phone = Device(this, "phone", api).also { it.start() }
        phone.repository.upsertCategory(Category("cat-software", "Dev tools"))
        testScheduler.runCurrent()
        phone.engine.syncNow()

        val newPhone = Device(this, "new-phone", api).also { it.start() }
        newPhone.engine.syncNow()

        assertEquals(
            "Dev tools",
            newPhone.data.categories.single { it.id == "cat-software" }.label,
            "the rename has to survive a new device joining",
        )
    }

    @Test
    fun `a fresh device does not push its default currency either`() = runTest {
        val api = FakeApi()
        val phone = Device(this, "phone", api).also { it.start() }
        phone.repository.setPreferredCurrency("EUR")
        testScheduler.runCurrent()
        phone.engine.syncNow()

        val newPhone = Device(this, "new-phone", api).also { it.start() }
        newPhone.engine.syncNow()

        assertEquals("EUR", newPhone.data.settings.preferredCurrency)
    }

    @Test
    fun `editing a seeded record makes it the user's and sends it`() = runTest {
        val api = FakeApi()
        val phone = Device(this, "phone", api).also { it.start() }
        val other = Device(this, "other", api).also { it.start() }

        phone.repository.upsertCategory(Category("cat-music", "Listening"))
        testScheduler.runCurrent()
        phone.engine.syncNow()
        other.engine.syncNow()

        assertEquals("Listening", other.data.categories.single { it.id == "cat-music" }.label)
    }

    @Test
    fun `deleting a seeded record still reaches the other device`() = runTest {
        val api = FakeApi()
        val phone = Device(this, "phone", api).also { it.start() }
        val other = Device(this, "other", api).also { it.start() }

        phone.repository.deleteCategory("cat-home")
        testScheduler.runCurrent()
        phone.engine.syncNow()
        other.engine.syncNow()

        assertTrue(other.data.categories.none { it.id == "cat-home" })
    }

    // endregion

    @Test
    fun `a client can be built without naming an engine`() {
        // There is no engine in commonMain: each target picks up the one on its own
        // classpath. If that wiring is wrong, this is where it shows.
        animaHttpClient().close()
    }

    @Test
    fun `a burst of edits costs one round trip`() = runTest {
        val api = FakeApi()
        val phone = Device(this, "phone", api).also { it.start() }

        repeat(5) {
            phone.repository.upsertSubscription(
                subscription("Netflix$it", listOf(PricePoint(today, Money.of(9.99)))),
            )
            phone.engine.requestSync(this)
        }
        testScheduler.advanceUntilIdle()

        assertEquals(1, api.requests, "the debounce has to replace the pending request")
    }
}
