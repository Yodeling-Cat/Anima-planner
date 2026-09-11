package lux.uno.anima

import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import lux.uno.anima.server.SessionBody
import lux.uno.anima.sync.HybridClock
import lux.uno.anima.sync.KtorSyncApi
import lux.uno.anima.sync.KtorSyncEvents
import lux.uno.anima.sync.RecordKind
import lux.uno.anima.sync.SyncEvent
import lux.uno.anima.sync.SyncRecord
import lux.uno.anima.sync.SyncRequest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live channel end to end: a push on one connection, heard on another.
 *
 * What it is really protecting is the promise that a device does not have to be touched to
 * be current. Everything below drives the real routes through the real client, because the
 * parts most likely to break — the event names and who is allowed to hear them — are the
 * parts a fake on either side would agree with itself about.
 */
class LiveEventsTest {

    private val directory = Files.createTempDirectory("anima-live")
    private val config = ServerConfig(
        port = 0,
        host = "localhost",
        dataDirectory = directory,
        publicInstance = true,
        joinPassword = null,
        backupEnabled = false,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val listeners = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun cleanUp() {
        listeners.cancel()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a push is announced to a device that is only listening`() = testApplication {
        application { module(config) }
        val token = register("live@example.com")
        val heard = listen(token)

        assertEquals(SyncEvent.Connected, next(heard))

        val api = KtorSyncApi(client, "")
        api.sync(token, SyncRequest(since = 0, changes = listOf(record("sub-1"))))

        val event = next(heard)
        assertTrue(event is SyncEvent.Changed, "expected a change announcement but got $event")
        assertEquals(1L, event.cursor, "the announcement carries how far the feed has got")
    }

    @Test
    fun `nothing is announced across accounts`() = testApplication {
        application { module(config) }
        val mine = register("mine@example.com")
        val theirs = register("theirs@example.com")
        val heard = listen(mine)
        assertEquals(SyncEvent.Connected, next(heard))

        KtorSyncApi(client, "").sync(theirs, SyncRequest(since = 0, changes = listOf(record("sub-1"))))

        assertNull(
            withTimeoutOrNull(1_000) { heard.receive() },
            "one account's edit must not tell another account's device anything at all",
        )
    }

    @Test
    fun `the channel refuses a caller with no session`() = testApplication {
        application { module(config) }
        val heard = listen("not-a-token")

        // The flow ends rather than carrying an error: reconnecting is the client's
        // decision, and the token is only ever dropped by a refused `/sync`.
        val outcome = withTimeoutOrNull(2_000) { heard.receiveCatching() }
        assertNotNull(outcome, "the channel should have ended rather than hung")
        assertTrue(outcome.isClosed, "a refused session must not be told anything")
    }

    // region harness

    private suspend fun ApplicationTestBuilder.register(email: String): String {
        val registered = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"correct horse battery"}""")
        }
        assertEquals(HttpStatusCode.Created, registered.status, registered.bodyAsText())
        return json.decodeFromString(SessionBody.serializer(), registered.bodyAsText()).token
    }

    /** Opens a live channel and hands back everything it says, in order. */
    private fun ApplicationTestBuilder.listen(token: String): Channel<SyncEvent> {
        val events = KtorSyncEvents(createClient { install(SSE) }, "")
        val heard = Channel<SyncEvent>(Channel.UNLIMITED)
        listeners.launch {
            events.changes(token).collect { heard.send(it) }
            heard.close()
        }
        return heard
    }

    private suspend fun next(heard: Channel<SyncEvent>): SyncEvent =
        withTimeout(5_000) { heard.receive() }

    private fun record(id: String) = SyncRecord(
        kind = RecordKind.SUBSCRIPTION,
        id = id,
        hlc = HybridClock("phone").tick(),
        payload = """{"id":"$id"}""",
    )

    // endregion
}
