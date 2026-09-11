package lux.uno.anima.sync

import kotlinx.serialization.Serializable

/**
 * One `POST /sync` carries both directions: the records this device has changed since it
 * last succeeded, and a cursor saying how much of the server's change feed it has already
 * seen. Pushing and pulling together means a device that wakes up on a bad connection gets
 * one chance to succeed rather than two chances to half-fail.
 */
@Serializable
data class SyncRequest(
    /** The highest [SyncRecord.seq] this device has applied. Zero on a first sync. */
    val since: Long,
    val changes: List<SyncRecord> = emptyList(),
)

/**
 * [changes] includes the merged result of whatever was just pushed, so a device finds out
 * in the same round trip when one of its writes lost — and can drop its pending marker
 * either way.
 */
@Serializable
data class SyncResponse(
    val cursor: Long,
    val changes: List<SyncRecord> = emptyList(),
    /** True when the feed was truncated; sync again from [cursor] to collect the rest. */
    val more: Boolean = false,
    /**
     * Records the server would not store. Always a bug on this side rather than a conflict —
     * conflicts resolve silently — so the client surfaces these rather than retrying them.
     */
    val rejected: List<RejectedRecord> = emptyList(),
)

/** A pushed record the server refused, and the structural reason why. */
@Serializable
data class RejectedRecord(val kind: String, val id: String, val reason: String)

/** Device-local sync bookkeeping. Never leaves the device, and never syncs. */
@Serializable
data class SyncState(
    /**
     * Generated once per install and never changed.
     *
     * It breaks [Hlc] ties, and it is how a device recognises its own records — everything
     * waiting to be pushed is identified by this rather than by a list kept alongside the
     * data, so it has to be the same id after a restart as before one.
     */
    val nodeId: String,
    /** The highest server sequence this device has taken in. */
    val cursor: Long = 0,
    /**
     * The highest reading this device has successfully pushed.
     *
     * Everything it stamped above this is still owed to the server. Derived from the data
     * rather than tracked as a list, so the two cannot disagree.
     */
    val lastPushed: Hlc = Hlc.Zero,
    /**
     * The last reading the clock issued, as a second line of defence.
     *
     * Normally redundant: the clock is carried past every stamp in the dataset when that
     * loads, and the highest of those is this. It matters only when the dataset is gone and
     * this file is not. Folding both in costs nothing, because the clock takes the larger.
     */
    val lastHlc: Hlc = Hlc.Zero,
    val lastSyncedAtMillis: Long? = null,
)
