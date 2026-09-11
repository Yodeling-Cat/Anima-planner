package lux.uno.anima.dev

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSDate
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import kotlin.math.sqrt

actual fun createShakeDetector(): ShakeDetector = MotionShakeDetector()

/**
 * CoreMotion rather than UIKit's built-in shake event.
 *
 * `motionEnded` reports a shake as one discrete moment, with no way to ask how long it
 * lasted — which is exactly what this gesture is defined by. Reading the accelerometer
 * directly is the only way to require that it was held.
 */
@OptIn(ExperimentalForeignApi::class)
private class MotionShakeDetector : ShakeDetector {

    private val motion = CMMotionManager()
    private val gesture = ShakeGesture()

    override val isSupported: Boolean get() = motion.accelerometerAvailable

    override fun start(onShake: () -> Unit) {
        if (!motion.accelerometerAvailable || motion.accelerometerActive) return
        gesture.reset()
        // 50 Hz: enough to catch the peaks of a shake cycle without waking the CPU more than
        // a screen-open gesture deserves.
        motion.accelerometerUpdateInterval = 1.0 / 50.0
        motion.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
            val acceleration = data?.acceleration ?: return@startAccelerometerUpdatesToQueue
            // Already in g with gravity included, which is what the gesture expects.
            val magnitude = acceleration.useContents { sqrt(x * x + y * y + z * z) }
            val nowMillis = (NSDate().timeIntervalSince1970 * 1000).toLong()
            if (gesture.onSample(magnitude, nowMillis)) onShake()
        }
    }

    override fun stop() {
        if (motion.accelerometerActive) motion.stopAccelerometerUpdates()
        gesture.reset()
    }
}
