package lux.uno.anima.dev

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Decides when a stream of accelerometer samples counts as a deliberate, sustained shake.
 *
 * Kept apart from the sensors so it can be tested without one, and so all three platforms
 * agree on what "shaking for a second" means.
 *
 * The subtlety is that a shake is not continuously above the threshold. Acceleration
 * oscillates as the hand changes direction, crossing rest twice per cycle, so a naive
 * "above threshold for a whole second" never fires. Instead the window survives gaps shorter
 * than [gapTolerance], and only a real pause ends it.
 */
class ShakeGesture(
    /**
     * How far *above* rest, in g, counts as shaking. A brisk shake peaks well past this.
     *
     * Only above: the samples are magnitudes, so they cannot go below zero, and the furthest
     * below rest a device can read is free fall at 1.0 g under. Anything low enough to catch
     * that would also catch a brisk walk, so the useful signal is all on the upper side.
     */
    private val thresholdG: Double = 1.2,
    private val holdFor: Duration = 1.seconds,
    /** Longer than the quiet part of a shake cycle, shorter than a deliberate stop. */
    private val gapTolerance: Duration = 400.milliseconds,
) {
    private var startedAt: Long? = null
    private var lastAboveAt: Long = 0
    private var fired = false

    /**
     * @param magnitudeG total acceleration including gravity, so a device at rest reads 1.0.
     * @return true exactly once per shake, at the moment it has been held long enough.
     */
    fun onSample(magnitudeG: Double, atMillis: Long): Boolean {
        if (magnitudeG - 1.0 < thresholdG) {
            // Below threshold. Only a gap longer than one shake cycle ends the gesture.
            if (startedAt != null && atMillis - lastAboveAt > gapTolerance.inWholeMilliseconds) {
                reset()
            }
            return false
        }

        lastAboveAt = atMillis
        val began = startedAt ?: atMillis.also { startedAt = it }

        // Held long enough, and not already reported. Staying shaky afterwards does nothing
        // until the device is still again, so one long shake opens one screen.
        if (!fired && atMillis - began >= holdFor.inWholeMilliseconds) {
            fired = true
            return true
        }
        return false
    }

    fun reset() {
        startedAt = null
        lastAboveAt = 0
        fired = false
    }
}

/**
 * Watches the accelerometer for the gesture that opens the developer screen.
 *
 * [isSupported] is false where there is no accelerometer — every desktop — and the UI offers
 * a keyboard shortcut there instead.
 */
interface ShakeDetector {
    val isSupported: Boolean
    fun start(onShake: () -> Unit)
    fun stop()
}

expect fun createShakeDetector(): ShakeDetector

/** Stands in wherever there is nothing to listen to. */
internal class UnsupportedShakeDetector : ShakeDetector {
    override val isSupported: Boolean = false
    override fun start(onShake: () -> Unit) = Unit
    override fun stop() = Unit
}
