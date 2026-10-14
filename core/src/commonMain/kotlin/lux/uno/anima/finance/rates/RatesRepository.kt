package lux.uno.anima.finance.rates

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import lux.uno.anima.sync.ApiResult

/** Why the app currently has no usable conversion, for the one line that says so. */
enum class RatesProblem { NOT_CONFIGURED, UNREACHABLE, REFUSED }

/** What the settings screen shows about conversion, and nothing the domain needs. */
data class RatesStatus(
    val rates: ExchangeRates? = null,
    val refreshing: Boolean = false,
    val problem: RatesProblem? = null,
)

/**
 * Holds the current rate table, refills it, and keeps it warm.
 *
 * The cached table is published before the first fetch is even attempted, so the app opens
 * converting with yesterday's rates rather than showing dashes until the network answers.
 * That is the offline-first rule applied to a cache: what is on disk is what the UI reads,
 * and the network only ever improves it.
 *
 * A failed refresh never clears what is held. A rate from yesterday is a usable answer and
 * a missing one is not, so the only thing a failure changes is the line on the settings
 * screen saying when the table is from.
 */
@OptIn(ExperimentalTime::class)
class RatesRepository(
    private val api: RatesApi?,
    private val store: RatesStore,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val state = MutableStateFlow(
        if (api == null) RatesStatus(problem = RatesProblem.NOT_CONFIGURED) else RatesStatus(),
    )
    val status: StateFlow<RatesStatus> = state.asStateFlow()

    /** What every caller that only wants to convert reads. Null until something is loaded. */
    val rates: ExchangeRates? get() = state.value.rates

    /** Publishes whatever the last run left behind, then tops it up if it has gone stale. */
    suspend fun start() {
        if (state.value.rates == null) {
            store.load()?.let { cached -> state.value = state.value.copy(rates = cached) }
        }
        refreshIfStale()
    }

    /**
     * Asks again only when what is held has aged past [RATES_FRESH_FOR_MILLIS].
     *
     * The check is here rather than at the call sites so that launch, returning to the
     * foreground and the hourly tick can all simply ask, and a phone picked up ten times an
     * hour still makes one request.
     */
    suspend fun refreshIfStale() {
        val held = state.value.rates
        if (held != null && held.isFreshAt(nowMillis())) return
        refreshNow()
    }

    /** Fetches regardless of age — what the settings screen's own refresh does. */
    suspend fun refreshNow() {
        val api = api ?: return
        if (state.value.refreshing) return
        state.value = state.value.copy(refreshing = true)

        val result = api.latest()
        state.value = when (result) {
            is ApiResult.Ok -> {
                store.save(result.value)
                RatesStatus(rates = result.value, refreshing = false, problem = null)
            }
            // The held table survives every failure. See the class comment.
            is ApiResult.Unreachable -> state.value.copy(
                refreshing = false,
                problem = RatesProblem.UNREACHABLE,
            )
            else -> state.value.copy(refreshing = false, problem = RatesProblem.REFUSED)
        }
    }

    /**
     * Keeps the table topped up while the app runs.
     *
     * Hourly rather than more often because the source publishes once per working day: the
     * tick exists to catch the moment that publication happens and to cover an app left open
     * for days, not to chase a rate that does not move. [refreshIfStale] makes most ticks
     * cost nothing at all.
     */
    fun keepWarm(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            delay(REFRESH_INTERVAL_MILLIS)
            refreshIfStale()
        }
    }

    companion object {
        const val REFRESH_INTERVAL_MILLIS: Long = 60L * 60 * 1000
    }
}
