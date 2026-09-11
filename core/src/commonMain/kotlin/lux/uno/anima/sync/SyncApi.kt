package lux.uno.anima.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What came back from a call, with the outcomes the caller must actually distinguish.
 *
 * Returned rather than thrown because the difference between "no network" and "rejected"
 * decides whether the app retries quietly or stops and asks for a password — and telling
 * those apart from exception types alone differs on every platform.
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** The token is gone or expired. Never a reason to touch local data. */
    data object Unauthorized : ApiResult<Nothing>

    /** Nothing was reached. Offline, asleep, or the server is down — all the same here. */
    data class Unreachable(val cause: Throwable) : ApiResult<Nothing>

    /**
     * Reached, and refused.
     *
     * [detail] is the server's sentence for a person; [code] is what to branch on, and is
     * null from any older server or any refusal that has nothing to distinguish.
     */
    data class Refused(val status: Int, val detail: String, val code: String? = null) : ApiResult<Nothing>
}

@Serializable
data class Session(val token: String, val expiresAtMillis: Long)

/** The calls the app makes. An interface so the engine can be driven without a server. */
interface SyncApi {
    suspend fun sync(token: String, request: SyncRequest): ApiResult<SyncResponse>
    /**
     * [joinPassword] is the server's shared secret for admitting new accounts, not this
     * account's own password. Null where the server has none or the app was given none.
     */
    suspend fun register(
        email: String,
        password: String,
        joinPassword: String? = null,
    ): ApiResult<Session>
    suspend fun logIn(email: String, password: String): ApiResult<Session>
    suspend fun logOut(token: String): ApiResult<Unit>

    /** Of [hashes], the ones the server does not hold yet. */
    suspend fun missingBlobs(token: String, hashes: List<String>): ApiResult<List<String>>

    suspend fun putBlob(token: String, hash: String, bytes: ByteArray): ApiResult<Unit>
    suspend fun getBlob(token: String, hash: String): ApiResult<ByteArray>
}

@Serializable
private data class Hashes(val hashes: List<String>)

@Serializable
private data class Missing(val missing: List<String> = emptyList())

@Serializable
private data class Credentials(
    val email: String,
    val password: String,
    val joinPassword: String? = null,
)

@Serializable
private data class ServerError(val error: String = "", val code: String? = null)

/**
 * Talks to the server over HTTP.
 *
 * [baseUrl] comes from the active environment, so pointing the app at a laptop is a setting
 * rather than a build. The client is supplied rather than built here so tests can hand it
 * one wired straight to the routes.
 */
class KtorSyncApi(private val client: HttpClient, private val baseUrl: String) : SyncApi {

    /**
     * Bodies are encoded and decoded here rather than by a content-negotiation plugin.
     *
     * The plugin would have to be installed on whatever client is handed in, and forgetting
     * it fails at runtime on the first call rather than at compile time. Doing it here means
     * any client works, including a bare one.
     */
    private val format = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun sync(token: String, request: SyncRequest): ApiResult<SyncResponse> =
        call(SyncResponse.serializer()) {
            client.post("$baseUrl/sync") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(format.encodeToString(SyncRequest.serializer(), request))
            }
        }

    override suspend fun register(
        email: String,
        password: String,
        joinPassword: String?,
    ): ApiResult<Session> =
        call(Session.serializer()) {
            client.post("$baseUrl/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    format.encodeToString(
                        Credentials.serializer(),
                        // Blank and absent mean the same thing to the server, and sending
                        // the empty string would read as an attempt with a wrong secret.
                        Credentials(email, password, joinPassword?.takeIf { it.isNotBlank() }),
                    ),
                )
            }
        }

    override suspend fun logIn(email: String, password: String): ApiResult<Session> =
        call(Session.serializer()) {
            client.post("$baseUrl/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(format.encodeToString(Credentials.serializer(), Credentials(email, password)))
            }
        }

    override suspend fun logOut(token: String): ApiResult<Unit> =
        call(serializer = null) {
            client.post("$baseUrl/auth/logout") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }

    override suspend fun missingBlobs(token: String, hashes: List<String>): ApiResult<List<String>> =
        when (
            val result = call(Missing.serializer()) {
                client.post("$baseUrl/blobs/missing") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(format.encodeToString(Hashes.serializer(), Hashes(hashes)))
                }
            }
        ) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.missing)
            else -> result as ApiResult<Nothing>
        }

    override suspend fun putBlob(token: String, hash: String, bytes: ByteArray): ApiResult<Unit> =
        call(serializer = null) {
            client.put("$baseUrl/blobs/$hash") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }
        }

    /**
     * Read as bytes rather than text: a PNG is not a string, and decoding it as one would
     * quietly replace anything that is not valid UTF-8.
     */
    override suspend fun getBlob(token: String, hash: String): ApiResult<ByteArray> {
        val response = try {
            client.get("$baseUrl/blobs/$hash") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        } catch (failure: Throwable) {
            return ApiResult.Unreachable(failure)
        }
        return when {
            response.status.value == 401 -> ApiResult.Unauthorized
            response.status.value !in 200..299 -> ApiResult.Refused(response.status.value, "")
            else -> runCatching { ApiResult.Ok(response.readRawBytes()) }
                .getOrElse { ApiResult.Refused(response.status.value, "unreadable blob") }
        }
    }

    /**
     * A null [serializer] means the call answers with no body — a 204 — so there is nothing
     * to read. Anything thrown on the way out is unreachability: the request never landed,
     * so there is nothing to interpret and nothing to change locally.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> call(
        serializer: KSerializer<T>?,
        request: suspend () -> HttpResponse,
    ): ApiResult<T> {
        val response = try {
            request()
        } catch (failure: Throwable) {
            return ApiResult.Unreachable(failure)
        }

        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        return when {
            response.status.value == 401 -> ApiResult.Unauthorized
            response.status.value !in 200..299 -> {
                val failure = runCatching {
                    format.decodeFromString(ServerError.serializer(), body)
                }.getOrNull()
                ApiResult.Refused(
                    status = response.status.value,
                    detail = failure?.error ?: body,
                    code = failure?.code,
                )
            }
            serializer == null -> ApiResult.Ok(Unit) as ApiResult<T>
            else -> runCatching { ApiResult.Ok(format.decodeFromString(serializer, body)) }
                .getOrElse { ApiResult.Refused(response.status.value, "unreadable response") }
        }
    }
}

/** A client with the defaults every Anima call expects. Bodies are handled by [KtorSyncApi]. */
fun animaHttpClient(): HttpClient = HttpClient()
