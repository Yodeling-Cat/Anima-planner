package lux.uno.anima

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.restamped
import lux.uno.anima.finance.sync.applying
import lux.uno.anima.finance.sync.recordsToPush
import lux.uno.anima.server.SessionBody
import lux.uno.anima.sync.ApiResult
import lux.uno.anima.sync.BlobStore
import lux.uno.anima.sync.BlobSync
import lux.uno.anima.sync.HybridClock
import lux.uno.anima.sync.KtorSyncApi
import lux.uno.anima.sync.SyncRequest
import lux.uno.anima.sync.SyncState
import lux.uno.anima.sync.SyncResponse
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two halves meeting: the client's mapping, the wire protocol, and the real server,
 * with nothing stubbed but the network transport.
 *
 * Everything below is one account. Anima is single-user, so every conflict here is one
 * person who edited in two places — which is exactly the case last-write-wins is for.
 */
class SyncRoundTripTest {

    private val directory = Files.createTempDirectory("anima-roundtrip")
    private val config = ServerConfig(
        port = 0,
        host = "localhost",
        dataDirectory = directory,
        registrationOpen = true,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val today = LocalDate(2026, 9, 11)

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    /** One device: its own dataset, its own clock, and the sync state that goes with them. */
    private class Device(node: String, var wallMillis: Long = 1_757_000_000_000) {
        val clock = HybridClock(node, wallClockMillis = { wallMillis })
        var data = FinanceData()
        var state = SyncState(nodeId = node)

        /** Applies an edit the way the repository would, so it gets stamped. */
        fun edit(change: (FinanceData) -> FinanceData) {
            val previous = data
            data = change(previous).restamped(previous, clock)
        }
    }

    private fun subscription(
        name: String = "Netflix",
        prices: List<PricePoint> = listOf(PricePoint(LocalDate(2026, 9, 11), Money.of(9.99))),
    ) = Subscription(id = "sub-1", name = name, anchorDate = LocalDate(2026, 9, 11), priceHistory = prices)

    @Test
    fun `a record made on one device arrives on another`() = roundTrip { token ->
        val phone = Device("phone")
        val desktop = Device("desktop")

        phone.edit { it.copy(subscriptions = listOf(subscription())) }
        sync(token, phone)

        sync(token, desktop)

        assertEquals("Netflix", desktop.data.subscriptions.single().name)
        assertEquals(phone.data.subscriptions.single(), desktop.data.subscriptions.single())
    }

    @Test
    fun `the later edit wins and the loser is corrected`() = roundTrip { token ->
        val phone = Device("phone")
        val desktop = Device("desktop")

        phone.edit { it.copy(subscriptions = listOf(subscription())) }
        sync(token, phone)
        sync(token, desktop)

        // Both go offline and edit the same record. The desktop's edit happens later.
        phone.wallMillis += 1_000
        phone.edit { data -> data.copy(subscriptions = listOf(subscription(name = "edited on the plane"))) }

        desktop.wallMillis += 5_000
        desktop.edit { data -> data.copy(subscriptions = listOf(subscription(name = "edited at the desk"))) }

        settle(token, phone, desktop)

        assertEquals("edited at the desk", desktop.data.subscriptions.single().name)
        assertEquals(
            "edited at the desk",
            phone.data.subscriptions.single().name,
            "the device that lost has to be told, not left holding its own version",
        )
    }

    @Test
    fun `a delete on one device removes it from the other`() = roundTrip { token ->
        val phone = Device("phone")
        val desktop = Device("desktop")

        phone.edit { it.copy(subscriptions = listOf(subscription())) }
        sync(token, phone)
        sync(token, desktop)
        assertEquals(1, desktop.data.subscriptions.size)

        phone.wallMillis += 1_000
        phone.edit { it.copy(subscriptions = emptyList()) }
        sync(token, phone)
        sync(token, desktop)

        assertTrue(desktop.data.subscriptions.isEmpty())
    }

    @Test
    fun `a price recorded on each device survives on both`() = roundTrip { token ->
        // The scenario the price history union exists for. Both devices are offline, each
        // records a different price change, and the desktop also renames — so the desktop's
        // record wins outright and would otherwise take its history with it.
        val phone = Device("phone")
        val desktop = Device("desktop")
        val base = PricePoint(today, Money.of(9.99))

        phone.edit { it.copy(subscriptions = listOf(subscription())) }
        sync(token, phone)
        sync(token, desktop)

        val phoneOnly = PricePoint(LocalDate(2026, 10, 1), Money.of(11.99))
        phone.wallMillis += 1_000
        phone.edit { it.copy(subscriptions = listOf(subscription(prices = listOf(base, phoneOnly)))) }

        val desktopOnly = PricePoint(LocalDate(2026, 11, 1), Money.of(13.99))
        desktop.wallMillis += 5_000
        desktop.edit {
            it.copy(subscriptions = listOf(subscription("Netflix Premium", listOf(base, desktopOnly))))
        }

        // Settling takes more than one exchange here, and that is inherent rather than a
        // shortcoming. The desktop's edit wins on the server, so the phone only meets it on
        // the way back — unions it there, and needs another turn to send the union on.
        settle(token, phone, desktop)

        val expected = listOf(base.effectiveFrom, phoneOnly.effectiveFrom, desktopOnly.effectiveFrom)
        assertEquals(
            expected,
            desktop.data.subscriptions.single().priceHistory.map { it.effectiveFrom },
            "the desktop has to end up with the price the phone recorded",
        )
        assertEquals(
            expected,
            phone.data.subscriptions.single().priceHistory.map { it.effectiveFrom },
        )
        assertEquals("Netflix Premium", phone.data.subscriptions.single().name)
    }

    @Test
    fun `an icon added on one device reaches the other`() = roundTrip { token ->
        // Blobs through the real routes, including the server's own hash check.
        val api = KtorSyncApi(client, "")
        val png = "pretend this is a png".encodeToByteArray()
        val hash = png.toByteString().sha256().hex()

        val phone = InMemoryBlobs()
        phone.put(hash, png)
        val desktop = InMemoryBlobs()

        assertTrue(BlobSync(phone) { setOf(hash) }.exchange(api, token) is ApiResult.Ok)
        assertTrue(BlobSync(desktop) { setOf(hash) }.exchange(api, token) is ApiResult.Ok)

        assertEquals(png.toList(), assertNotNull(desktop.load(hash)).toList())
    }

    @Test
    fun `an icon nothing points at is never uploaded`() = roundTrip { token ->
        // The server has no business storing something no record refers to.
        val api = KtorSyncApi(client, "")
        val orphan = "an icon whose subscription was deleted".encodeToByteArray()
        val hash = orphan.toByteString().sha256().hex()
        val phone = InMemoryBlobs().apply { put(hash, orphan) }

        BlobSync(phone) { emptySet() }.exchange(api, token)

        assertEquals(listOf(hash), (api.missingBlobs(token, listOf(hash)) as ApiResult.Ok).value)
    }

    @Test
    fun `an icon the server does not have yet is not an error`() = roundTrip { token ->
        // The device holding it simply has not uploaded it. The next sync will find it.
        val api = KtorSyncApi(client, "")
        val absent = "x".encodeToByteArray().toByteString().sha256().hex()

        val result = BlobSync(InMemoryBlobs()) { setOf(absent) }.exchange(api, token)

        assertTrue(result is ApiResult.Ok, "a blob that is not there yet must not fail the sync")
    }

    @Test
    fun `syncing twice with nothing to say changes nothing`() = roundTrip { token ->
        val phone = Device("phone")
        phone.edit { it.copy(subscriptions = listOf(subscription())) }
        sync(token, phone)

        val settled = phone.data
        val cursor = phone.state.cursor
        sync(token, phone)

        assertEquals(settled, phone.data)
        assertEquals(cursor, phone.state.cursor, "an idle sync must not advance the cursor")
        assertTrue(
            phone.data.recordsToPush(phone.state.nodeId, phone.state.lastPushed).isEmpty(),
            "a device that has pushed everything must stop offering it again",
        )
    }

    @Test
    fun `a device that never pushed still catches up from nothing`() = roundTrip { token ->
        val phone = Device("phone")
        phone.edit { data ->
            data.copy(subscriptions = listOf(subscription()))
                .copy(settings = data.settings.copy(preferredCurrency = "EUR"))
        }
        sync(token, phone)

        val fresh = Device("fresh")
        sync(token, fresh)

        assertEquals("EUR", fresh.data.settings.preferredCurrency)
        assertEquals(phone.data.subscriptions, fresh.data.subscriptions)
    }

    // region plumbing

    /** One push-and-pull, exactly as the sync engine will do it. */
    private suspend fun ApplicationTestBuilder.sync(token: String, device: Device) {
        val outgoing = device.data.recordsToPush(device.state.nodeId, device.state.lastPushed)
        val request = SyncRequest(since = device.state.cursor, changes = outgoing)

        // The real client class, against the real routes. Only the socket is stood in for,
        // so the serialisation on both sides is the shipping one.
        val result = KtorSyncApi(client, "").sync(token, request)
        val body = (result as? ApiResult.Ok)?.value ?: error("sync failed: $result")
        assertTrue(body.rejected.isEmpty(), "server refused records: ${body.rejected}")

        device.data = device.data.applying(body.changes, device.clock)
        device.state = device.state.copy(
            cursor = body.cursor,
            // Moved only over what was actually sent. A record stamped while applying the
            // response — a merged price history — is above this and still goes out next time.
            lastPushed = outgoing.maxOfOrNull { it.hlc } ?: device.state.lastPushed,
            lastHlc = device.clock.last,
        )
    }

    /**
     * Syncs every device in turn until nothing changes any more.
     *
     * How many exchanges convergence takes depends on who reached the server first, so
     * pinning a count would test the order of this test rather than the behaviour. What
     * matters is that it settles, and that it settles on the same thing everywhere.
     */
    private suspend fun ApplicationTestBuilder.settle(token: String, vararg devices: Device) {
        // The cursor counts as state, not just the data. A pass where both devices pushed
        // and neither had anything to take back changes nothing locally while moving the
        // server on — stopping there would call a half-finished exchange "converged".
        fun snapshot() = devices.map { it.data to it.state }

        repeat(MAX_EXCHANGES) {
            val before = snapshot()
            devices.forEach { sync(token, it) }
            if (snapshot() == before) return
        }
        error("still changing after $MAX_EXCHANGES exchanges, which means it is not converging")
    }

    private fun roundTrip(body: suspend ApplicationTestBuilder.(token: String) -> Unit) =
        testApplication {
            application { module(config) }
            val registered = client.post("/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"sync@example.com","password":"correct horse battery"}""")
            }
            assertEquals(HttpStatusCode.Created, registered.status, registered.bodyAsText())
            body(json.decodeFromString(SessionBody.serializer(), registered.bodyAsText()).token)
        }

    /** A blob store with no disk behind it, for driving [BlobSync]. */
    private class InMemoryBlobs : BlobStore {
        private val held = mutableMapOf<String, ByteArray>()
        override suspend fun stored(): Set<String> = held.keys.toSet()
        override suspend fun load(hash: String): ByteArray? = held[hash]
        override suspend fun put(hash: String, bytes: ByteArray): Boolean {
            held[hash] = bytes
            return true
        }
    }

    private companion object {
        /** Generous: two devices settle in three, and anything beyond that is a loop. */
        const val MAX_EXCHANGES = 6
    }

    // endregion
}
