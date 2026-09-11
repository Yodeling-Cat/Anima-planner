package lux.uno.anima.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.FinanceRepository
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.store.InMemoryFinanceStore
import lux.uno.anima.platform.SESSION_TOKEN_KEY
import lux.uno.anima.platform.SecretProtection
import lux.uno.anima.platform.SecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LiveSyncTest {

    private val today = LocalDate(2026, 9, 11)

    private class Secrets(private val values: MutableMap<String, String>) : SecretStore {
        override val protection = SecretProtection.FILE_PERMISSIONS
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    /** Counts round trips, so a test can tell "was told to sync" from "synced anyway". */
    private class CountingApi : SyncApi {
        var requests = 0
        val held = mutableMapOf<RecordKey, SyncRecord>()
        var nextSeq = 1L
        var unauthorized = false

        override suspend fun sync(token: String, request: SyncRequest): ApiResult<SyncResponse> {
            requests++
            if (unauthorized) return ApiResult.Unauthorized
            for (incoming in request.changes) {
                val existing = held[incoming.key]
                if (existing != null && existing.hlc >= incoming.hlc) continue
                held[incoming.key] = incoming.copy(seq = nextSeq++)
            }
            val feed = held.values.filter { it.seq > request.since }.sortedBy { it.seq }
            return ApiResult.Ok(
                SyncResponse(
                    cursor = maxOf(request.since, feed.maxOfOrNull { it.seq } ?: 0L),
                    changes = feed,
                ),
            )
        }

        override suspend fun register(email: String, password: String, joinPassword: String?) =
            ApiResult.Ok(Session("t", 0))
        override suspend fun logIn(email: String, password: String) = ApiResult.Ok(Session("t", 0))
        override suspend fun logOut(token: String) = ApiResult.Ok(Unit)
        override suspend fun missingBlobs(token: String, hashes: List<String>) =
            ApiResult.Ok(emptyList<String>())
        override suspend fun putBlob(token: String, hash: String, bytes: ByteArray) = ApiResult.Ok(Unit)
        override suspend fun getBlob(token: String, hash: String): ApiResult<ByteArray> =
            ApiResult.Refused(404, "no such blob")
    }

    /** A channel dressed as the live endpoint, so a test can post an event by hand. */
    private class FakeEvents : SyncEvents {
        val posted = Channel<SyncEvent>(Channel.UNLIMITED)
        var connections = 0
        override fun changes(token: String): Flow<SyncEvent> {
            connections++
            return posted.consumeAsFlow()
        }
    }

    private class Device(scope: TestScope, node: String, val api: CountingApi) {
        val secrets = Secrets(mutableMapOf(SESSION_TOKEN_KEY to "a-token"))
        val repository = FinanceRepository(InMemoryFinanceStore(), scope, clock = HybridClock(node))
        val engine = SyncEngine(
            repository,
            InMemorySyncStateStore(SyncState(nodeId = node)),
            secrets,
            api,
        )
        val events = FakeEvents()
        val live = LiveSync(engine, events, secrets)

        suspend fun start() = repository.load()
    }

    private fun subscription(name: String) =
        Subscription(
            id = "sub-1",
            name = name,
            anchorDate = today,
            priceHistory = listOf(PricePoint(today, Money.of(9.99))),
        )

    @Test
    fun `an edit on another device arrives without anyone touching this one`() = runTest {
        val api = CountingApi()
        val phone = Device(this, "phone", api).also { it.start() }
        val desktop = Device(this, "desktop", api).also { it.start() }

        val listening = launch { desktop.live.run() }
        desktop.events.posted.send(SyncEvent.Connected)
        testScheduler.runCurrent()

        // The phone's edit lands on the server, which then says how far its feed has got.
        phone.repository.upsertSubscription(subscription("Netflix"))
        testScheduler.runCurrent()
        phone.engine.syncNow()
        desktop.events.posted.send(SyncEvent.Changed(cursor = api.nextSeq - 1))
        testScheduler.runCurrent()

        assertEquals("Netflix", desktop.repository.data.value.subscriptions.single().name)
        listening.cancel()
    }

    @Test
    fun `hearing about its own push costs nothing`() = runTest {
        val api = CountingApi()
        val phone = Device(this, "phone", api).also { it.start() }
        phone.repository.upsertSubscription(subscription("Netflix"))
        testScheduler.runCurrent()

        val listening = launch { phone.live.run() }
        phone.engine.syncNow()
        val afterPush = api.requests

        phone.events.posted.send(SyncEvent.Changed(cursor = phone.engine.syncState.value!!.cursor))
        testScheduler.runCurrent()

        assertEquals(afterPush, api.requests)
        listening.cancel()
    }

    @Test
    fun `reaching the server again catches up on its own`() = runTest {
        val api = CountingApi()
        val phone = Device(this, "phone", api).also { it.start() }

        val listening = launch { phone.live.run() }
        phone.events.posted.send(SyncEvent.Connected)
        testScheduler.runCurrent()

        assertEquals(1, api.requests)
        listening.cancel()
    }

    @Test
    fun `a dropped connection is opened again`() = runTest {
        val api = CountingApi()
        val phone = Device(this, "phone", api).also { it.start() }

        val listening = launch { phone.live.run() }
        testScheduler.runCurrent()
        phone.events.posted.close()
        testScheduler.advanceTimeBy(10.seconds)
        testScheduler.runCurrent()

        assertTrue(phone.events.connections >= 2)
        listening.cancel()
    }

    @Test
    fun `a refused session stops the listener rather than reconnecting`() = runTest {
        val api = CountingApi().also { it.unauthorized = true }
        val phone = Device(this, "phone", api).also { it.start() }

        val listening = launch { phone.live.run() }
        phone.events.posted.send(SyncEvent.Connected)
        testScheduler.advanceTimeBy(10.seconds)
        testScheduler.runCurrent()

        assertNull(phone.secrets.get(SESSION_TOKEN_KEY))
        assertEquals(1, phone.events.connections)
        assertTrue(listening.isCompleted)
    }

    @Test
    fun `an environment with no server listens to nothing`() = runTest {
        val api = CountingApi()
        val phone = Device(this, "phone", api).also { it.start() }
        val live = LiveSync(phone.engine, events = null, secrets = phone.secrets)

        live.run()

        assertEquals(0, api.requests)
    }

    @Test
    fun `nothing is listened to without a session`() = runTest {
        val api = CountingApi()
        val phone = Device(this, "phone", api).also { it.start() }
        phone.secrets.remove(SESSION_TOKEN_KEY)

        phone.live.run()

        assertEquals(0, phone.events.connections)
    }
}
