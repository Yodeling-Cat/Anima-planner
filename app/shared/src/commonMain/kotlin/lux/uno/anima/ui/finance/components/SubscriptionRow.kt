package lux.uno.anima.ui.finance.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.formatShort
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.model.currentPrice
import lux.uno.anima.finance.model.daysUntilRenewal
import lux.uno.anima.finance.model.daysUntilTrialEnds
import lux.uno.anima.finance.model.format
import lux.uno.anima.finance.model.nextRenewal
import lux.uno.anima.finance.relativeDayLabel
import lux.uno.anima.ui.components.AnimaCard
import lux.uno.anima.ui.components.Pill
import lux.uno.anima.ui.icons.SubscriptionIcon
import lux.uno.anima.ui.theme.AnimaTheme

/**
 * One subscription as it appears in a list: icon, name, when it next bills, what it costs.
 * The trailing price is always the real charge, with the normalised monthly figure below it
 * whenever the cycle is not monthly.
 */
@Composable
fun SubscriptionRow(
    subscription: Subscription,
    today: LocalDate,
    paymentMethod: PaymentMethod?,
    monthlyEquivalent: Money?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(subscription.colorArgb)
    val trialDays = subscription.daysUntilTrialEnds(today)

    AnimaCard(
        modifier = modifier.fillMaxWidth().alpha(if (subscription.isActive) 1f else 0.55f),
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubscriptionIcon(subscription.icon, accent)
            Spacer(Modifier.width(13.dp))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = subscription.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        !subscription.isActive -> "Paused"
                        else -> {
                            val days = subscription.daysUntilRenewal(today)
                            "${subscription.nextRenewal(today).formatShort(today)} · ${relativeDayLabel(days)}"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AnimaTheme.palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trialDays != null && trialDays >= 0) {
                    Spacer(Modifier.padding(top = 1.dp))
                    Pill(
                        text = if (trialDays == 0) "Trial ends today" else "Trial · ${relativeDayLabel(trialDays)}",
                        accent = AnimaTheme.palette.warning,
                    )
                } else if (paymentMethod != null) {
                    Spacer(Modifier.padding(top = 1.dp))
                    Pill(
                        text = paymentMethod.displayName,
                        accent = Color(paymentMethod.colorArgb),
                        leading = paymentMethod.kind.glyph,
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = subscription.currentPrice(today).format(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = subscription.cycle.shortSuffix,
                        style = MaterialTheme.typography.bodySmall,
                        color = AnimaTheme.palette.faint,
                    )
                }
                // Only shown when the cycle is not already monthly, where it would repeat itself.
                if (monthlyEquivalent != null) {
                    Text(
                        text = "≈ ${monthlyEquivalent.format()}/mo",
                        style = MaterialTheme.typography.bodySmall,
                        color = AnimaTheme.palette.faint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
