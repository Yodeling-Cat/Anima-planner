package lux.uno.anima.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import lux.uno.anima.platform.SESSION_TOKEN_KEY
import lux.uno.anima.platform.SecretStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps a device up to date with edits made on the *other* ones.
 *
 * Pushing has never needed this — every local edit already asks [SyncEngine] for a sync.
 * The gap it closes is the other direction: without something telling a device its feed
 * moved, a phone left open on the dashboard shows last week's data until somebody touches
 * it. Polling would answer that too, at the cost of waking every device that has nothing
 * to collect, which is most of them most of the time.
 *
 * Nothing here decides what changed. The server says how far its feed has got, this
 * compares that with what the device holds, and the ordinary `/sync` round trip does the
 * rest — so the live channel cannot become a second way for data to arrive.
 */
class LiveSync(
    private val engine: SyncEngine,
    private val events: SyncEvents?,
    private val secrets: SecretStore,
    private val tokenKey: String = SESSION_TOKEN_KEY,
    /** First wait after a dropped connection. Doubles up to [maxBackoff]. */
    private val backoff: Duration = 3.seconds,
    private val maxBackoff: Duration = 2.minutes,
) {
    private val _connected = MutableStateFlow(false)

    /** Whether the live channel is open, for anything that wants to show it. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Listens until cancelled. Call it from a scope that ends when the app does, or when
     * the account signs out.
     *
     * Returns rather than throws when there is nothing to listen to — no server in this
     * environment, or no session — because both are ordinary states the app runs in, and
     * the caller restarts this when either changes.
     */
    suspend fun run() {
        if (events == null) return
        var wait = backoff

        while (currentCoroutineContext().isActive) {
            val token = secrets.get(tokenKey) ?: return

            var sawAnything = false
            var signedOut = false

            // takeWhile rather than a flag checked inside collect: a false answer ends the
            // collection there and then, so a refused session stops listening instead of
            // reading every remaining event to find out it is still refused.
            events.changes(token)
                .takeWhile { event ->
                    sawAnything = true
                    signedOut = handle(event) == SyncOutcome.SignedOut
                    !signedOut
                }
                .collect { }

            _connected.value = false
            // The session is gone rather than the network: stop instead of reconnecting
            // with a token the server has already refused.
            if (signedOut || secrets.get(tokenKey) == null) return

            // A connection that worked starts the backoff over. Without this, a server
            // restarted once an hour would have every device waiting two minutes to notice.
            if (sawAnything) wait = backoff

            delay(wait)
            wait = minOf(wait * 2, maxBackoff)
        }
    }

    /** Null when the event asked for nothing; otherwise how the sync it asked for went. */
    private suspend fun handle(event: SyncEvent): SyncOutcome? = when (event) {
        // Reaching the server again is itself news: this device was away for however long
        // the connection was down, so it starts by catching up.
        SyncEvent.Connected -> {
            _connected.value = true
            engine.syncNow()
        }

        // The server's feed is ahead of what this device holds. A cursor at or below its
        // own is this device hearing about its own push, which it has already applied —
        // syncing on that would make every edit cost two round trips on the device that
        // made it.
        is SyncEvent.Changed ->
            if (event.cursor > (engine.syncState.value?.cursor ?: 0L)) engine.syncNow() else null
    }
}
