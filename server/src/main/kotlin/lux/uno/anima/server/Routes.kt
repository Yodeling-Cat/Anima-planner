package lux.uno.anima.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import lux.uno.anima.Admission
import lux.uno.anima.Limits
import lux.uno.anima.ServerConfig
import lux.uno.anima.server.auth.Accounts
import lux.uno.anima.server.auth.IssuedSession
import lux.uno.anima.server.auth.Principal
import lux.uno.anima.server.auth.RegisterResult
import lux.uno.anima.server.blobs.BlobStore
import lux.uno.anima.server.sync.SyncNotifier
import lux.uno.anima.server.sync.SyncStore
import lux.uno.anima.sync.SyncRequest

@Serializable
data class CredentialsBody(
    val email: String,
    val password: String,
    /** The server's shared join secret, not this account's password. Absent where none is set. */
    val joinPassword: String? = null,
)

@Serializable
data class SessionBody(val token: String, val expiresAtMillis: Long)

/**
 * [error] is for a person to read; [code] is for the client to branch on.
 *
 * The two are separate because "registration is closed" and "that join password is wrong"
 * need different things said in the app, and matching on prose would break the moment the
 * prose improved.
 */
@Serializable
data class ErrorBody(val error: String, val code: String? = null)

@Serializable
data class HashesBody(val hashes: List<String> = emptyList())

@Serializable
data class MissingBody(val missing: List<String>)

/**
 * Every route the server has.
 *
 * There are deliberately no others. Nothing here reports a total, a renewal or a count,
 * because every one of those is computed on the device from data it already holds — and an
 * endpoint that answered them would need the server to understand what it is storing.
 */
fun Route.animaRoutes(
    config: ServerConfig,
    accounts: Accounts,
    records: SyncStore,
    blobs: BlobStore,
    notifier: SyncNotifier = SyncNotifier(),
) {
    get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }

    rateLimit(RateLimitName("auth")) {
        post("/auth/register") {
            val body = call.receive<CredentialsBody>()
            // Inside the auth rate limiter on purpose: a join password is typed by a person
            // and so is short enough to guess, which makes the ten-a-minute ceiling part of
            // what protects it rather than an incidental neighbour.
            when (config.admits(body.joinPassword)) {
                Admission.ALLOWED -> Unit

                Admission.WRONG_JOIN_PASSWORD -> {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorBody("that join password is wrong", "join_password_wrong"),
                    )
                    return@post
                }

                Admission.CLOSED -> {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorBody("this server is not taking new accounts", "registration_closed"),
                    )
                    return@post
                }
            }
            when (val result = accounts.register(body.email, body.password)) {
                is RegisterResult.Success ->
                    call.respond(HttpStatusCode.Created, result.session.toBody())

                is RegisterResult.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorBody(result.reason))

                // Deliberately the same answer as a malformed address would get, so this
                // endpoint cannot be walked to find out who has an account here.
                RegisterResult.EmailTaken ->
                    call.respond(HttpStatusCode.BadRequest, ErrorBody("that address cannot be used"))
            }
        }

        post("/auth/login") {
            val body = call.receive<CredentialsBody>()
            val session = accounts.signIn(body.email, body.password)
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorBody("email or password is wrong"))
            } else {
                call.respond(HttpStatusCode.OK, session.toBody())
            }
        }
    }

    authenticate("session") {
        post("/auth/logout") {
            call.bearerToken()?.let { accounts.signOut(it) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/sync") {
            val userId = call.userId()
            if (call.request.contentLength()?.let { it > Limits.MAX_SYNC_BODY_BYTES } == true) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorBody("sync body too large"))
                return@post
            }
            val request = call.receive<SyncRequest>()
            if (request.changes.size > Limits.MAX_CHANGES_PER_REQUEST) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorBody("at most ${Limits.MAX_CHANGES_PER_REQUEST} changes per request"),
                )
                return@post
            }
            val response = records.sync(userId, request.copy(since = request.since.coerceAtLeast(0)))
            call.respond(response)
            // After the response, and never in place of it: a device's own sync must not
            // depend on whether anyone else was listening. Every device of this user hears
            // it, the pusher included — it will recognise its own cursor and stay put.
            notifier.notify(userId, response.cursor)
        }

        /**
         * Says when this user's feed moved, so the other devices do not have to keep asking.
         *
         * The event carries the new cursor and nothing else. A device compares it with the
         * one it holds and calls `/sync` if it is behind, which means this endpoint never
         * has to send a record — and so never has to be trusted with one.
         */
        sse("/sync/events") {
            val userId = call.userId()
            val channel = notifier.subscribe(userId)
            try {
                // The connection itself is the first signal. A device reaching the server
                // again after being away is behind by definition, and waiting for the next
                // edit to tell it so would leave it stale for as long as nobody typed.
                send(ServerSentEvent(event = CONNECTED_EVENT, data = "ok"))
                while (true) {
                    val cursor = withTimeoutOrNull(HEARTBEAT_MILLIS) { channel.receive() }
                    if (cursor == null) {
                        // Nothing to say, said out loud. A proxy that sees no bytes for a
                        // few minutes closes the connection, and the device would not find
                        // out until the next edit failed to arrive.
                        send(ServerSentEvent(comments = "keep-alive"))
                    } else {
                        send(ServerSentEvent(event = CHANGED_EVENT, data = cursor.toString()))
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // This connection was dropped to make room under the per-user cap. Ending
                // quietly is the whole response: the device reconnects and takes a new slot.
            } finally {
                notifier.unsubscribe(userId, channel)
            }
        }

        post("/blobs/missing") {
            val body = call.receive<HashesBody>()
            call.respond(MissingBody(blobs.missing(call.userId(), body.hashes)))
        }

        put("/blobs/{hash}") {
            val hash = call.parameters["hash"].orEmpty()
            val bytes = call.receiveBounded(Limits.MAX_BLOB_BYTES)
            if (bytes == null) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorBody("blob too large"))
                return@put
            }
            if (!blobs.put(call.userId(), hash, bytes)) {
                // Either the name is not a SHA-256 or the bytes do not hash to it. Both mean
                // the upload cannot be trusted to stay the thing it claims to be.
                call.respond(HttpStatusCode.BadRequest, ErrorBody("bytes do not match the hash"))
                return@put
            }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/blobs/{hash}") {
            val bytes = blobs.get(call.userId(), call.parameters["hash"].orEmpty())
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("no such blob"))
            } else {
                call.respondBytes(bytes, ContentType.Application.OctetStream)
            }
        }
    }
}

/** Named on both sides: `core`'s KtorSyncEvents branches on exactly these. */
private const val CONNECTED_EVENT = "connected"
private const val CHANGED_EVENT = "changed"

/**
 * Well under the minute most proxies and mobile networks idle a connection out at, and far
 * enough above it to cost nothing: one comment every 25 seconds per connected device.
 */
private const val HEARTBEAT_MILLIS = 25_000L

private fun IssuedSession.toBody() =
    SessionBody(token, expiresAtMillis)

private fun ApplicationCall.userId(): String =
    principal<Principal>()?.userId ?: error("route is inside authenticate() but has no principal")

private fun ApplicationCall.bearerToken(): String? =
    request.headers["Authorization"]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)

/**
 * Reads at most [max] bytes, answering null when there are more.
 *
 * Both halves matter: a declared length is checked before anything is read, and the read
 * itself is still bounded, because a client is free to lie about the length or omit it.
 */
private suspend fun ApplicationCall.receiveBounded(max: Int): ByteArray? {
    if (request.contentLength()?.let { it > max } == true) return null
    val stream = receiveStream()
    return withContext(Dispatchers.IO) {
        val bytes = stream.readNBytes(max + 1)
        if (bytes.size > max) null else bytes
    }
}
