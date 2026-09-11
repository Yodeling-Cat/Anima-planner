package lux.uno.anima

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import lux.uno.anima.server.MissingBody
import lux.uno.anima.server.SessionBody
import lux.uno.anima.sync.Hlc
import lux.uno.anima.sync.RecordKind
import lux.uno.anima.sync.SyncRecord
import lux.uno.anima.sync.SyncRequest
import lux.uno.anima.sync.SyncResponse
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationTest {

    private val directory = Files.createTempDirectory("anima-test")
    private val config = ServerConfig(
        port = 0,
        host = "localhost",
        dataDirectory = directory,
        // Public, so these tests do not have to carry a join password around.
        publicInstance = true,
        joinPassword = null,
        // The scheduler would otherwise outlive each test with a loop nothing
        // cancels. Backups have their own test.
        backupEnabled = false,
    )
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `health needs no account`() = server {
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `sync refuses a request with no session`() = server {
        val response = client.post("/sync") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SyncRequest.serializer(), SyncRequest(since = 0)))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a pushed record comes back on the next pull`() = server {
        val token = register("keeper@example.com")

        val pushed = sync(
            token,
            SyncRequest(since = 0, changes = listOf(record("sub-1", Hlc.of(1, 0, "phone")))),
        )
        assertTrue(pushed.rejected.isEmpty(), "unexpected rejections: ${pushed.rejected}")
        assertEquals(1, pushed.changes.size)
        assertTrue(pushed.cursor > 0)

        // A second device starting from nothing sees it.
        val fresh = sync(token, SyncRequest(since = 0))
        assertEquals("sub-1", fresh.changes.single().id)

        // The device that pushed it has nothing left to collect.
        val caughtUp = sync(token, SyncRequest(since = pushed.cursor))
        assertTrue(caughtUp.changes.isEmpty())
    }

    @Test
    fun `the later edit wins and the loser is told`() = server {
        val token = register("conflict@example.com")

        val winner = record("sub-1", Hlc.of(2_000, 0, "desktop"), payload = "edited at the desk")
        sync(token, SyncRequest(since = 0, changes = listOf(winner)))

        // The phone was offline and pushes an older edit of the same record.
        val loser = record("sub-1", Hlc.of(1_000, 0, "phone"), payload = "edited on the plane")
        val response = sync(token, SyncRequest(since = 0, changes = listOf(loser)))

        assertEquals("edited at the desk", response.changes.single().payload)
    }

    @Test
    fun `a losing push is returned even when the cursor is already past it`() = server {
        val token = register("stale@example.com")

        val first = sync(
            token,
            SyncRequest(
                since = 0,
                changes = listOf(record("sub-1", Hlc.of(2_000, 0, "desktop"), payload = "current")),
            ),
        )

        // Caught up, then pushes an edit stamped earlier — nothing new is in the feed, so
        // only the deliberate add-back tells this device its write lost.
        val response = sync(
            token,
            SyncRequest(
                since = first.cursor,
                changes = listOf(record("sub-1", Hlc.of(1_000, 0, "phone"), payload = "stale")),
            ),
        )

        assertEquals("current", response.changes.single().payload)
        assertEquals(first.cursor, response.cursor, "cursor must not move backwards")
    }

    @Test
    fun `a tombstone beats an earlier edit`() = server {
        val token = register("deleter@example.com")

        sync(token, SyncRequest(since = 0, changes = listOf(record("sub-1", Hlc.of(1_000, 0, "phone")))))
        val response = sync(
            token,
            SyncRequest(
                since = 0,
                changes = listOf(
                    SyncRecord(RecordKind.SUBSCRIPTION, "sub-1", Hlc.of(2_000, 0, "desktop"), deleted = true),
                ),
            ),
        )

        val tombstone = response.changes.single()
        assertTrue(tombstone.deleted)
        assertNull(tombstone.payload)
    }

    @Test
    fun `one account never sees another's records`() = server {
        val mine = register("mine@example.com")
        val theirs = register("theirs@example.com")

        sync(mine, SyncRequest(since = 0, changes = listOf(record("sub-1", Hlc.of(1_000, 0, "phone")))))

        assertTrue(sync(theirs, SyncRequest(since = 0)).changes.isEmpty())
    }

    @Test
    fun `a malformed record is rejected without failing the batch`() = server {
        val token = register("mixed@example.com")

        val response = sync(
            token,
            SyncRequest(
                since = 0,
                changes = listOf(
                    record("sub-1", Hlc.of(1_000, 0, "phone")),
                    // A live record with nothing in it is a client bug, not a conflict.
                    SyncRecord(RecordKind.SUBSCRIPTION, "sub-2", Hlc.of(1_000, 0, "phone")),
                ),
            ),
        )

        assertEquals("sub-2", response.rejected.single().id)
        assertEquals("sub-1", response.changes.single().id)
    }

    @Test
    fun `a blob is stored under the hash of its bytes`() = server {
        val token = register("icons@example.com")
        val bytes = "not really a png".toByteArray()
        val hash = sha256(bytes)

        assertEquals(listOf(hash), missing(token, listOf(hash)))

        val upload = client.put("/blobs/$hash") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.NoContent, upload.status)

        assertTrue(missing(token, listOf(hash)).isEmpty())
        assertEquals("not really a png", client.get("/blobs/$hash") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.bodyAsText())
    }

    @Test
    fun `a blob whose bytes do not match its name is refused`() = server {
        val token = register("liar@example.com")
        val response = client.put("/blobs/${sha256("one thing".toByteArray())}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("something else".toByteArray())
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `signing out revokes the token`() = server {
        val token = register("leaver@example.com")
        assertEquals(
            HttpStatusCode.NoContent,
            client.post("/auth/logout") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
        )

        val after = client.post("/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SyncRequest.serializer(), SyncRequest(since = 0)))
        }
        assertEquals(HttpStatusCode.Unauthorized, after.status)
    }

    @Test
    fun `login reports nothing about which addresses exist`() = server {
        register("known@example.com", password = "correct horse battery")

        val wrongPassword = login("known@example.com", "not the password")
        val noSuchAccount = login("unknown@example.com", "not the password")

        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.first)
        assertEquals(HttpStatusCode.Unauthorized, noSuchAccount.first)
        assertEquals(wrongPassword.second, noSuchAccount.second)
    }

    @Test
    fun `a private server with no join password refuses everyone`() = testApplication {
        application { module(config.copy(publicInstance = false, joinPassword = null)) }
        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"nope@example.com","password":"correct horse battery"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("registration_closed"))
    }

    @Test
    fun `a join password lets the right newcomer in and keeps the rest out`() = testApplication {
        application { module(config.copy(publicInstance = false, joinPassword = "open-sesame")) }

        val wrong = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"guess@example.com","password":"correct horse battery","joinPassword":"sesame"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        // The client tells the two apart on this code, never on the sentence beside it.
        assertTrue(wrong.bodyAsText().contains("join_password_wrong"))

        val right = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"invited@example.com","password":"correct horse battery","joinPassword":"open-sesame"}""")
        }
        assertEquals(HttpStatusCode.Created, right.status)
    }

    @Test
    fun `the join password is never echoed back`() = testApplication {
        application { module(config.copy(publicInstance = false, joinPassword = "open-sesame")) }
        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"guess@example.com","password":"correct horse battery","joinPassword":"sesame"}""")
        }
        val body = response.bodyAsText()
        assertFalse(body.contains("open-sesame"), "the expected secret leaked")
        assertFalse(body.contains("sesame\""), "the offered secret was echoed")
    }

    @Test
    fun `a short password is refused`() = server {
        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"short@example.com","password":"tiny"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertFalse(response.bodyAsText().contains("tiny"), "the password must never be echoed")
    }

    // region helpers

    private fun server(body: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config) }
        body()
    }

    private fun record(
        id: String,
        hlc: Hlc,
        payload: String = """{"id":"$id"}""",
    ) = SyncRecord(RecordKind.SUBSCRIPTION, id, hlc, payload = payload)

    private suspend fun ApplicationTestBuilder.register(
        email: String,
        password: String = "correct horse battery",
    ): String {
        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$password"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(SessionBody.serializer(), response.bodyAsText()).token
    }

    private suspend fun ApplicationTestBuilder.login(
        email: String,
        password: String,
    ): Pair<HttpStatusCode, String> {
        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$password"}""")
        }
        return response.status to response.bodyAsText()
    }

    private suspend fun ApplicationTestBuilder.sync(
        token: String,
        request: SyncRequest,
    ): SyncResponse {
        val response = client.post("/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SyncRequest.serializer(), request))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(SyncResponse.serializer(), response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.missing(
        token: String,
        hashes: List<String>,
    ): List<String> {
        val response = client.post("/blobs/missing") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"hashes":[${hashes.joinToString(",") { "\"$it\"" }}]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(MissingBody.serializer(), response.bodyAsText()).missing
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // endregion
}
