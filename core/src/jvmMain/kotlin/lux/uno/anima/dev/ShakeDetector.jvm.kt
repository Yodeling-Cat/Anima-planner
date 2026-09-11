package lux.uno.anima.dev

/** Desktops have no accelerometer, so the developer screen is opened by keyboard instead. */
actual fun createShakeDetector(): ShakeDetector = UnsupportedShakeDetector()
