package lux.uno.anima.dev

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentStoreTest {

    private val fileSystem = FakeFileSystem()
    private val directory = "/anima"

    private fun store() = EnvironmentStore(fileSystem, directory)

    @Test
    fun `production owns the files the app already wrote`() {
        // The app shipped before environments existed. If Production were suffixed, every
        // existing install would open to an empty app and its real data would look lost.
        assertEquals("finance.json", Environments.Production.dataFileName)
        assertEquals("icons", Environments.Production.iconFolderName)
    }

    @Test
    fun `no two environments share any storage`() {
        // The guarantee the sandbox rests on. A collision here means test data could be
        // read as real data, or pushed to the live server.
        val names = Environments.all.flatMap {
            listOf(
                it.dataFileName,
                it.iconFolderName,
                it.syncStateFileName,
                it.handledRemindersFileName,
                it.sessionTokenKey,
            )
        }
        assertEquals(names.size, names.toSet().size, "environments overlap: $names")
    }

    @Test
    fun `it starts on production`() = runTest {
        val store = store()
        store.load()
        assertEquals(Environments.Production.id, store.active.value.id)
    }

    @Test
    fun `a choice survives a relaunch`() = runTest {
        store().apply { load() }.select(Environments.Sandbox.id)

        val reopened = store()
        reopened.load()
        assertEquals(Environments.Sandbox.id, reopened.active.value.id)
    }

    @Test
    fun `switching swaps which files the app will read`() = runTest {
        val store = store()
        store.load()
        val before = store.active.value.dataFileName

        store.select(Environments.Development.id)

        assertTrue(store.active.value.dataFileName != before)
        assertEquals("finance-dev.json", store.active.value.dataFileName)
    }

    @Test
    fun `a typed server address applies and persists`() = runTest {
        val store = store()
        store.load()
        store.select(Environments.Development.id)
        store.setServerUrl(Environments.Development.id, "http://192.168.1.20:8080/")

        // Trailing slash trimmed, so paths appended later do not double up.
        assertEquals("http://192.168.1.20:8080", store.active.value.serverUrl)

        val reopened = store()
        reopened.load()
        assertEquals("http://192.168.1.20:8080", reopened.active.value.serverUrl)
    }

    @Test
    fun `clearing the address returns the built-in one`() = runTest {
        val store = store()
        store.load()
        store.select(Environments.Development.id)
        store.setServerUrl(Environments.Development.id, "http://example.test:9000")
        store.setServerUrl(Environments.Development.id, "   ")

        assertEquals(Environments.Development.serverUrl, store.active.value.serverUrl)
    }

    @Test
    fun `the sandbox cannot be given a server`() = runTest {
        // Its whole promise is that nothing in it leaves the device, so configuration must
        // not be able to take that away.
        val store = store()
        store.load()
        store.select(Environments.Sandbox.id)
        store.setServerUrl(Environments.Sandbox.id, "https://anima.lux.uno")

        assertNull(store.active.value.serverUrl)
        assertTrue(!store.active.value.syncs)
    }

    @Test
    fun `an unknown id falls back to production rather than failing`() = runTest {
        fileSystem.createDirectories(directory.toPath())
        fileSystem.write("$directory/environment.json".toPath()) {
            writeUtf8("""{"activeId":"retired-environment"}""")
        }

        val store = store()
        store.load()
        assertEquals(Environments.Production.id, store.active.value.id)
    }

    @Test
    fun `a corrupt file opens on production instead of crashing`() = runTest {
        fileSystem.createDirectories(directory.toPath())
        fileSystem.write("$directory/environment.json".toPath()) {
            writeUtf8("not json at all")
        }

        val store = store()
        store.load()
        assertEquals(Environments.Production.id, store.active.value.id)
    }

    @Test
    fun `the development default points somewhere a device can actually reach`() {
        val url = assertNotNull(Environments.Development.serverUrl)
        assertTrue(url.contains(localhostForDevelopment()), "expected $url to use the host alias")
    }
}
