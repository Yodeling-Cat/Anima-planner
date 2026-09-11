package lux.uno.anima.ui.nav

import androidx.compose.runtime.Composable

/**
 * Routes the platform's own "go back" into the app's [Navigator].
 *
 * Android has a back gesture and a back button that the system handles by finishing the
 * activity unless something claims them. A custom back stack is exactly the thing that has
 * to claim them — otherwise a swipe from a detail screen quits the app rather than
 * returning to the list, and looks like a crash.
 *
 * [enabled] must be false when there is genuinely nothing to go back to, so the last back
 * press leaves the app the way Android expects rather than trapping the user inside it.
 *
 * Only Android has such a gesture today. iOS's edge swipe belongs to a `UINavigationController`
 * this app does not use, and desktop already has Escape, so both are no-ops.
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
