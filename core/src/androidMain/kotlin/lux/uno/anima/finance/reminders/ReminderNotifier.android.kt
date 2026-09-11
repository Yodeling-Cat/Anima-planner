package lux.uno.anima.finance.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import lux.uno.anima.platform.AndroidPlatform
import kotlin.time.ExperimentalTime

actual fun createReminderNotifier(): ReminderNotifier = AndroidReminderNotifier()

/**
 * Hands the schedule to `AlarmManager`, so reminders arrive whether or not the app is
 * running. The set of scheduled ids is kept in preferences purely so stale alarms can be
 * cancelled when the data behind them changes.
 */
private class AndroidReminderNotifier : ReminderNotifier {

    override val deliversWhenClosed: Boolean = true

    private val context: Context get() = AndroidPlatform.requireContext()

    private val preferences
        get() = context.getSharedPreferences("anima-reminders", Context.MODE_PRIVATE)

    override suspend fun permissionGranted(): Boolean = withContext(Dispatchers.Default) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return@withContext false
        if (!manager.areNotificationsEnabled()) return@withContext false

        // From API 33 the user also has to grant POST_NOTIFICATIONS, which only an Activity
        // can ask for; MainActivity does that at startup.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext true
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** The runtime prompt needs an Activity, so this only reports the current state. */
    override suspend fun requestPermission(): Boolean = permissionGranted()

    @OptIn(ExperimentalTime::class)
    override suspend fun schedule(reminders: List<Reminder>): Unit = withContext(Dispatchers.Default) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return@withContext
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.let(::ensureChannel)

        val previous = preferences.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        val wanted = reminders.mapTo(mutableSetOf()) { it.id }

        // Drop alarms whose reminder has changed or gone away before arming the new set.
        (previous - wanted).forEach { staleId ->
            alarms.cancel(pendingIntentFor(staleId, title = null, body = null))
        }

        val zone = TimeZone.currentSystemDefault()
        reminders.forEach { reminder ->
            val at = LocalDateTime(reminder.date, LocalTime(reminder.hour, 0))
                .toInstant(zone)
                .toEpochMilliseconds()
            val intent = pendingIntentFor(reminder.id, reminder.title, reminder.body)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Not "exact": a day-level reminder does not justify the exact-alarm
                // permission, and doze will still release it in a maintenance window.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                alarms.set(AlarmManager.RTC_WAKEUP, at, intent)
            }
        }

        preferences.edit().putStringSet(KEY_SCHEDULED, wanted).apply()
    }

    override suspend fun deliverNow(reminder: Reminder): Unit = withContext(Dispatchers.Default) {
        ReminderReceiver().onReceive(
            context,
            Intent(context, ReminderReceiver::class.java)
                .putExtra(EXTRA_REMINDER_ID, reminder.id)
                .putExtra(EXTRA_TITLE, reminder.title)
                .putExtra(EXTRA_BODY, reminder.body),
        )
    }

    private fun pendingIntentFor(id: String, title: String?, body: String?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction("$ACTION_PREFIX$id")
            .putExtra(EXTRA_REMINDER_ID, id)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_BODY, body)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val KEY_SCHEDULED = "scheduled_ids"
        const val ACTION_PREFIX = "lux.uno.anima.REMINDER."
    }
}
