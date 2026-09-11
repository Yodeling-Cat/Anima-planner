package lux.uno.anima.sync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.sse.sse
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** What the server's live channel has to say. Never a record — only that there is one. */
sealed interface SyncEvent {
    /**
     * The channel is open.
     *
     * Worth a sync on its own: this device has just reached a server it could not reach a
     * moment ago, which is the same position it is in at launch.
     */
    data object Connected : SyncEvent

    /** This user's feed has reached [cursor] on the server. */
    data class Changed(val cursor: Long) : SyncEvent
}

/**
 * The server saying when to sync, instead of every device asking.
 *
 * An interface so the engine can be driven without a server, and so the sandbox
 * environment — which has no server at all — is a null rather than a special case.
 */
interface SyncEvents {
    /**
     * One connection per collection, ending when the server closes it or the network goes.
     *
     * Reconnecting is deliberately not done here: how long to wait before trying again is a
     * policy the caller owns, and a flow that silently reconnected forever would be
     * impossible to stop. See [LiveSync].
     */
    fun changes(token: String): Flow<SyncEvent>
}

/**
 * Server-sent events, not a WebSocket.
 *
 * The traffic only ever goes one way — the device has `/sync` for the other direction —
 * and SSE is plain HTTP, which matters for the self-hoster who puts this behind whatever
 * reverse proxy their host came with. A WebSocket would buy an upstream channel nothing
 * needs and cost an upgrade handshake that proxies have to be configured to allow.
 */
class KtorSyncEvents(
    private val client: HttpClient,
    private val baseUrl: String,
) : SyncEvents {

    override fun changes(token: String): Flow<SyncEvent> = channelFlow {
        try {
            client.sse(
                urlString = "$baseUrl/sync/events",
                request = {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    timeout {
                        // A live channel is a request that is never meant to finish, so the
                        // whole-call timeout has to be off or it would cut the connection at
                        // whatever that number was.
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        // Quiet is the normal state here. OkHttp gives up on a silent socket
                        // after ten seconds by default, which is well under the server's
                        // twenty-five second keep-alive — so without this the connection dies
                        // and is rebuilt every ten seconds, forever. Two missed keep-alives
                        // is the signal that it is really gone.
                        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
                    }
                },
            ) {
                incoming.collect { event ->
                    when (event.event) {
                        CONNECTED_EVENT -> send(SyncEvent.Connected)
                        // An unreadable cursor is dropped rather than guessed at. The next
                        // event carries a newer one, and the heartbeat proves the line is
                        // still there in the meantime.
                        CHANGED_EVENT -> event.data?.toLongOrNull()?.let { send(SyncEvent.Changed(it)) }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Every reason the channel will not open or stay open looks the same from here:
            // offline, refused, a 401, a proxy that does not do event streams. None is worth
            // telling apart, because the answer to all of them is to wait and open another —
            // and a session that is really gone is found out by the next `/sync`, which is
            // the one place a token is ever dropped. Ending the flow is the whole report.
        }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 60_000L
        const val CONNECTED_EVENT = "connected"
        const val CHANGED_EVENT = "changed"
    }
}
