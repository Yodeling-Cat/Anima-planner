package lux.uno.anima.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lux.uno.anima.finance.FinanceRepository
import lux.uno.anima.finance.sync.recordsToPush
import lux.uno.anima.platform.SESSION_TOKEN_KEY
import lux.uno.anima.platform.SecretStore
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/** Where a sync got to. Everything except [Synced] leaves local data exactly as it was. */
sealed interface SyncOutcome {
    data class Synced(val pushed: Int, val received: Int) : SyncOutcome

    /** Nothing was reached. Normal, frequent, and not an error worth showing as one. */
    data object Offline : SyncOutcome

    /** No token, or the server refused it. The data stays; only the session is gone. */
    data object SignedOut : SyncOutcome

    /** This environment has no server, so there is nothing to sync with by design. */
    data object NotConfigured : SyncOutcome

    data class Failed(val detail: String) : SyncOutcome
}

/** What the UI shows. Deliberately separates "cannot reach" from "went wrong". */
enum class SyncStatus { Idle, Syncing, Offline, SignedOut, Failed }

/**
 * Drives one sync at a time: push what this device owes, take what the server has, repeat
 * until neither side has anything left.
 *
 * More than one pass is normal rather than exceptional. A price history merged while
 * applying a response is stamped *after* that pass already sent its changes, so it needs
 * another turn to leave the device — see plan/now.md step 2.
 */
@OptIn(ExperimentalTime::class)
class SyncEngine(
    private val repository: FinanceRepository,
    private val stateStore: SyncStateStore,
    private val secrets: SecretStore,
    private val api: SyncApi?,
    /** Null where there are no attachments to move — every test that does not care. */
    private val blobs: BlobSync? = null,
    private val tokenKey: String = SESSION_TOKEN_KEY,
    private val debounce: Duration = 2.seconds,
) {
    private val _status = MutableStateFlow(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState?>(null)

    /**
     * The sync bookkeeping as it stands, for anything that needs to show it.
     *
     * Mostly so the UI can count what is still waiting to go up: that is
     * `recordsToPush(nodeId, lastPushed)`, and both halves live in here.
     */
    val syncState: StateFlow<SyncState?> = _syncState.asStateFlow()

    val lastSyncedAtMillis: StateFlow<Long?>
        get() = _lastSyncedAtMillis.asStateFlow()
    private val _lastSyncedAtMillis = MutableStateFlow<Long?>(null)

    /** One sync at a time; a second request waits rather than interleaving with the first. */
    private val running = Mutex()

    private var scheduled: Job? = null

    /**
     * Asks for a sync shortly.
     *
     * Debounced because it is called on every mutation, and typing a subscription name is a
     * mutation per keystroke. The pending request is replaced rather than queued, so a burst
     * of edits costs one round trip.
     */
    fun requestSync(scope: CoroutineScope) {
        scheduled?.cancel()
        scheduled = scope.launch {
            delay(debounce)
            syncNow()
        }
    }

    suspend fun syncNow(): SyncOutcome = running.withLock {
        if (api == null) {
            _status.value = SyncStatus.Idle
            return@withLock SyncOutcome.NotConfigured
        }
        val token = secrets.get(tokenKey)
        if (token == null) {
            _status.value = SyncStatus.SignedOut
            return@withLock SyncOutcome.SignedOut
        }

        _status.value = SyncStatus.Syncing
        val outcome = exchange(api, token)
        _status.value = when (outcome) {
            is SyncOutcome.Synced -> SyncStatus.Idle
            SyncOutcome.Offline -> SyncStatus.Offline
            SyncOutcome.SignedOut -> SyncStatus.SignedOut
            SyncOutcome.NotConfigured -> SyncStatus.Idle
            is SyncOutcome.Failed -> SyncStatus.Failed
        }
        outcome
    }

    private suspend fun exchange(api: SyncApi, token: String): SyncOutcome {
        var state = stateStore.loadOrCreate().also { _syncState.value = it }
        var pushed = 0
        var received = 0

        // Two budgets, because the two reasons to go round again are nothing alike. Paging
        // through a long feed is ordinary work and the count depends on how much there is;
        // going round because this device keeps finding more to send is a feedback loop, and
        // three passes is already generous. One budget for both would either cut a first
        // sync short or let a loop spin.
        var pages = 0
        var passes = 0

        while (true) {
            val outgoing = repository.data.value.recordsToPush(state.nodeId, state.lastPushed)

            when (val result = api.sync(token, SyncRequest(since = state.cursor, changes = outgoing))) {
                is ApiResult.Unreachable -> return SyncOutcome.Offline

                ApiResult.Unauthorized -> {
                    // The session is over, the data is not. Dropping the token is the whole
                    // of the response; everything local stays exactly where it is, and the
                    // app carries on offline until someone signs in again.
                    secrets.remove(tokenKey)
                    return SyncOutcome.SignedOut
                }

                is ApiResult.Refused -> return SyncOutcome.Failed("${result.status}: ${result.detail}")

                is ApiResult.Ok -> {
                    val response = result.value
                    pushed += outgoing.size
                    received += response.changes.size

                    val data = repository.applyRemote(response.changes)

                    state = state.copy(
                        cursor = response.cursor,
                        // Moved over everything sent, including anything refused. A refusal
                        // is a bug in this code rather than data to keep retrying, and it is
                        // reported instead — retrying it forever would stop sync settling.
                        lastPushed = maxOf(
                            state.lastPushed,
                            outgoing.maxOfOrNull { record -> record.hlc } ?: state.lastPushed,
                        ),
                        lastHlc = repository.clock.last,
                        lastSyncedAtMillis = Clock.System.now().toEpochMilliseconds(),
                    )
                    stateStore.save(state)
                    _syncState.value = state
                    _lastSyncedAtMillis.value = state.lastSyncedAtMillis

                    if (response.rejected.isNotEmpty()) {
                        return SyncOutcome.Failed("server refused ${response.rejected.size} records")
                    }

                    if (response.more) {
                        pages += 1
                        if (pages > MAX_PAGES) return SyncOutcome.Failed("feed never ended")
                        continue
                    }

                    // Settled only when the server has nothing more and this device owes
                    // nothing — the second half is what catches a merge made moments ago,
                    // after this pass had already sent its changes.
                    val stillOwed = data.recordsToPush(state.nodeId, state.lastPushed)
                    if (stillOwed.isEmpty()) {
                        // Blobs last, and only once the records are settled: which icons are
                        // worth moving is decided by the records, so moving them earlier
                        // would be working from a list about to change.
                        blobs?.exchange(api, token)?.let { result ->
                            when (result) {
                                is ApiResult.Ok -> Unit
                                is ApiResult.Unreachable -> return SyncOutcome.Offline
                                ApiResult.Unauthorized -> {
                                    secrets.remove(tokenKey)
                                    return SyncOutcome.SignedOut
                                }
                                is ApiResult.Refused ->
                                    return SyncOutcome.Failed("blobs: ${result.status} ${result.detail}")
                            }
                        }
                        return SyncOutcome.Synced(pushed, received)
                    }

                    passes += 1
                    if (passes > MAX_SETTLE_PASSES) {
                        return SyncOutcome.Failed("did not settle after $MAX_SETTLE_PASSES passes")
                    }
                }
            }
        }
    }

    private companion object {
        /** Convergence takes three at the very worst; more than that is a feedback loop. */
        const val MAX_SETTLE_PASSES = 5

        /** Only a guard against a server that always says there is more. */
        const val MAX_PAGES = 500
    }
}
