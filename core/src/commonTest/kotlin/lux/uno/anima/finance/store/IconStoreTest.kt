package lux.uno.anima.finance.store

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These use raw bytes rather than real images: [IconStore.store] resizes through a platform
 * decoder, which has nothing to do with the addressing being tested here. [IconStore.put] is
 * the same path a download takes, and takes bytes as they are.
 */
class IconStoreTest {

    private val fileSystem = FakeFileSystem()
    private val directory = "/anima"

    private fun store() = IconStore(fileSystem, directory, "icons")

    private fun hashOf(bytes: ByteArray) = bytes.toByteString().sha256().hex()

    private val png = "pretend this is a png".encodeToByteArray()

    @Test
    fun `an icon is named after its own bytes`() = runTest {
        val store = store()
        assertTrue(store.put(hashOf(png), png))

        assertEquals(setOf(hashOf(png)), store.stored())
        assertEquals(png.toList(), assertNotNull(store.load(hashOf(png))).toList())
    }

    @Test
    fun `the same picture added twice is one file`() = runTest {
        // The whole reason for content addressing: two devices adding the same image reach
        // the same id, so there is never anything to reconcile.
        val store = store()
        store.put(hashOf(png), png)
        store.put(hashOf(png), png)

        assertEquals(1, store.stored().size)
    }

    @Test
    fun `bytes that do not match the name are refused`() = runTest {
        // An id that stopped describing its contents would poison every device that later
        // asked the server for it.
        val store = store()

        assertFalse(store.put(hashOf(png), "different bytes entirely".encodeToByteArray()))
        assertTrue(store.stored().isEmpty())
    }

    @Test
    fun `an id that is not a hash at all is refused`() = runTest {
        assertFalse(store().put("icon-x7f2abc", png))
    }

    // region migrating off random ids

    private suspend fun writeLegacy(id: String, bytes: ByteArray) {
        fileSystem.createDirectories("$directory/icons".toPath())
        fileSystem.write("$directory/icons/$id.png".toPath()) { write(bytes) }
    }

    @Test
    fun `an old random id is renamed to the hash of its contents`() = runTest {
        writeLegacy("icon-x7f2abc", png)

        val moved = store().migrateToContentAddressing()

        assertEquals(mapOf("icon-x7f2abc" to hashOf(png)), moved)
        assertEquals(setOf(hashOf(png)), store().stored())
        assertEquals(png.toList(), assertNotNull(store().load(hashOf(png))).toList())
    }

    @Test
    fun `two old ids holding the same picture collapse into one file`() = runTest {
        // Exactly the duplication content addressing exists to remove, and the migration is
        // where the existing copies of it get cleaned up.
        writeLegacy("icon-aaa", png)
        writeLegacy("icon-bbb", png)

        val moved = store().migrateToContentAddressing()

        assertEquals(setOf(hashOf(png)), moved.values.toSet())
        assertEquals(1, store().stored().size)
    }

    @Test
    fun `running it again does nothing`() = runTest {
        writeLegacy("icon-x7f2abc", png)
        store().migrateToContentAddressing()

        assertTrue(store().migrateToContentAddressing().isEmpty())
        assertEquals(setOf(hashOf(png)), store().stored())
    }

    @Test
    fun `an interrupted migration carries on where it stopped`() = runTest {
        // A file already named after its own hash is left alone, so a half-finished run is
        // safe to repeat rather than something to recover from.
        val other = "a second picture".encodeToByteArray()
        writeLegacy(hashOf(png), png)
        writeLegacy("icon-later", other)

        val moved = store().migrateToContentAddressing()

        assertEquals(mapOf("icon-later" to hashOf(other)), moved)
        assertEquals(setOf(hashOf(png), hashOf(other)), store().stored())
    }

    @Test
    fun `nothing to migrate is not a failure`() = runTest {
        assertTrue(store().migrateToContentAddressing().isEmpty())
    }

    // endregion

    @Test
    fun `pruning removes what nothing points at`() = runTest {
        val other = "a second picture".encodeToByteArray()
        val store = store()
        store.put(hashOf(png), png)
        store.put(hashOf(other), other)

        store.pruneExcept(setOf(hashOf(png)))

        assertEquals(setOf(hashOf(png)), store.stored())
        assertNull(store.load(hashOf(other)))
    }
}
