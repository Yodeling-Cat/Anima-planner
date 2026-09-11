package lux.uno.anima.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MergeTest {

    private val older = Hlc.of(1_757_000_000_000, 0, "phone")
    private val newer = Hlc.of(1_757_000_001_000, 0, "desktop")

    private fun record(
        hlc: Hlc,
        id: String = "sub-1",
        deleted: Boolean = false,
        name: String = "Netflix",
        seq: Long = 0,
    ) = SyncRecord(
        kind = RecordKind.SUBSCRIPTION,
        id = id,
        hlc = hlc,
        deleted = deleted,
        payload = if (deleted) null else name,
        seq = seq,
    )

    @Test
    fun `a record we have never seen is taken`() {
        val remote = record(older)
        assertSame(remote, merge(null, remote))
    }

    @Test
    fun `the later edit wins in both directions`() {
        val local = record(newer, name = "edited at the desk")
        val remote = record(older, name = "edited on the plane")

        assertEquals(local, merge(local, remote))
        assertEquals(local, merge(remote, local))
    }

    @Test
    fun `a delete beats an earlier edit`() {
        val edit = record(older, name = "renamed")
        val tombstone = record(newer, deleted = true)

        assertTrue(merge(edit, tombstone).deleted)
    }

    @Test
    fun `an edit beats an earlier delete`() {
        val tombstone = record(older, deleted = true)
        val edit = record(newer, name = "changed my mind")

        val winner = merge(tombstone, edit)
        assertTrue(!winner.deleted)
        assertEquals("changed my mind", winner.payload)
    }

    @Test
    fun `a tie keeps the copy carrying the server position`() {
        // Only the same device in the same millisecond can tie, so this is one write coming
        // back to us. Taking the remote is how the local copy learns its seq.
        val local = record(older, seq = 0)
        val acknowledged = record(older, seq = 42)

        assertEquals(42, merge(local, acknowledged).seq)
    }

    @Test
    fun `merging a batch settles each record independently`() {
        val held = mapOf(
            RecordKey(RecordKind.SUBSCRIPTION, "sub-1") to record(newer, id = "sub-1", name = "kept"),
            RecordKey(RecordKind.SUBSCRIPTION, "sub-2") to record(older, id = "sub-2", name = "replaced"),
        )

        val merged = held.mergedWith(
            listOf(
                record(older, id = "sub-1", name = "stale"),
                record(newer, id = "sub-2", name = "fresh"),
                record(older, id = "sub-3", name = "new to us"),
            ),
        )

        assertEquals(3, merged.size)
        assertEquals("kept", merged.getValue(RecordKey(RecordKind.SUBSCRIPTION, "sub-1")).payload)
        assertEquals("fresh", merged.getValue(RecordKey(RecordKind.SUBSCRIPTION, "sub-2")).payload)
        assertEquals("new to us", merged.getValue(RecordKey(RecordKind.SUBSCRIPTION, "sub-3")).payload)
    }

    @Test
    fun `merging is order independent`() {
        val versions = listOf(
            record(Hlc.of(1_757_000_000_000, 0, "a"), name = "first"),
            record(Hlc.of(1_757_000_002_000, 0, "b"), name = "last"),
            record(Hlc.of(1_757_000_001_000, 0, "c"), name = "middle"),
        )

        repeat(20) {
            val merged = emptyMap<RecordKey, SyncRecord>().mergedWith(versions.shuffled())
            assertEquals("last", merged.values.single().payload)
        }
    }
}
