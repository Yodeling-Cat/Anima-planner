package lux.uno.anima.sync

import kotlinx.serialization.Serializable

/** Identifies one record across every device and on the server. */
@Serializable
data class RecordKey(val kind: String, val id: String) {
    override fun toString(): String = "$kind:$id"
}

/**
 * A synced thing, in the only shape the server ever sees.
 *
 * Everything above [payload] is the typed spine — what the server needs to authorize the
 * write, order it against what it already holds, and hand it back to another device.
 * [payload] is the leaf: the server stores it, versions it and returns it, and never looks
 * inside. Nothing on the server may come to depend on reading it, because that is what
 * keeps the door open to encrypting it end-to-end without touching this protocol.
 */
@Serializable
data class SyncRecord(
    val kind: String,
    val id: String,
    val hlc: Hlc,
    /** A tombstone, not an absence — see [merge] for why deletes need a reading of their own. */
    val deleted: Boolean = false,
    /**
     * The record's own serialised form, carried as text the server treats as bytes.
     *
     * Not a `JsonElement`, deliberately. Typing it as structured JSON would oblige the
     * server to parse it just to hand it back, and once it is parsing it is one careless
     * commit away from reading it. A string also means the day this becomes ciphertext,
     * nothing about the protocol changes.
     *
     * Null exactly when [deleted]; there is nothing left to describe.
     */
    val payload: String? = null,
    /** Where this landed in the server's change feed. Zero on the way up. */
    val seq: Long = 0,
) {
    val key: RecordKey get() = RecordKey(kind, id)
}

/** The kinds that sync. Shared so the server's rows and the client's records agree. */
object RecordKind {
    const val SUBSCRIPTION = "subscription"
    const val PAYMENT_METHOD = "paymentMethod"
    const val CATEGORY = "category"
    const val SETTINGS = "settings"

    /** The settings singleton has no natural id, so it is given one. */
    const val SETTINGS_ID = "default"
}

/**
 * Picks the winner between what is held and what arrived.
 *
 * This comparison is the entire conflict story. Anima has one user, so two versions of a
 * record are never two people disagreeing — they are one person who edited on a plane and
 * edited again at a desk. The later edit is the one they meant, so it wins silently and
 * nothing is ever raised for them to resolve.
 *
 * Deletes carry an [Hlc] like any other change, which is why deleting on one device and
 * editing on another settles by recency instead of needing a rule of its own.
 *
 * A tie means the same device stamped the same millisecond with the same counter, so the
 * two are the same write arriving twice. The incoming copy is preferred because it carries
 * the server's [SyncRecord.seq], which the local copy has not been told yet.
 */
fun merge(local: SyncRecord?, remote: SyncRecord): SyncRecord =
    if (local == null || remote.hlc >= local.hlc) remote else local

/** Folds [incoming] into a set of records held by key, resolving each one with [merge]. */
fun Map<RecordKey, SyncRecord>.mergedWith(
    incoming: Iterable<SyncRecord>,
): Map<RecordKey, SyncRecord> {
    val result = toMutableMap()
    for (record in incoming) {
        result[record.key] = merge(result[record.key], record)
    }
    return result
}
