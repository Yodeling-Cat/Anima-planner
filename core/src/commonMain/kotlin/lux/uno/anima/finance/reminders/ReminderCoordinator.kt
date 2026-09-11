package lux.uno.anima.finance.reminders

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.FinanceRepository
import lux.uno.anima.finance.today
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Keeps the OS reminder schedule in step with the data.
 *
 * Reminders that already came due while the app was closed are shown on the spot; the rest
 * are handed to the platform. Every id that has been dealt with goes to [handled], which is
 * what stops the same reminder appearing twice.
 */
class ReminderCoordinator(
    private val notifier: ReminderNotifier,
    private val repository: FinanceRepository,
    /** Where the notification wording comes from; see [ReminderStrings]. */
    private val strings: ReminderStrings,
    private val handledStore: HandledReminderStore = InMemoryHandledReminderStore(),
) {
    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    val deliversWhenClosed: Boolean get() = notifier.deliversWhenClosed

    suspend fun requestPermission(): Boolean =
        notifier.requestPermission().also { _permissionGranted.value = it }

    suspend fun sync(today: LocalDate = today()) {
        val data = repository.data.value
        val reminders = buildReminders(data, strings)
        val knownIds = reminders.mapTo(mutableSetOf()) { it.id }
        val handled = handledStore.load()

        val due = reminders.filter { it.date <= today && it.id !in handled }
        val future = reminders.filter { it.date > today }

        _permissionGranted.value = notifier.permissionGranted()

        due.forEach { notifier.deliverNow(it) }
        // Re-armed on every sync so a schedule lost to a reboot or a force-stop comes back.
        notifier.schedule(future)

        // Once a phone holds the future reminders they belong to the OS, and this app must
        // never show them again. The desktop cannot schedule, so it leaves them open and
        // picks them up as "due" on a later pass.
        val nowHandled = buildSet {
            addAll(handled)
            addAll(due.map { it.id })
            if (notifier.deliversWhenClosed) addAll(future.map { it.id })
        }

        // Dropping ids that no longer exist keeps the list from growing forever, and lets a
        // deleted-then-recreated card fire again.
        val next = nowHandled.filterTo(mutableSetOf()) { it in knownIds }
        if (next != handled) handledStore.save(next)
    }

    /**
     * Syncs now, then whenever the payment methods change, and again on every [interval] so a
     * desktop app left open overnight still shows today's reminder.
     */
    suspend fun run(interval: Duration = 1.hours, clock: () -> LocalDate = ::today): Unit =
        coroutineScope {
            launch {
                repository.data
                    .map { it.paymentMethods }
                    .distinctUntilChanged()
                    .collect { sync(clock()) }
            }
            launch {
                while (currentCoroutineContext().isActive) {
                    sync(clock())
                    delay(interval)
                }
            }
        }
}
