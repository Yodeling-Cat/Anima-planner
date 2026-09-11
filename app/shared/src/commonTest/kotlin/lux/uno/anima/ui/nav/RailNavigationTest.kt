package lux.uno.anima.ui.nav

import androidx.compose.ui.unit.dp
import lux.uno.anima.ui.dashboard.LifeArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rail is a second way to reach a screen, and the risk of a second way in is a second
 * idea of where you have been. These cover the stack the rail leaves behind it.
 */
class RailNavigationTest {

    @Test
    fun `a rail jump leaves the section it came from behind`() {
        val navigator = Navigator()
        navigator.go(Screen.Finance)
        navigator.go(Screen.SubscriptionDetail("sub-1"))

        navigator.switchTo(Screen.ComingSoon(LifeArea.HEALTH))

        // Not Finance and not the subscription: Back from a section means the way home.
        assertEquals(listOf(Screen.Dashboard, Screen.ComingSoon(LifeArea.HEALTH)), navigator.entries)
    }

    @Test
    fun `back still walks home from a section the rail opened`() {
        val navigator = Navigator()

        navigator.switchTo(Screen.Finance)

        assertTrue(navigator.canGoBack)
        assertTrue(navigator.back())
        assertEquals(Screen.Dashboard, navigator.current)
    }

    @Test
    fun `the home item unwinds to the root rather than stacking another dashboard`() {
        val navigator = Navigator()
        navigator.go(Screen.Finance)
        navigator.go(Screen.SubscriptionEditor())

        navigator.switchTo(Screen.Dashboard)

        assertEquals(listOf(Screen.Dashboard), navigator.entries)
        assertFalse(navigator.canGoBack, "the root must not gain a second entry")
    }

    @Test
    fun `arriving where you already are changes nothing`() {
        val navigator = Navigator()
        navigator.switchTo(Screen.Finance)
        navigator.go(Screen.SubscriptionDetail("sub-1"))

        navigator.switchTo(Screen.Finance)

        assertEquals(listOf(Screen.Dashboard, Screen.Finance), navigator.entries)
    }

    @Test
    fun `every screen inside finance marks finance on the rail`() {
        val inside = listOf(
            Screen.Finance,
            Screen.PaymentMethods,
            Screen.Categories,
            Screen.SubscriptionDetail("sub-1"),
            Screen.SubscriptionEditor("sub-1"),
        )

        inside.forEach { assertEquals(LifeArea.FINANCE, it.area, it.toString()) }
    }

    @Test
    fun `the dashboard and the account belong to no area`() {
        assertNull(Screen.Dashboard.area)
        assertNull(Screen.Account.area)
    }

    @Test
    fun `a section list keeps the pane beside it but a top-level screen does not`() {
        assertEquals(Screen.Finance, Screen.SubscriptionDetail("sub-1").listPane)
        assertEquals(Screen.Finance, Screen.Categories.listPane)
        assertNull(Screen.Dashboard.listPane)
        assertNull(Screen.Account.listPane)
        assertNull(Screen.Finance.listPane)
    }

    @Test
    fun `width decides the layout and nothing else does`() {
        assertEquals(WindowClass.Compact, WindowClass.of(420.dp))
        assertEquals(WindowClass.Compact, WindowClass.of(699.dp))
        assertEquals(WindowClass.Medium, WindowClass.of(700.dp))
        assertEquals(WindowClass.Medium, WindowClass.of(1099.dp))
        assertEquals(WindowClass.Expanded, WindowClass.of(1100.dp))

        assertFalse(WindowClass.Compact.showsRail)
        assertTrue(WindowClass.Medium.showsRail)
        assertFalse(WindowClass.Medium.showsTwoPanes)
        assertTrue(WindowClass.Expanded.showsTwoPanes)
    }
}
