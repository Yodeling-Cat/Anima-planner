package lux.uno.anima.ui.nav

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room the window has, in the only three amounts the app does anything different
 * with.
 *
 * Measured from the window rather than asked of the platform: a desktop window dragged
 * narrow is a phone-shaped space and should be laid out like one, and an Android tablet
 * folded shut is too. Nothing here may ask which OS it is running on.
 */
enum class WindowClass {
    /** A phone, or a desktop window kept narrow. One screen at a time, back stack only. */
    Compact,

    /** Room for the rail beside a single screen. */
    Medium,

    /** Room for the rail, a list and whatever the list opened, side by side. */
    Expanded;

    /** Whether the rail replaces the back arrow as the way between sections. */
    val showsRail: Boolean get() = this != Compact

    /** Whether a section's list stays on screen while one of its entries is open. */
    val showsTwoPanes: Boolean get() = this == Expanded

    companion object {
        /**
         * The two widths the layout changes at.
         *
         * [RailWidth] is where a rail stops stealing room the content needed. [PaneWidth] is
         * where the list and the detail can both have their natural width — below it one of
         * them would be squeezed, and a cramped two-pane layout is worse than a roomy one.
         */
        private val RailWidth = 700.dp
        private val PaneWidth = 1100.dp

        fun of(width: Dp): WindowClass = when {
            width >= PaneWidth -> Expanded
            width >= RailWidth -> Medium
            else -> Compact
        }
    }
}
