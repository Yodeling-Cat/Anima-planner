package lux.uno.anima.finance.reminders

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

actual fun createReminderNotifier(): ReminderNotifier = DesktopReminderNotifier()

/**
 * Desktop notifications go through the system tray, which only exists while the app is
 * running. Nothing can be handed to the OS for later, so [deliversWhenClosed] is false and
 * the coordinator shows anything that fell due while the app was shut.
 */
private class DesktopReminderNotifier : ReminderNotifier {

    override val deliversWhenClosed: Boolean = false

    private val trayIcon: TrayIcon? by lazy { installTrayIcon() }

    override suspend fun permissionGranted(): Boolean = withContext(Dispatchers.IO) {
        trayIcon != null
    }

    /** Nothing to ask for — the tray is either available or it is not. */
    override suspend fun requestPermission(): Boolean = permissionGranted()

    /** The OS holds no schedule for us; [ReminderCoordinator] revisits these on its own. */
    override suspend fun schedule(reminders: List<Reminder>) = Unit

    override suspend fun deliverNow(reminder: Reminder): Unit = withContext(Dispatchers.IO) {
        trayIcon?.displayMessage(reminder.title, reminder.body, TrayIcon.MessageType.INFO)
        Unit
    }

    private fun installTrayIcon(): TrayIcon? = runCatching {
        if (System.getProperty("java.awt.headless") == "true") return@runCatching null
        if (!SystemTray.isSupported()) return@runCatching null

        val tray = SystemTray.getSystemTray()
        val existing = tray.trayIcons.firstOrNull { it.toolTip == TOOLTIP }
        if (existing != null) return@runCatching existing

        val icon = TrayIcon(renderIcon(tray.trayIconSize.width.coerceAtLeast(16)), TOOLTIP).apply {
            isImageAutoSize = true
        }
        tray.add(icon)
        icon
    }.getOrNull()

    /** A drawn mark rather than a bundled asset, so the tray needs no resource pipeline. */
    private fun renderIcon(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x2E, 0x6F, 0x5E)
        g.fillOval(0, 0, size - 1, size - 1)
        g.color = Color.WHITE
        g.fillOval(size / 3, size / 3, size / 3, size / 3)
        g.dispose()
        return image
    }

    private companion object {
        const val TOOLTIP = "Anima"
    }
}
