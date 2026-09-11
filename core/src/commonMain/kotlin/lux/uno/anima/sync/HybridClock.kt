package lux.uno.anima.sync

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Bits the counter occupies in the packed state; the millis take the remaining 48. */
private const val COUNTER_BITS = 16

/**
 * Issues [Hlc] readings for this device.
 *
 * Two rules keep the ordering honest when device clocks disagree: a reading is never below
 * the last one this device issued, and never below the highest one it has been shown from
 * anywhere else. So a laptop running ten minutes slow adopts the time it is handed rather
 * than stamping today's edits into the past — without that, the edit made *later* would be
 * the one discarded, which is exactly the failure last-write-wins is supposed to avoid.
 *
 * [last] is worth persisting. A device whose wall clock jumps backwards between launches
 * would otherwise reissue readings it has already used.
 *
 * The millisecond and the counter are held packed in one [AtomicLong] because they have to
 * move together — the 48 bits [Hlc] allows for the one and the 16 for the other are exactly
 * a Long, so a single compare-and-set covers both.
 */
@OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)
class HybridClock(
    val nodeId: String,
    last: Hlc = Hlc.Zero,
    private val wallClockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val state = AtomicLong(pack(last.millis, last.counter))

    /** The most recent reading issued or absorbed. Persist this across launches. */
    val last: Hlc
        get() = state.load().let { Hlc.of(it ushr COUNTER_BITS, (it and 0xFFFF).toInt(), nodeId) }

    /** Stamps a change made here. Every call returns a reading above the one before it. */
    fun tick(): Hlc = advance(null)

    /**
     * Folds in a reading from elsewhere so that anything stamped afterwards sorts above it.
     * Called for every record received, whatever the merge then decides to do with it.
     */
    fun observe(remote: Hlc): Hlc = advance(remote)

    private fun advance(remote: Hlc?): Hlc {
        while (true) {
            val previous = state.load()
            val next = step(previous ushr COUNTER_BITS, (previous and 0xFFFF).toInt(), remote)
            if (state.compareAndSet(previous, next)) {
                return Hlc.of(next ushr COUNTER_BITS, (next and 0xFFFF).toInt(), nodeId)
            }
        }
    }

    private fun step(lastMillis: Long, lastCounter: Int, remote: Hlc?): Long {
        val remoteMillis = remote?.millis ?: Long.MIN_VALUE
        val physical = maxOf(lastMillis, remoteMillis, wallClockMillis())

        // The counter only continues where physical time has not moved on; whichever inputs
        // are still level with it contribute, so no source can be stamped over.
        var counter = when {
            physical == lastMillis && physical == remoteMillis ->
                maxOf(lastCounter, remote!!.counter) + 1
            physical == lastMillis -> lastCounter + 1
            physical == remoteMillis -> remote!!.counter + 1
            else -> 0
        }

        // Borrow from physical time rather than refusing the stamp. A millisecond of drift
        // costs nothing; losing a write costs the user their edit.
        var millis = physical
        if (counter > MAX_COUNTER) {
            millis += 1
            counter = 0
        }
        return pack(millis, counter)
    }

    private companion object {
        fun pack(millis: Long, counter: Int): Long {
            require(millis shr (Long.SIZE_BITS - COUNTER_BITS) == 0L) {
                "millis too large to pack: $millis"
            }
            return (millis shl COUNTER_BITS) or counter.toLong()
        }
    }
}
