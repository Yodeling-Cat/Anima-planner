package lux.uno.anima.ui.nav

import androidx.compose.runtime.Composable

/** Desktop back is Escape, handled by the key listener at the root of the app. */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
