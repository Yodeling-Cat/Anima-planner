package lux.uno.anima.dev

/** The simulator shares the host's network stack, and a real device needs a typed-in address. */
actual fun localhostForDevelopment(): String = "localhost"
