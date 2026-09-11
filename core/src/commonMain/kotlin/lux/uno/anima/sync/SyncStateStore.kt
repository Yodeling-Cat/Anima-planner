package lux.uno.anima.sync

import lux.uno.anima.platform.newId

/**
 * Where a device keeps what it knows about its own syncing.
 *
 * Separate from the dataset because it is about this device rather than about the user:
 * a different phone holding the same subscriptions has a different node id and a different
 * cursor, and neither has any business travelling between them.
 */
interface SyncStateStore {
    suspend fun load(): SyncState?
    suspend fun save(state: SyncState)
}

/**
 * The stored state, or a fresh one written out before it is returned.
 *
 * Saved immediately rather than on the first sync, because the node id has to be the same
 * on the next launch as on this one. A device that generated a new id each time it started
 * would treat everything it had ever pushed as still owed.
 */
suspend fun SyncStateStore.loadOrCreate(): SyncState =
    load() ?: SyncState(nodeId = newId("node")).also { save(it) }

/** Store used by tests — holds the state for the lifetime of the process only. */
class InMemorySyncStateStore(private var state: SyncState? = null) : SyncStateStore {
    override suspend fun load(): SyncState? = state
    override suspend fun save(state: SyncState) {
        this.state = state
    }
}
