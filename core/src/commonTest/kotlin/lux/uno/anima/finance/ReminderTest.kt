package lux.uno.anima.finance

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.model.CardExpiry
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.PaymentMethodKind
import lux.uno.anima.finance.model.cardsNeedingAttention
import lux.uno.anima.finance.model.hasExpired
import lux.uno.anima.finance.model.toDigits
import lux.uno.anima.finance.reminders.Reminder
import lux.uno.anima.finance.reminders.InMemoryHandledReminderStore
import lux.uno.anima.finance.reminders.ReminderCoordinator
import lux.uno.anima.finance.reminders.ReminderKind
import lux.uno.anima.finance.reminders.ReminderNotifier
import lux.uno.anima.finance.reminders.buildReminders
import lux.uno.anima.finance.store.InMemoryFinanceStore
import lux.uno.anima.sync.HybridClock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardExpiryTest {

    @Test
    fun aCardLastsToTheEndOfItsPrintedMonth() {
        assertEquals(LocalDate(2026, 9, 30), CardExpiry(2026, 9).lastValidDay)
        assertEquals(LocalDate(2026, 2, 28), CardExpiry(2026, 2).lastValidDay)
        // A leap year has to give the extra day.
        assertEquals(LocalDate(2028, 2, 29), CardExpiry(2028, 2).lastValidDay)
        assertEquals(LocalDate(2026, 12, 31), CardExpiry(2026, 12).lastValidDay)
    }

    @Test
    fun itIsStillValidOnTheLastDayOfTheMonth() {
        val expiry = CardExpiry(2026, 9)
        assertFalse(expiry.hasExpired(LocalDate(2026, 9, 30)))
        assertTrue(expiry.hasExpired(LocalDate(2026, 10, 1)))
    }

    @Test
    fun theEntryFieldRoundTrips() {
        assertEquals(CardExpiry(2026, 9), CardExpiry.fromDigits("0926"))
        assertEquals("0926", CardExpiry(2026, 9).toDigits())
        assertEquals("09/26", CardExpiry(2026, 9).label)
    }

    @Test
    fun partialOrImpossibleEntriesAreRejected() {
        assertNull(CardExpiry.fromDigits(""))
        assertNull(CardExpiry.fromDigits("09"))
        assertNull(CardExpiry.fromDigits("0926x"))
        assertNull(CardExpiry.fromDigits("1326"))
        assertNull(CardExpiry.fromDigits("0026"))
    }

    @Test
    fun onlyCardsWithinTheWarningWindowAreFlagged() {
        val card = card(CardExpiry(2026, 9))
        val methods = listOf(card)

        assertTrue(methods.cardsNeedingAttention(LocalDate(2026, 8, 29)).isEmpty())
        assertEquals(listOf(card), methods.cardsNeedingAttention(LocalDate(2026, 8, 30)))
        assertEquals(listOf(card), methods.cardsNeedingAttention(LocalDate(2026, 9, 30)))
        // Still flagged after it dies, because a dead card is the louder problem.
        assertEquals(listOf(card), methods.cardsNeedingAttention(LocalDate(2027, 1, 1)))
    }

    @Test
    fun expiryOnAnythingOtherThanACardIsIgnored() {
        val account = PaymentMethod(
            id = "pm-bank",
            name = "Main account",
            kind = PaymentMethodKind.BANK,
            expiry = CardExpiry(2026, 9),
        )
        assertTrue(listOf(account).cardsNeedingAttention(LocalDate(2026, 9, 30)).isEmpty())
        assertTrue(buildReminders(FinanceData(paymentMethods = listOf(account))).isEmpty())
    }
}

class ReminderScheduleTest {

    @Test
    fun aCardProducesAWarningAndAnExpiryDayReminder() {
        val reminders = buildReminders(FinanceData(paymentMethods = listOf(card(CardExpiry(2026, 9)))))

        assertEquals(2, reminders.size)
        assertEquals(ReminderKind.CARD_EXPIRING_SOON, reminders[0].kind)
        assertEquals(LocalDate(2026, 8, 30), reminders[0].date)
        assertEquals(ReminderKind.CARD_EXPIRES_TODAY, reminders[1].kind)
        assertEquals(LocalDate(2026, 9, 30), reminders[1].date)
    }

    @Test
    fun aCardWithNoExpiryProducesNothing() {
        assertTrue(buildReminders(FinanceData(paymentMethods = listOf(card(expiry = null)))).isEmpty())
    }

    @Test
    fun changingTheExpiryChangesTheIdsSoTheRemindersReArm() {
        val before = buildReminders(FinanceData(paymentMethods = listOf(card(CardExpiry(2026, 9)))))
        val after = buildReminders(FinanceData(paymentMethods = listOf(card(CardExpiry(2029, 9)))))

        assertTrue(before.map { it.id }.intersect(after.map { it.id }.toSet()).isEmpty())
    }
}

class ReminderCoordinatorTest {

    private val expiry = CardExpiry(2026, 9)
    private val warnDate = LocalDate(2026, 8, 30)
    private val expiryDate = LocalDate(2026, 9, 30)

    @Test
    fun overdueRemindersAreShownOnceAndNeverAgain() = runTest {
        val notifier = FakeNotifier(deliversWhenClosed = false)
        val repository = repositoryWithCard()
        val coordinator = ReminderCoordinator(notifier, repository)

        coordinator.sync(today = LocalDate(2026, 9, 11))
        assertContentEquals(listOf(warnDate), notifier.delivered.map { it.date })

        coordinator.sync(today = LocalDate(2026, 9, 12))
        assertContentEquals(listOf(warnDate), notifier.delivered.map { it.date })
    }

    @Test
    fun futureRemindersAreHandedToThePlatform() = runTest {
        val notifier = FakeNotifier(deliversWhenClosed = true)
        val coordinator = ReminderCoordinator(notifier, repositoryWithCard())

        coordinator.sync(today = LocalDate(2026, 9, 11))

        assertContentEquals(listOf(expiryDate), notifier.lastScheduled.map { it.date })
    }

    @Test
    fun aPhoneDoesNotRepeatWhatTheSystemAlreadyDelivered() = runTest {
        val notifier = FakeNotifier(deliversWhenClosed = true)
        val coordinator = ReminderCoordinator(notifier, repositoryWithCard())

        coordinator.sync(today = LocalDate(2026, 9, 11))
        notifier.delivered.clear()

        // The OS fired the expiry-day alarm while the app was closed; opening it later must
        // not show the same thing a second time.
        coordinator.sync(today = LocalDate(2026, 10, 2))
        assertTrue(notifier.delivered.isEmpty())
    }

    @Test
    fun aDesktopLeftOpenShowsTheExpiryDayReminderWhenTheDayArrives() = runTest {
        val notifier = FakeNotifier(deliversWhenClosed = false)
        val coordinator = ReminderCoordinator(notifier, repositoryWithCard())

        coordinator.sync(today = LocalDate(2026, 9, 11))
        notifier.delivered.clear()

        coordinator.sync(today = expiryDate)
        assertContentEquals(listOf(expiryDate), notifier.delivered.map { it.date })
    }

    @Test
    fun deletingTheCardClearsItsHandledIds() = runTest {
        val notifier = FakeNotifier(deliversWhenClosed = true)
        val repository = repositoryWithCard()
        val handled = InMemoryHandledReminderStore()
        val coordinator = ReminderCoordinator(notifier, repository, handled)

        coordinator.sync(today = LocalDate(2026, 9, 11))
        assertEquals(2, handled.load().size)

        repository.deletePaymentMethod("pm-visa")
        coordinator.sync(today = LocalDate(2026, 9, 11))
        assertTrue(handled.load().isEmpty())
    }

    @Test
    fun handledIdsAreNotPartOfTheDataset() = runTest {
        // The whole point of moving them out: delivering a notification is something this
        // device did, and it must not read as the user editing their data.
        val notifier = FakeNotifier(deliversWhenClosed = true)
        val repository = repositoryWithCard()
        val before = repository.data.value

        ReminderCoordinator(notifier, repository).sync(today = LocalDate(2026, 9, 11))

        assertEquals(before, repository.data.value)
    }

    @Test
    fun handledIdsSurviveARestart() = runTest {
        val repository = repositoryWithCard()
        val handled = InMemoryHandledReminderStore()
        ReminderCoordinator(FakeNotifier(deliversWhenClosed = true), repository, handled)
            .sync(today = LocalDate(2026, 9, 11))

        // A second coordinator is what the next launch builds.
        val notifier = FakeNotifier(deliversWhenClosed = true)
        ReminderCoordinator(notifier, repository, handled).sync(today = expiryDate)

        assertTrue(notifier.delivered.isEmpty(), "already delivered before the restart")
    }

    private suspend fun TestScope.repositoryWithCard(): FinanceRepository {
        val repository = FinanceRepository(
            store = InMemoryFinanceStore(FinanceData(paymentMethods = listOf(card(expiry)))),
            scope = this,
            clock = HybridClock("test-device"),
        )
        repository.load()
        return repository
    }
}

private fun card(expiry: CardExpiry?) = PaymentMethod(
    id = "pm-visa",
    name = "Personal Visa",
    kind = PaymentMethodKind.CARD,
    last4 = "4417",
    expiry = expiry,
)

private class FakeNotifier(override val deliversWhenClosed: Boolean) : ReminderNotifier {
    val delivered = mutableListOf<Reminder>()
    var lastScheduled: List<Reminder> = emptyList()

    override suspend fun permissionGranted(): Boolean = true
    override suspend fun requestPermission(): Boolean = true
    override suspend fun schedule(reminders: List<Reminder>) {
        lastScheduled = reminders
    }

    override suspend fun deliverNow(reminder: Reminder) {
        delivered += reminder
    }
}
