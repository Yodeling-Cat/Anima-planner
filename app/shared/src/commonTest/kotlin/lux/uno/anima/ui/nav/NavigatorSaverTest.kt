package lux.uno.anima.ui.nav

import androidx.compose.runtime.saveable.SaverScope
import lux.uno.anima.ui.dashboard.LifeArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android destroys and rebuilds the activity on every rotation, so a back stack held only
 * in memory is gone. These cover the round trip that keeps it.
 */
class NavigatorSaverTest {

    /** Nothing here refuses to save, so the permissive scope is the honest stand-in. */
    private val scope = SaverScope { true }

    private fun roundTrip(navigator: Navigator): Navigator? {
        val saved = assertNotNull(with(NavigatorSaver) { scope.save(navigator) })
        return NavigatorSaver.restore(saved)
    }

    @Test
    fun `a stack several screens deep comes back whole`() {
        val navigator = Navigator()
        navigator.go(Screen.Finance)
        navigator.go(Screen.SubscriptionDetail("sub-1"))
        navigator.go(Screen.SubscriptionEditor("sub-1"))

        val restored = assertNotNull(roundTrip(navigator))

        assertEquals(navigator.entries, restored.entries)
        assertEquals(Screen.SubscriptionEditor("sub-1"), restored.current)
    }

    @Test
    fun `backing out of a restored stack goes where it did before`() {
        val navigator = Navigator()
        navigator.go(Screen.Finance)
        navigator.go(Screen.SubscriptionDetail("sub-1"))

        val restored = assertNotNull(roundTrip(navigator))
        assertTrue(restored.back())

        assertEquals(Screen.Finance, restored.current)
        assertTrue(restored.canGoBack)
    }

    @Test
    fun `screen arguments survive`() {
        // The ids are the whole reason this cannot just save a depth count.
        val navigator = Navigator()
        navigator.go(Screen.ComingSoon(LifeArea.HEALTH))

        val restored = assertNotNull(roundTrip(navigator))

        assertEquals(Screen.ComingSoon(LifeArea.HEALTH), restored.current)
    }

    @Test
    fun `an editor opened for a new record stays a new record`() {
        // SubscriptionEditor(null) and SubscriptionEditor("id") drive different screens, so
        // a null argument has to survive as null rather than becoming absent.
        val navigator = Navigator()
        navigator.go(Screen.SubscriptionEditor(null))

        val restored = assertNotNull(roundTrip(navigator))

        assertEquals(Screen.SubscriptionEditor(null), restored.current)
        assertNull((restored.current as Screen.SubscriptionEditor).id)
    }

    @Test
    fun `the dashboard alone round trips`() {
        val restored = assertNotNull(roundTrip(Navigator()))

        assertEquals(Screen.Dashboard, restored.current)
        assertTrue(!restored.canGoBack, "the root must not gain a phantom entry")
    }

    @Test
    fun `a screen that no longer decodes is dropped, not fatal`() {
        // Saved state outlives the code that wrote it: an update can rename or remove a
        // screen while the system still holds a bundle describing it.
        val restored = NavigatorSaver.restore(
            listOf("""{"type":"lux.uno.anima.ui.nav.Screen.Dashboard"}""", "not json at all"),
        )

        assertEquals(Screen.Dashboard, assertNotNull(restored).current)
    }

    @Test
    fun `nothing decodable means start fresh rather than crash`() {
        assertNull(NavigatorSaver.restore(listOf("junk", "more junk")))
    }
}
