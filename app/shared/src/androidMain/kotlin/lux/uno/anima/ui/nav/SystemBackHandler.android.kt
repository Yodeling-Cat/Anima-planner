package lux.uno.anima.ui.nav

import androidx.compose.runtime.Composable

@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Handles the gesture and the three-button back alike, and takes part in the predictive
    // back animation, so the peek behind the screen shows what will actually be revealed.
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
