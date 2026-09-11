package lux.uno.anima.dev

/**
 * The emulator forwards this to the host machine's loopback. On a physical device it is
 * wrong, and the address has to be typed into the developer screen — there is no way to
 * discover the developer's laptop from here.
 */
actual fun localhostForDevelopment(): String = "10.0.2.2"
