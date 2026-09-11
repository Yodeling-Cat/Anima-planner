package lux.uno.anima.sync

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncStateStoreTest {

    private val fileSystem = FakeFileSystem()
    private val directory = "/anima"

    private fun store(fileName: String = "sync-state.json") =
        JsonFileSyncStateStore(fileSystem, directory, fileName)

    @Test
    fun `a fresh device is given a node id and keeps it`() = runTest {
        // The whole point of persisting anything here. A device that generated a new id on
        // every launch would look brand new to itself and re-push everything it had ever
        // sent, because that is how records waiting to go out are recognised.
        val first = store().loadOrCreate()

        val second = store().loadOrCreate()

        assertEquals(first.nodeId, second.nodeId)
    }

    @Test
    fun `the generated node id is one an Hlc will accept`() = runTest {
        val nodeId = store().loadOrCreate().nodeId

        // Hlc.of throws on anything outside [a-z0-9-], which would make the first stamp fail.
        assertEquals(nodeId, Hlc.of(1, 0, nodeId).nodeId)
    }

    @Test
    fun `a new state is written out before it is handed back`() = runTest {
        val store = store()
        store.loadOrCreate()

        assertTrue(fileSystem.exists("$directory/sync-state.json".toPath()))
        assertNotNull(store.load())
    }

    @Test
    fun `the cursor and push mark survive a relaunch`() = runTest {
        val original = store().loadOrCreate().copy(
            cursor = 42,
            lastPushed = Hlc.of(1_757_000_000_000, 3, "phone"),
            lastSyncedAtMillis = 1_757_000_000_500,
        )
        store().save(original)

        assertEquals(original, store().load())
    }

    @Test
    fun `nothing stored reads as nothing rather than failing`() = runTest {
        assertNull(store().load())
    }

    @Test
    fun `a corrupt file starts the device over instead of crashing it`() = runTest {
        // A full re-pull, not a loss: everything comes back from the server, and anything
        // held here that the server does not have goes up under the new id.
        fileSystem.createDirectories(directory.toPath())
        fileSystem.write("$directory/sync-state.json".toPath()) { writeUtf8("{ not json") }

        assertNull(store().load())
        assertNotNull(store().loadOrCreate().nodeId)
    }

    @Test
    fun `an environment keeps its own state`() = runTest {
        // Same guarantee as the datasets: a sandbox cursor must never be read as production's.
        val production = store("sync-state.json").loadOrCreate()
        val sandbox = store("sync-state-local.json").loadOrCreate()

        assertTrue(production.nodeId != sandbox.nodeId)
        assertEquals(production.nodeId, store("sync-state.json").loadOrCreate().nodeId)
    }

    @Test
    fun `a half-written file never replaces a good one`() = runTest {
        val store = store()
        val good = store.loadOrCreate()
        fileSystem.write("$directory/sync-state.json.tmp".toPath()) { writeUtf8("{ interrupted") }

        assertEquals(good, store.load())
    }

    @Test
    fun `the stored reading keeps the clock from going backwards`() = runTest {
        // Belt and braces beside recovering the clock from the dataset: this covers the case
        // where the dataset is gone and this file is not.
        val issued = Hlc.of(1_757_000_900_000, 0, "phone")
        store().save(SyncState(nodeId = "phone", lastHlc = issued))

        val restored = assertNotNull(store().load())
        val clock = HybridClock("phone", last = restored.lastHlc, wallClockMillis = { 1_000 })

        assertTrue(clock.tick() > issued)
    }
}
