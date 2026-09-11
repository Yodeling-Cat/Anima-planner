package lux.uno.anima.ui.finance.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.formatLong
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.daysUntilExpiry
import lux.uno.anima.finance.model.hasExpired
import lux.uno.anima.finance.relativeDayLabel
import lux.uno.anima.ui.theme.AnimaTheme

enum class ExpirySeverity { CALM, WARNING, EXPIRED }

/** How a card's expiry should read and how loudly, wherever it is shown. */
data class ExpiryStatus(
    val short: String,
    val full: String,
    val severity: ExpirySeverity,
)

/** Null for anything that is not a card with an expiry on it. */
fun PaymentMethod.expiryStatus(today: LocalDate): ExpiryStatus? {
    val expiry = expiry?.takeIf { tracksExpiry } ?: return null
    val days = expiry.daysUntilExpiry(today)

    return when {
        expiry.hasExpired(today) -> ExpiryStatus(
            short = "Expired ${expiry.label}",
            full = "Expired on ${expiry.lastValidDay.formatLong()}",
            severity = ExpirySeverity.EXPIRED,
        )
        days == 0 -> ExpiryStatus(
            short = "Expires today",
            full = "Today is the last day this card works",
            severity = ExpirySeverity.WARNING,
        )
        // Matches the reminder window, so the screen goes loud exactly when the first
        // notification fires rather than on some second, unrelated threshold.
        days <= 31 -> ExpiryStatus(
            short = "Expires ${relativeDayLabel(days)}",
            full = "Expires ${expiry.label}, ${relativeDayLabel(days)}",
            severity = ExpirySeverity.WARNING,
        )
        else -> ExpiryStatus(
            short = "Expires ${expiry.label}",
            full = "Expires ${expiry.label}",
            severity = ExpirySeverity.CALM,
        )
    }
}

@Composable
@ReadOnlyComposable
fun ExpirySeverity.color(): Color = when (this) {
    ExpirySeverity.CALM -> AnimaTheme.palette.muted
    ExpirySeverity.WARNING -> AnimaTheme.palette.warning
    ExpirySeverity.EXPIRED -> MaterialTheme.colorScheme.error
}
