package lux.uno.anima.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [relativeDay] is where the app decides how coarsely it talks about time, so the
 * interesting cases are the boundaries where one phrasing takes over from the next.
 */
class RelativeDayTest {

    @Test
    fun theThreeNamedDaysHaveNoNumber() {
        assertEquals(RelativeDay.Today, relativeDay(0))
        assertEquals(RelativeDay.Tomorrow, relativeDay(1))
        assertEquals(RelativeDay.Yesterday, relativeDay(-1))
    }

    @Test
    fun daysStayDaysUpToAWeek() {
        assertEquals(RelativeDay.InDays(2), relativeDay(2))
        assertEquals(RelativeDay.InDays(6), relativeDay(6))
    }

    @Test
    fun theSecondWeekIsStillOneWeekAway() {
        // 7..13 days out all round down to a single week rather than reporting "in 1 week"
        // for seven days and "in 1 week" again for thirteen by accident.
        assertEquals(RelativeDay.InWeeks(1), relativeDay(7))
        assertEquals(RelativeDay.InWeeks(1), relativeDay(13))
    }

    @Test
    fun weeksTakeOverUntilTwoMonths() {
        assertEquals(RelativeDay.InWeeks(2), relativeDay(14))
        assertEquals(RelativeDay.InWeeks(8), relativeDay(59))
    }

    @Test
    fun monthsTakeOverAtSixty() {
        assertEquals(RelativeDay.InMonths(2), relativeDay(60))
        assertEquals(RelativeDay.InMonths(12), relativeDay(365))
    }

    @Test
    fun thePastMirrorsTheFutureButRoundsSooner() {
        assertEquals(RelativeDay.DaysAgo(6), relativeDay(-6))
        assertEquals(RelativeDay.WeeksAgo(1), relativeDay(-7))
        assertEquals(RelativeDay.WeeksAgo(4), relativeDay(-29))
        assertEquals(RelativeDay.MonthsAgo(1), relativeDay(-30))
        assertEquals(RelativeDay.MonthsAgo(12), relativeDay(-365))
    }

    @Test
    fun everyCountedFormReportsAtLeastOne() {
        // A label reading "in 0 weeks" would be worse than the day count it replaced.
        (-800..800).forEach { days ->
            val count = when (val relative = relativeDay(days)) {
                is RelativeDay.InDays -> relative.days
                is RelativeDay.InWeeks -> relative.weeks
                is RelativeDay.InMonths -> relative.months
                is RelativeDay.DaysAgo -> relative.days
                is RelativeDay.WeeksAgo -> relative.weeks
                is RelativeDay.MonthsAgo -> relative.months
                else -> 1
            }
            assertTrue(count >= 1, "relativeDay($days) counted $count")
        }
    }

    @Test
    fun theSignAloneDecidesPastOrFuture() {
        (1..800).forEach { days ->
            val future = relativeDay(days)
            val past = relativeDay(-days)
            val futureIsPast = future is RelativeDay.Yesterday || future is RelativeDay.DaysAgo ||
                future is RelativeDay.WeeksAgo || future is RelativeDay.MonthsAgo
            val pastIsFuture = past is RelativeDay.Tomorrow || past is RelativeDay.InDays ||
                past is RelativeDay.InWeeks || past is RelativeDay.InMonths
            assertTrue(!futureIsPast, "relativeDay($days) landed in the past")
            assertTrue(!pastIsFuture, "relativeDay(-$days) landed in the future")
        }
    }
}
