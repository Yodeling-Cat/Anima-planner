package lux.uno.anima.ui.nav

import androidx.compose.runtime.Composable

/** iOS has no system back affordance to intercept; every screen carries its own control. */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
