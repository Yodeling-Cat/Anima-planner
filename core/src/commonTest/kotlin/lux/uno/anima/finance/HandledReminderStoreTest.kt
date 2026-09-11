package lux.uno.anima.finance

import kotlinx.coroutines.test.runTest
import lux.uno.anima.finance.reminders.JsonFileHandledReminderStore
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandledReminderStoreTest {

    private val fileSystem = FakeFileSystem()
    private val directory = "/anima"

    private fun store(fileName: String = "handled-reminders.json") =
        JsonFileHandledReminderStore(fileSystem, directory, fileName)

    @Test
    fun `what was saved comes back on the next launch`() = runTest {
        store().save(setOf("reminder-a", "reminder-b"))

        assertEquals(setOf("reminder-a", "reminder-b"), store().load())
    }

    @Test
    fun `nothing written yet is nothing handled`() = runTest {
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `an unreadable file costs one repeated notification rather than a crash`() = runTest {
        fileSystem.createDirectories(directory.toPath())
        fileSystem.write("$directory/handled-reminders.json".toPath()) {
            writeUtf8("{ not a list at all")
        }

        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `saving replaces rather than accumulates`() = runTest {
        // The coordinator drops ids whose card no longer exists, so a save that merged
        // would let the list grow forever.
        store().save(setOf("reminder-a"))
        store().save(setOf("reminder-b"))

        assertEquals(setOf("reminder-b"), store().load())
    }

    @Test
    fun `environments keep separate lists`() = runTest {
        store("handled-reminders.json").save(setOf("prod-reminder"))

        assertTrue(store("handled-reminders-dev.json").load().isEmpty())
    }
}
