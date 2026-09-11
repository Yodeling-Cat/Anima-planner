package lux.uno.anima.sync

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Wide enough for timestamps past the year 10000. */
private const val MILLIS_WIDTH = 12

/** 65,536 stamps inside a single millisecond, which no device will reach by hand. */
private const val COUNTER_WIDTH = 4

internal const val MAX_COUNTER: Int = 0xFFFF

/**
 * A hybrid logical clock reading: wall-clock milliseconds, a counter separating stamps that
 * land in the same millisecond, and the device that issued it.
 *
 * The encoding is the point. Both numbers are fixed-width hex, so comparing the *strings*
 * compares the clocks. That is what lets the server order records whose contents it is not
 * allowed to read, and what reduces the whole merge rule to one `>=`.
 *
 * Node ids are restricted to `[a-z0-9-]` so that string ordering is the same everywhere it
 * might be evaluated — Kotlin's UTF-16 comparison and SQLite's byte-wise `BINARY` collation
 * only agree while the text stays ASCII.
 */
@Serializable
@JvmInline
value class Hlc private constructor(val encoded: String) : Comparable<Hlc> {

    val millis: Long get() = encoded.substring(0, MILLIS_WIDTH).toLong(16)

    val counter: Int
        get() = encoded.substring(MILLIS_WIDTH + 1, MILLIS_WIDTH + 1 + COUNTER_WIDTH).toInt(16)

    val nodeId: String get() = encoded.substring(MILLIS_WIDTH + COUNTER_WIDTH + 2)

    override fun compareTo(other: Hlc): Int = encoded.compareTo(other.encoded)

    override fun toString(): String = encoded

    companion object {
        /**
         * Sorts below every real reading, so a record that has never been stamped always
         * loses a merge. The empty node id makes it a prefix of any same-instant reading,
         * and a prefix sorts first.
         */
        val Zero: Hlc = Hlc("0".repeat(MILLIS_WIDTH) + "-" + "0".repeat(COUNTER_WIDTH) + "-")

        private val NODE_ID = Regex("[a-z0-9-]*")

        fun of(millis: Long, counter: Int, nodeId: String): Hlc {
            require(millis >= 0) { "millis must not be negative, was $millis" }
            require(counter in 0..MAX_COUNTER) { "counter out of range: $counter" }
            require(NODE_ID.matches(nodeId)) { "node id must match [a-z0-9-]*, was '$nodeId'" }
            val hex = millis.toString(16)
            require(hex.length <= MILLIS_WIDTH) { "millis too large to encode: $millis" }
            return Hlc(
                hex.padStart(MILLIS_WIDTH, '0') + "-" +
                    counter.toString(16).padStart(COUNTER_WIDTH, '0') + "-" +
                    nodeId,
            )
        }

        /** Null rather than a throw: a malformed reading is bad data, not a broken caller. */
        fun parseOrNull(encoded: String): Hlc? = runCatching {
            val candidate = Hlc(encoded)
            of(candidate.millis, candidate.counter, candidate.nodeId)
        }.getOrNull()
    }
}
