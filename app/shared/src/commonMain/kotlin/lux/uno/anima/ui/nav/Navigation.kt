package lux.uno.anima.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import lux.uno.anima.ui.dashboard.LifeArea

/** Every destination in the app. Small enough that a sealed hierarchy beats a route DSL. */
sealed interface Screen {
    data object Dashboard : Screen
    data object Finance : Screen
    data class SubscriptionDetail(val id: String) : Screen

    /** [id] null means "create a new one". */
    data class SubscriptionEditor(val id: String? = null) : Screen
    data object PaymentMethods : Screen
    data object Categories : Screen
    data class ComingSoon(val area: LifeArea) : Screen
}

/**
 * A plain back stack. The app has no deep links or process-death restoration yet, so a
 * navigation library would add a dependency without adding behaviour.
 */
@Stable
class Navigator(initial: Screen = Screen.Dashboard) {
    private val stack: SnapshotStateList<Screen> = mutableStateListOf(initial)

    val current: Screen get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    fun go(screen: Screen) {
        stack.add(screen)
    }

    /** Replaces the top entry — used after saving, so Back does not return to the editor. */
    fun replace(screen: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(screen)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Drops everything above the first occurrence of [screen], or resets to it. */
    fun popTo(screen: Screen) {
        val index = stack.indexOfFirst { it == screen }
        if (index >= 0) {
            while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)
        } else {
            stack.clear()
            stack.add(screen)
        }
    }
}

@Composable
fun rememberNavigator(initial: Screen = Screen.Dashboard): Navigator = remember { Navigator(initial) }

/** Depth of the stack, used to pick a forward or backward transition. */
@Composable
fun Navigator.rememberDepth(): Int {
    val depth by remember(this) { derivedStateOf { screenDepth(current) } }
    return depth
}

private fun screenDepth(screen: Screen): Int = when (screen) {
    Screen.Dashboard -> 0
    Screen.Finance, is Screen.ComingSoon -> 1
    is Screen.SubscriptionDetail, Screen.PaymentMethods, Screen.Categories -> 2
    is Screen.SubscriptionEditor -> 3
}
