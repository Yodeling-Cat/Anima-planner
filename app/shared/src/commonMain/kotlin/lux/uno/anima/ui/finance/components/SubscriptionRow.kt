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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.model.currentPrice
import lux.uno.anima.finance.model.daysUntilRenewal
import lux.uno.anima.finance.model.daysUntilTrialEnds
import lux.uno.anima.finance.model.format
import lux.uno.anima.finance.model.nextRenewal
import lux.uno.anima.resources.*
import lux.uno.anima.ui.components.AnimaCard
import lux.uno.anima.ui.components.Pill
import lux.uno.anima.ui.icons.SubscriptionIcon
import lux.uno.anima.ui.text.formatShort
import lux.uno.anima.ui.text.relativeDayLabel
import lux.uno.anima.ui.text.shortSuffix
import lux.uno.anima.ui.text.shown
import lux.uno.anima.ui.text.shownBare
import lux.uno.anima.ui.theme.AnimaTheme
import org.jetbrains.compose.resources.stringResource

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
    /** True where a pane beside this list is showing this subscription. */
    selected: Boolean = false,
) {
    val accent = Color(subscription.colorArgb)
    val trialDays = subscription.daysUntilTrialEnds(today)

    AnimaCard(
        modifier = modifier.fillMaxWidth().alpha(if (subscription.isActive) 1f else 0.55f),
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        // The row wears the subscription's own colour rather than one selection accent:
        // the detail pane beside it is already full of that colour, and matching them is
        // what ties the two panes together without drawing a line between them.
        color = if (selected) {
            accent.copy(alpha = if (AnimaTheme.palette.isDark) 0.16f else 0.10f)
                .compositeOver(AnimaTheme.palette.elevated)
        } else {
            AnimaTheme.palette.elevated
        },
        borderColor = if (selected) accent.copy(alpha = 0.55f) else AnimaTheme.palette.hairline,
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
                    text = if (!subscription.isActive) {
                        stringResource(Res.string.sub_paused)
                    } else {
                        stringResource(
                            Res.string.sub_next_renewal,
                            subscription.nextRenewal(today).formatShort(today),
                            relativeDayLabel(subscription.daysUntilRenewal(today)),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AnimaTheme.palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trialDays != null && trialDays >= 0) {
                    Spacer(Modifier.padding(top = 1.dp))
                    Pill(
                        text = if (trialDays == 0) {
                            stringResource(Res.string.sub_trial_ends_today)
                        } else {
                            stringResource(Res.string.sub_trial_ends_in, relativeDayLabel(trialDays))
                        },
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
                        text = subscription.currentPrice(today).shown(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = subscription.cycle.shortSuffix(),
                        style = MaterialTheme.typography.bodySmall,
                        color = AnimaTheme.palette.faint,
                    )
                }
                // Only shown when the cycle is not already monthly, where it would repeat itself.
                if (monthlyEquivalent != null) {
                    Text(
                        text = stringResource(
                            Res.string.sub_monthly_equivalent,
                            monthlyEquivalent.shownBare(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = AnimaTheme.palette.faint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
