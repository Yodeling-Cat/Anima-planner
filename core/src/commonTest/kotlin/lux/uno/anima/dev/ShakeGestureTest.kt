package lux.uno.anima.dev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ShakeGestureTest {

    private val gesture = ShakeGesture()

    /** A device sitting on a table: one g of gravity and nothing else. */
    private val atRest = 1.0

    /** Comfortably past the 1.2 g threshold, as a real shake is. */
    private val shaking = 2.6

    /**
     * Feeds [samples] at 50 Hz, matching the sensor rate, and reports how many times the
     * gesture fired.
     */
    private fun feed(samples: List<Double>, fromMillis: Long = 0): Int {
        var fired = 0
        samples.forEachIndexed { index, magnitude ->
            if (gesture.onSample(magnitude, fromMillis + index * 20L)) fired++
        }
        return fired
    }

    @Test
    fun `a second of shaking fires once`() {
        // 1.5s at 50 Hz, comfortably past the hold.
        assertEquals(1, feed(List(75) { shaking }))
    }

    @Test
    fun `a brief shake does nothing`() {
        // Half a second of enthusiasm is someone putting their phone in a pocket.
        assertEquals(0, feed(List(25) { shaking }))
    }

    @Test
    fun `oscillating through rest does not break the gesture`() {
        // A real shake crosses rest twice per cycle. If those crossings reset the window,
        // the gesture can never complete — this is the case the gap tolerance exists for.
        val cycling = List(150) { if (it % 5 < 2) atRest else shaking }
        assertEquals(1, feed(cycling))
    }

    @Test
    fun `pausing resets the window`() {
        // 0.8s of shaking, a second of stillness, then 0.8s more. Neither half reaches the
        // one-second hold, and the pause must stop them from being added together.
        val stopStart = List(40) { shaking } + List(50) { atRest } + List(40) { shaking }
        assertEquals(0, feed(stopStart), "the two halves must not add up to one shake")
    }

    @Test
    fun `holding the shake does not fire repeatedly`() {
        // Ten seconds of shaking is still one request to open the screen.
        assertEquals(1, feed(List(500) { shaking }))
    }

    @Test
    fun `shaking again after stopping fires again`() {
        assertEquals(1, feed(List(150) { shaking }))
        assertEquals(0, feed(List(50) { atRest }, fromMillis = 3_000))
        assertEquals(1, feed(List(150) { shaking }, fromMillis = 5_000))
    }

    @Test
    fun `being carried around never fires`() {
        // Walking swings a phone through roughly a third of a g. Two minutes of it.
        val walking = List(6_000) { if (it % 25 < 12) 1.3 else 0.7 }
        assertEquals(0, feed(walking))
    }

    @Test
    fun `reset drops a gesture in progress`() {
        assertEquals(0, feed(List(40) { shaking }))
        gesture.reset()
        assertEquals(0, feed(List(40) { shaking }, fromMillis = 800))
    }

    @Test
    fun `weightlessness alone is not a shake`() {
        // Free fall reads 0 g, which is only 1.0 below rest — under the threshold. Pinned
        // because it is the case that makes a two-sided check look necessary when it is not:
        // magnitudes cannot go below zero, so there is nothing further down to detect.
        assertFalse(ShakeGesture().onSample(0.0, 0))
        assertEquals(0, feed(List(150) { 0.0 }))
    }

    @Test
    fun `a shake through free fall still fires on its peaks`() {
        assertEquals(1, feed(List(150) { if (it % 2 == 0) 0.0 else 2.6 }))
    }

    @Test
    fun `a still device is never shaking`() {
        assertFalse(ShakeGesture().onSample(atRest, 0))
        assertEquals(0, feed(List(1_000) { atRest }))
    }
}
