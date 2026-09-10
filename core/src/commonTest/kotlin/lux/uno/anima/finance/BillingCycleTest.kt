package lux.uno.anima.finance

import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.model.BillingCycle
import lux.uno.anima.finance.model.BillingPeriod
import lux.uno.anima.finance.model.advance
import lux.uno.anima.finance.model.nextOccurrence
import lux.uno.anima.finance.model.previousOccurrence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillingCycleTest {

    private val monthly = BillingCycle.Monthly
    private val quarterly = BillingCycle.Quarterly
    private val yearly = BillingCycle.Yearly

    @Test
    fun anchorInTheFutureIsItsOwnNextOccurrence() {
        val anchor = LocalDate(2026, 12, 1)
        assertEquals(anchor, monthly.nextOccurrence(anchor, LocalDate(2026, 9, 10)))
    }

    @Test
    fun anchorOnTodayCountsAsToday() {
        val today = LocalDate(2026, 9, 10)
        assertEquals(today, monthly.nextOccurrence(today, today))
    }

    @Test
    fun monthlyRollsForwardAcrossYearBoundary() {
        val anchor = LocalDate(2025, 11, 20)
        assertEquals(
            LocalDate(2026, 9, 20),
            monthly.nextOccurrence(anchor, LocalDate(2026, 9, 10)),
        )
    }

    @Test
    fun quarterlyLandsOnMultiplesOfThreeMonths() {
        val anchor = LocalDate(2025, 1, 15)
        val next = quarterly.nextOccurrence(anchor, LocalDate(2026, 9, 10))
        assertEquals(LocalDate(2026, 10, 15), next)
    }

    @Test
    fun yearlyKeepsTheSameDayOfMonth() {
        val anchor = LocalDate(2020, 3, 4)
        assertEquals(
            LocalDate(2027, 3, 4),
            yearly.nextOccurrence(anchor, LocalDate(2026, 9, 10)),
        )
    }

    @Test
    fun weeklyRepeatsEverySevenDays() {
        val weekly = BillingCycle.Weekly
        val anchor = LocalDate(2026, 9, 1)
        val next = weekly.nextOccurrence(anchor, LocalDate(2026, 9, 10))
        assertEquals(LocalDate(2026, 9, 15), next)
    }

    @Test
    fun fortnightlyUsesTheInterval() {
        val fortnightly = BillingCycle(BillingPeriod.WEEK, 2)
        val anchor = LocalDate(2026, 9, 1)
        assertEquals(LocalDate(2026, 9, 29), fortnightly.nextOccurrence(anchor, LocalDate(2026, 9, 16)))
    }

    @Test
    fun nextOccurrenceIsNeverBeforeTheReferenceDay() {
        val anchor = LocalDate(2019, 1, 31)
        val today = LocalDate(2026, 9, 10)
        listOf(monthly, quarterly, yearly, BillingCycle.Weekly).forEach { cycle ->
            assertTrue(cycle.nextOccurrence(anchor, today) >= today, "failed for ${cycle.label}")
        }
    }

    @Test
    fun previousOccurrenceSitsOneCycleBack() {
        val anchor = LocalDate(2025, 1, 15)
        val today = LocalDate(2026, 9, 10)
        val previous = monthly.previousOccurrence(anchor, today)
        assertEquals(LocalDate(2026, 8, 15), previous)
    }

    @Test
    fun previousOccurrenceIsNullBeforeTheFirstCharge() {
        val anchor = LocalDate(2027, 1, 1)
        assertNull(monthly.previousOccurrence(anchor, LocalDate(2026, 9, 10)))
    }

    @Test
    fun advanceMatchesRepeatedSteps() {
        val anchor = LocalDate(2026, 1, 31)
        assertEquals(quarterly.advance(anchor, 4), yearly.advance(anchor, 1))
    }

    @Test
    fun normalisationPutsEveryCycleOnOneAxis() {
        assertEquals(1.0, monthly.cyclesPerMonth)
        assertEquals(12.0, monthly.cyclesPerYear)
        assertEquals(1.0, yearly.cyclesPerYear)
        assertTrue(BillingCycle.Weekly.cyclesPerMonth > 4.0)
        assertTrue(BillingCycle.Weekly.cyclesPerMonth < 4.5)
    }
}
