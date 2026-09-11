package lux.uno.anima.dev

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import lux.uno.anima.platform.AndroidPlatform
import kotlin.math.sqrt

actual fun createShakeDetector(): ShakeDetector = AndroidShakeDetector()

private class AndroidShakeDetector : ShakeDetector, SensorEventListener {

    private val manager: SensorManager?
        get() = AndroidPlatform.requireContext()
            .getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor?
        get() = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override val isSupported: Boolean get() = accelerometer != null

    private val gesture = ShakeGesture()
    private var listener: (() -> Unit)? = null

    override fun start(onShake: () -> Unit) {
        val sensor = accelerometer ?: return
        listener = onShake
        gesture.reset()
        // GAME rather than UI: the gesture needs enough samples to see the peaks of a shake
        // cycle, and NORMAL is too slow to catch them reliably.
        manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        manager?.unregisterListener(this)
        listener = null
        gesture.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        // Android reports m/s² with gravity included; the gesture works in g.
        val magnitude = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (gesture.onSample(magnitude.toDouble(), System.currentTimeMillis())) {
            listener?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
