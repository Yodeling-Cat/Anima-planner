package lux.uno.anima.finance

import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.model.CardExpiry
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.PaymentMethodKind
import lux.uno.anima.finance.model.cardsNeedingAttention
import lux.uno.anima.finance.model.daysUntilExpiry
import lux.uno.anima.finance.model.expiryNeedsAttention
import lux.uno.anima.finance.model.hasExpired
import lux.uno.anima.finance.model.toDigits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A card lives through the whole of its printed month, so every boundary here is about
 * the difference between the month on the card and the day it actually stops working.
 */
class CardExpiryTest {

    private fun card(expiry: CardExpiry?, id: String = "pm") = PaymentMethod(
        id = id,
        name = "Card",
        kind = PaymentMethodKind.CARD,
        expiry = expiry,
    )

    @Test
    fun lastValidDayIsTheEndOfThePrintedMonth() {
        assertEquals(LocalDate(2026, 9, 30), CardExpiry(2026, 9).lastValidDay)
        assertEquals(LocalDate(2026, 12, 31), CardExpiry(2026, 12).lastValidDay)
    }

    @Test
    fun februaryFollowsTheLeapYear() {
        assertEquals(LocalDate(2027, 2, 28), CardExpiry(2027, 2).lastValidDay)
        assertEquals(LocalDate(2028, 2, 29), CardExpiry(2028, 2).lastValidDay)
    }

    @Test
    fun labelIsTheTwoDigitFormPrintedOnTheCard() {
        assertEquals("09/26", CardExpiry(2026, 9).label)
        assertEquals("12/07", CardExpiry(2007, 12).label)
    }

    @Test
    fun anImpossibleMonthIsRefusedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { CardExpiry(2026, 0) }
        assertFailsWith<IllegalArgumentException> { CardExpiry(2026, 13) }
    }

    @Test
    fun digitsRoundTrip() {
        listOf(CardExpiry(2026, 9), CardExpiry(2031, 12), CardExpiry(2000, 1)).forEach {
            assertEquals(it, CardExpiry.fromDigits(it.toDigits()))
        }
    }

    @Test
    fun anIncompleteOrImpossibleEntryParsesToNothing() {
        // The editor calls this on every keystroke, so a half-typed field is the normal case.
        listOf("", "0", "09", "092", "09266", "ab26", "09/6", "1326", "0026").forEach {
            assertNull(CardExpiry.fromDigits(it), "expected null for \"$it\"")
        }
    }

    @Test
    fun aCardWorksThroughItsFinalDayAndNotAfter() {
        val expiry = CardExpiry(2026, 9)
        assertFalse(expiry.hasExpired(LocalDate(2026, 9, 30)))
        assertTrue(expiry.hasExpired(LocalDate(2026, 10, 1)))
    }

    @Test
    fun daysUntilExpiryIsZeroOnTheLastGoodDayAndNegativeAfter() {
        val expiry = CardExpiry(2026, 9)
        assertEquals(0, expiry.daysUntilExpiry(LocalDate(2026, 9, 30)))
        assertEquals(1, expiry.daysUntilExpiry(LocalDate(2026, 9, 29)))
        assertEquals(-1, expiry.daysUntilExpiry(LocalDate(2026, 10, 1)))
    }

    @Test
    fun attentionStartsAMonthBeforeTheEnd() {
        val subject = card(CardExpiry(2026, 9))
        assertFalse(subject.expiryNeedsAttention(LocalDate(2026, 8, 29)))
        assertTrue(subject.expiryNeedsAttention(LocalDate(2026, 8, 30)))
        assertTrue(subject.expiryNeedsAttention(LocalDate(2026, 9, 30)))
    }

    @Test
    fun theWarningWindowIsAdjustable() {
        val subject = card(CardExpiry(2026, 9))
        assertFalse(subject.expiryNeedsAttention(LocalDate(2026, 6, 29), warningMonths = 3))
        assertTrue(subject.expiryNeedsAttention(LocalDate(2026, 6, 30), warningMonths = 3))
    }

    @Test
    fun attentionNeverLapsesOnceTheCardIsDead() {
        val subject = card(CardExpiry(2020, 1))
        assertTrue(subject.expiryNeedsAttention(LocalDate(2026, 9, 10)))
    }

    @Test
    fun onlyCardsTrackExpiryAtAll() {
        val bank = card(CardExpiry(2020, 1)).copy(kind = PaymentMethodKind.BANK)
        assertFalse(bank.tracksExpiry)
        assertFalse(bank.expiryNeedsAttention(LocalDate(2026, 9, 10)))
    }

    @Test
    fun aCardWithNoExpiryNeverAsksForAttention() {
        assertFalse(card(expiry = null).expiryNeedsAttention(LocalDate(2026, 9, 10)))
    }

    @Test
    fun theListPutsTheMostUrgentCardFirst() {
        val today = LocalDate(2026, 9, 10)
        val dead = card(CardExpiry(2024, 3), id = "dead")
        val soon = card(CardExpiry(2026, 9), id = "soon")
        val later = card(CardExpiry(2029, 1), id = "later")
        val account = card(CardExpiry(2024, 1), id = "bank").copy(kind = PaymentMethodKind.BANK)

        val needing = listOf(later, soon, dead, account).cardsNeedingAttention(today)

        assertEquals(listOf("dead", "soon"), needing.map { it.id })
    }

    @Test
    fun displayNameShowsTheLastFourOnlyWhenThereIsOne() {
        assertEquals("Card ·· 4417", card(null).copy(last4 = "4417").displayName)
        assertEquals("Card", card(null).copy(last4 = " ").displayName)
        assertEquals("Card", card(null).displayName)
    }
}
