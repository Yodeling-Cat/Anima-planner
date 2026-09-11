package lux.uno.anima.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlcTest {

    @Test
    fun `round trips its parts`() {
        val hlc = Hlc.of(1_757_000_000_000, 7, "desktop-a91f")
        assertEquals(1_757_000_000_000, hlc.millis)
        assertEquals(7, hlc.counter)
        assertEquals("desktop-a91f", hlc.nodeId)
    }

    @Test
    fun `string order matches clock order`() {
        // Deliberately mixed: hex widths differ, counters wrap, node ids vary in length.
        val ordered = listOf(
            Hlc.Zero,
            Hlc.of(255, 0, "a"),
            Hlc.of(4096, 0, "a"),
            Hlc.of(1_757_000_000_000, 0, "a"),
            Hlc.of(1_757_000_000_000, 9, "a"),
            Hlc.of(1_757_000_000_000, 16, "a"),
            Hlc.of(1_757_000_000_000, 16, "b"),
            Hlc.of(1_757_000_000_001, 0, "a"),
        )
        assertEquals(ordered, ordered.shuffled().sorted())
        assertEquals(ordered, ordered.shuffled().sortedBy { it.encoded })
    }

    @Test
    fun `zero sorts below every real reading`() {
        assertTrue(Hlc.Zero < Hlc.of(0, 0, "a"))
        assertTrue(Hlc.Zero < Hlc.of(1, 0, "a"))
    }

    @Test
    fun `parses back what it encodes`() {
        val hlc = Hlc.of(1_757_000_000_000, 3, "phone-22")
        assertEquals(hlc, Hlc.parseOrNull(hlc.encoded))
        assertEquals(Hlc.Zero, Hlc.parseOrNull(Hlc.Zero.encoded))
    }

    @Test
    fun `refuses malformed readings rather than throwing`() {
        assertNull(Hlc.parseOrNull(""))
        assertNull(Hlc.parseOrNull("nonsense"))
        assertNull(Hlc.parseOrNull("zzzzzzzzzzzz-0000-a"))
        assertNull(Hlc.parseOrNull("000000000000-0000-UPPER"))
    }

    @Test
    fun `rejects node ids that would not sort consistently`() {
        // Anything outside ASCII would order differently under SQLite's BINARY collation.
        assertFailsWith<IllegalArgumentException> { Hlc.of(0, 0, "Device") }
        assertFailsWith<IllegalArgumentException> { Hlc.of(0, 0, "café") }
        assertFailsWith<IllegalArgumentException> { Hlc.of(0, 0, "a:b") }
    }

    @Test
    fun `rejects values the encoding cannot hold`() {
        assertFailsWith<IllegalArgumentException> { Hlc.of(-1, 0, "a") }
        assertFailsWith<IllegalArgumentException> { Hlc.of(0, MAX_COUNTER + 1, "a") }
    }
}
