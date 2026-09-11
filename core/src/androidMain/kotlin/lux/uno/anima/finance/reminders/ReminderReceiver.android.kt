package lux.uno.anima.finance.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

internal const val REMINDER_CHANNEL_ID = "anima-reminders"
internal const val EXTRA_REMINDER_ID = "reminder_id"
internal const val EXTRA_TITLE = "title"
internal const val EXTRA_BODY = "body"

/**
 * Woken by the alarm manager at the scheduled moment. It carries everything it needs in the
 * intent, so it never has to open the dataset from a cold process.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: title

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        ensureChannel(context, manager)

        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, REMINDER_CHANNEL_ID)
        } else {
            android.app.Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(context.reminderIconRes())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()

        manager.notify(reminderId.hashCode(), notification)
    }
}

internal fun ensureChannel(context: Context, manager: NotificationManager) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (manager.getNotificationChannel(REMINDER_CHANNEL_ID) != null) return
    manager.createNotificationChannel(
        NotificationChannel(
            REMINDER_CHANNEL_ID,
            context.reminderString("anima_reminder_channel_name", "Reminders"),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.reminderString(
                "anima_reminder_channel_description",
                "Card expiry and other dated reminders",
            )
        },
    )
}

/** Resolved by name so this module needs no generated R class of its own. */
internal fun Context.reminderIconRes(): Int =
    resources.getIdentifier("anima_reminder", "drawable", packageName)
        .takeIf { it != 0 }
        ?: android.R.drawable.ic_dialog_info

/**
 * Resolved by name for the same reason as the icon above.
 *
 * The channel is the one string Android reads rather than the app, so it cannot come from
 * Compose’s resources — it is shown in the system settings, where there is no composition.
 */
private fun Context.reminderString(name: String, fallback: String): String =
    resources.getIdentifier(name, "string", packageName)
        .takeIf { it != 0 }
        ?.let(::getString)
        ?: fallback
