package lux.uno.anima.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridClockTest {

    /** A wall clock the test moves by hand, so nothing here depends on real time passing. */
    private class FakeWallClock(var millis: Long = 1_757_000_000_000) {
        operator fun invoke(): Long = millis
    }

    @Test
    fun `stamps keep rising inside one millisecond`() {
        val wall = FakeWallClock()
        val clock = HybridClock("device-a", wallClockMillis = wall::invoke)

        val stamps = List(5) { clock.tick() }

        assertEquals(stamps, stamps.sorted())
        assertEquals(List(5) { it }, stamps.map { it.counter })
        assertTrue(stamps.all { it.millis == wall.millis })
    }

    @Test
    fun `counter resets once the wall clock moves on`() {
        val wall = FakeWallClock()
        val clock = HybridClock("device-a", wallClockMillis = wall::invoke)

        clock.tick()
        clock.tick()
        wall.millis += 1
        val next = clock.tick()

        assertEquals(0, next.counter)
        assertEquals(wall.millis, next.millis)
    }

    @Test
    fun `a wall clock that jumps backwards cannot reissue a reading`() {
        val wall = FakeWallClock()
        val clock = HybridClock("device-a", wallClockMillis = wall::invoke)

        val before = clock.tick()
        wall.millis -= 60_000 // an NTP correction, or the user fixing their timezone
        val after = clock.tick()

        assertTrue(after > before)
        assertEquals(before.millis, after.millis)
    }

    @Test
    fun `a slow device adopts the time it is shown`() {
        // The failure this exists to prevent: the phone is ten minutes behind, so the edit
        // it makes *after* seeing the desktop's would otherwise be stamped into the past
        // and lose the merge.
        val desktopWall = FakeWallClock()
        val phoneWall = FakeWallClock(millis = desktopWall.millis - 600_000)

        val desktop = HybridClock("desktop", wallClockMillis = desktopWall::invoke)
        val phone = HybridClock("phone", wallClockMillis = phoneWall::invoke)

        val fromDesktop = desktop.tick()
        phone.observe(fromDesktop)
        val fromPhone = phone.tick()

        assertTrue(fromPhone > fromDesktop, "$fromPhone should sort above $fromDesktop")
        assertEquals(merge(record(fromDesktop), record(fromPhone)).hlc, fromPhone)
    }

    @Test
    fun `observing an older reading changes nothing`() {
        val wall = FakeWallClock()
        val clock = HybridClock("device-a", wallClockMillis = wall::invoke)

        val current = clock.tick()
        clock.observe(Hlc.of(current.millis - 5_000, 0, "device-b"))
        val next = clock.tick()

        assertTrue(next > current)
        assertEquals(wall.millis, next.millis)
    }

    @Test
    fun `a full counter borrows a millisecond instead of refusing`() {
        val wall = FakeWallClock()
        val clock = HybridClock(
            "device-a",
            last = Hlc.of(wall.millis, MAX_COUNTER, "device-a"),
            wallClockMillis = wall::invoke,
        )

        val next = clock.tick()

        assertEquals(wall.millis + 1, next.millis)
        assertEquals(0, next.counter)
    }

    @Test
    fun `a persisted reading survives a restart`() {
        val wall = FakeWallClock()
        val first = HybridClock("device-a", wallClockMillis = wall::invoke)
        val before = first.tick()

        // Relaunch with the clock rolled back, as a fresh process would see it.
        wall.millis -= 10_000
        val second = HybridClock("device-a", last = first.last, wallClockMillis = wall::invoke)

        assertTrue(second.tick() > before)
    }

    private fun record(hlc: Hlc) = SyncRecord(RecordKind.SUBSCRIPTION, "sub-1", hlc)
}
