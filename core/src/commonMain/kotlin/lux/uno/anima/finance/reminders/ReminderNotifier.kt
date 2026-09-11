package lux.uno.anima.finance.reminders

/**
 * The platform's notification machinery, reduced to what reminders need.
 *
 * Two very different shapes hide behind this: phones hand the whole schedule to the OS and
 * get notifications even when the app is dead, while the desktop can only speak while it is
 * running. [deliversWhenClosed] is how [ReminderCoordinator] tells them apart.
 */
interface ReminderNotifier {

    /** True when the OS keeps the schedule for us once [schedule] has run. */
    val deliversWhenClosed: Boolean

    /** Whether notifications will actually appear right now. */
    suspend fun permissionGranted(): Boolean

    /**
     * Asks for permission if the platform can do so without an Activity. Returns the state
     * afterwards; platforms that must ask from the UI simply report what they already have.
     */
    suspend fun requestPermission(): Boolean

    /** Replaces the entire pending schedule with [reminders]. All dates are in the future. */
    suspend fun schedule(reminders: List<Reminder>)

    /** Shows one reminder immediately. */
    suspend fun deliverNow(reminder: Reminder)
}

expect fun createReminderNotifier(): ReminderNotifier

/** Used in tests and on any platform where notifications are not wired up. */
object NoOpReminderNotifier : ReminderNotifier {
    override val deliversWhenClosed: Boolean = false
    override suspend fun permissionGranted(): Boolean = false
    override suspend fun requestPermission(): Boolean = false
    override suspend fun schedule(reminders: List<Reminder>) = Unit
    override suspend fun deliverNow(reminder: Reminder) = Unit
}
