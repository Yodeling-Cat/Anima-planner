package lux.uno.anima.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lux.uno.anima.ui.dashboard.LifeArea

/**
 * Every destination in the app. Small enough that a sealed hierarchy beats a route DSL.
 *
 * Serialisable so the back stack can be written into the saved instance state — Android
 * destroys the activity on every rotation, and a stack held only in memory does not survive
 * that.
 */
@Serializable
sealed interface Screen {
    @Serializable data object Dashboard : Screen
    @Serializable data object Finance : Screen
    @Serializable data class SubscriptionDetail(val id: String) : Screen

    /** [id] null means "create a new one". */
    @Serializable data class SubscriptionEditor(val id: String? = null) : Screen
    @Serializable data object PaymentMethods : Screen
    @Serializable data object Categories : Screen
    @Serializable data object Account : Screen
    @Serializable data class ComingSoon(val area: LifeArea) : Screen
}

/**
 * A plain back stack. The app has no deep links or process-death restoration yet, so a
 * navigation library would add a dependency without adding behaviour.
 */
/** Which way the app last moved through the stack. */
enum class Move { Forward, Backward }

@Stable
class Navigator(initial: Screen = Screen.Dashboard) {
    private val stack: SnapshotStateList<Screen> = mutableStateListOf(initial)

    constructor(restored: List<Screen>) : this(restored.first()) {
        stack.clear()
        stack.addAll(restored)
    }

    val current: Screen get() = stack.last()

    /** The stack bottom-first, for writing into saved state. */
    val entries: List<Screen> get() = stack.toList()

    val canGoBack: Boolean get() = stack.size > 1

    /**
     * How the app arrived at [current], so the screen transition can play backwards when
     * leaving one.
     *
     * Recorded as it happens rather than worked out from how deep each screen sits. A depth
     * table would be one more thing to remember to update every time a screen is added, and
     * forgetting would show up as an animation that quietly plays the wrong way.
     */
    var lastMove: Move by mutableStateOf(Move.Forward)
        private set

    fun go(screen: Screen) {
        lastMove = Move.Forward
        stack.add(screen)
    }

    /** Replaces the top entry — used after saving, so Back does not return to the editor. */
    fun replace(screen: Screen) {
        // Saving and moving on to the saved thing reads as carrying on, not retreating.
        lastMove = Move.Forward
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(screen)
    }

    /**
     * Goes to a section from the rail: the stack becomes the root screen and [screen] above it.
     *
     * Not [go], which would stack sections on top of each other, and not [popTo], which
     * resets the stack to [screen] alone when it is not on it — the rail is reachable from
     * every depth, and either way Back would come to mean "the thing before this, in a
     * section you have already left". Rooted rather than replaced so back and Escape still
     * walk home from anywhere.
     */
    fun switchTo(screen: Screen) {
        val root = stack.first()
        val target = if (screen == root) listOf(root) else listOf(root, screen)
        if (stack.toList() == target) return
        lastMove = if (target.size < stack.size) Move.Backward else Move.Forward
        stack.clear()
        stack.addAll(target)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        lastMove = Move.Backward
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Drops everything above the first occurrence of [screen], or resets to it. */
    fun popTo(screen: Screen) {
        lastMove = Move.Backward
        val index = stack.indexOfFirst { it == screen }
        if (index >= 0) {
            while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)
        } else {
            stack.clear()
            stack.add(screen)
        }
    }
}

/**
 * The life area a screen belongs to, so the rail can mark where you are from anywhere
 * inside a section rather than only on the section's own screen.
 *
 * Null means the screen is not inside an area at all — the dashboard and the account.
 */
val Screen.area: LifeArea?
    get() = when (this) {
        Screen.Finance,
        Screen.PaymentMethods,
        Screen.Categories,
        is Screen.SubscriptionDetail,
        is Screen.SubscriptionEditor,
        -> LifeArea.FINANCE

        is Screen.ComingSoon -> this.area
        Screen.Dashboard, Screen.Account -> null
    }

/**
 * The screen that stays in the list pane while this one fills the detail pane, where there
 * is room for both. Null means the screen wants the whole width.
 *
 * Derived from the destination rather than from how deep it sits, for the same reason
 * [Navigator.lastMove] is recorded rather than computed: a depth table is one more thing to
 * remember to update, and forgetting shows up as a layout that quietly stops splitting.
 */
val Screen.listPane: Screen?
    get() = when (this) {
        Screen.PaymentMethods,
        Screen.Categories,
        is Screen.SubscriptionDetail,
        is Screen.SubscriptionEditor,
        -> Screen.Finance

        else -> null
    }

/** The subscription a screen is about, so a list beside it can mark the matching row. */
val Screen.subscriptionId: String?
    get() = when (this) {
        is Screen.SubscriptionDetail -> id
        is Screen.SubscriptionEditor -> id
        else -> null
    }

private val screenFormat = Json { ignoreUnknownKeys = true }

/**
 * Identifies one entry of the back stack for [androidx.compose.runtime.saveable.SaveableStateHolder].
 *
 * Only one screen is composed at a time, so a screen that is still on the stack but sitting
 * underneath another has left the composition and would otherwise lose everything typed into
 * it. Holding its state under this key is what makes a half-filled form survive a trip to
 * another screen and back. The encoded form is used because the key has to be writable into
 * the saved instance state, which a sealed class is not.
 */
val Screen.stateKey: String
    get() = runCatching { screenFormat.encodeToString(Screen.serializer(), this) }.getOrElse { toString() }

/**
 * Writes the back stack out as one string per entry.
 *
 * A screen that no longer decodes — renamed, or dropped in an update — is left out rather
 * than failing the whole restore, and an empty result falls back to a fresh stack. Coming
 * back to the dashboard is a poor outcome; refusing to start is a worse one.
 */
val NavigatorSaver: Saver<Navigator, Any> = listSaver(
    save = { navigator ->
        navigator.entries.mapNotNull {
            runCatching { screenFormat.encodeToString(Screen.serializer(), it) }.getOrNull()
        }
    },
    restore = { encoded ->
        encoded
            .mapNotNull { runCatching { screenFormat.decodeFromString(Screen.serializer(), it) }.getOrNull() }
            .takeIf { it.isNotEmpty() }
            ?.let(::Navigator)
    },
)

@Composable
fun rememberNavigator(initial: Screen = Screen.Dashboard): Navigator =
    rememberSaveable(saver = NavigatorSaver) { Navigator(initial) }
