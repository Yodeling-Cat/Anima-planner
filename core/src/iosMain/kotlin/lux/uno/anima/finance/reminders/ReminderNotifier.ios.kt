package lux.uno.anima.finance.reminders

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.number
import platform.Foundation.NSDateComponents
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

actual fun createReminderNotifier(): ReminderNotifier = IosReminderNotifier()

/**
 * Local notifications through `UNUserNotificationCenter`. iOS keeps the schedule itself, so
 * reminders arrive with the app closed and the whole pending set is simply replaced on sync.
 */
private class IosReminderNotifier : ReminderNotifier {

    override val deliversWhenClosed: Boolean = true

    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun permissionGranted(): Boolean = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            continuation.resume(
                status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional,
            )
        }
    }

    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, _ ->
            continuation.resume(granted)
        }
    }

    override suspend fun schedule(reminders: List<Reminder>) {
        // Anima owns nothing else in this centre, so replacing the lot is the simplest way
        // to drop reminders whose card has changed.
        center.removeAllPendingNotificationRequests()
        reminders.forEach { reminder ->
            center.addNotificationRequest(reminder.toRequest(reminder.trigger()), null)
        }
    }

    override suspend fun deliverNow(reminder: Reminder) {
        center.addNotificationRequest(reminder.toRequest(trigger = null), null)
    }
}

private fun Reminder.trigger(): UNNotificationTrigger {
    // Read the reminder's fields up front: inside the apply block the names would resolve
    // against NSDateComponents, which has a `date` of its own.
    val on = date
    val atHour = hour
    val components = NSDateComponents().apply {
        year = on.year.toLong()
        month = on.month.number.toLong()
        day = on.day.toLong()
        hour = atHour.toLong()
        minute = 0
    }
    return UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false)
}

private fun Reminder.toRequest(trigger: UNNotificationTrigger?): UNNotificationRequest {
    val content = UNMutableNotificationContent().apply {
        setTitle(this@toRequest.title)
        setBody(this@toRequest.body)
        setSound(UNNotificationSound.defaultSound)
    }
    return UNNotificationRequest.requestWithIdentifier(id, content, trigger)
}
