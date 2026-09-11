package lux.uno.anima.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.model.BillingPeriod
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.model.currentPrice
import lux.uno.anima.finance.model.daysUntilRenewal
import lux.uno.anima.finance.model.daysUntilTrialEnds
import lux.uno.anima.finance.model.format
import lux.uno.anima.finance.model.monthlyCost
import lux.uno.anima.finance.model.nextRenewal
import lux.uno.anima.finance.model.priceChangeSinceStart
import lux.uno.anima.finance.model.resolve
import lux.uno.anima.finance.model.yearlyCost
import lux.uno.anima.resources.*
import lux.uno.anima.ui.components.AnimaCard
import lux.uno.anima.ui.components.AnimaTextField
import lux.uno.anima.ui.components.AnimaTopBar
import lux.uno.anima.ui.components.DateField
import lux.uno.anima.ui.components.GhostButton
import lux.uno.anima.ui.components.Hairline
import lux.uno.anima.ui.components.IconAction
import lux.uno.anima.ui.components.LabelledValue
import lux.uno.anima.ui.components.Pill
import lux.uno.anima.ui.components.PrimaryButton
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.components.SectionHeader
import lux.uno.anima.ui.finance.components.PriceHistoryChart
import lux.uno.anima.ui.icons.SubscriptionIcon
import lux.uno.anima.ui.text.displayLabel
import lux.uno.anima.ui.text.formatLong
import lux.uno.anima.ui.text.formatShort
import lux.uno.anima.ui.text.label
import lux.uno.anima.ui.text.relativeDayLabel
import lux.uno.anima.ui.text.shortSuffix
import lux.uno.anima.ui.theme.AnimaIcons
import lux.uno.anima.ui.theme.AnimaTheme
import lux.uno.anima.ui.theme.EyebrowStyle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import androidx.compose.runtime.saveable.rememberSaveable
import lux.uno.anima.ui.components.LocalDateSaver
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SubscriptionDetailScreen(
    subscription: Subscription,
    data: FinanceData,
    today: LocalDate,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePaused: () -> Unit,
    onEndTrial: () -> Unit,
    onRecordPrice: (Money, LocalDate, String?) -> Unit,
    onDeletePricePoint: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(subscription.colorArgb)
    val method = data.paymentMethods.firstOrNull { it.id == subscription.paymentMethodId }
    val category = data.categories.resolve(subscription.categoryId)
    val history = remember(subscription) { subscription.history }

    var showPriceDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    ScreenSurface(modifier) { contentModifier ->
        Column(contentModifier.safeDrawingPadding().fillMaxSize()) {
            AnimaTopBar(
                title = subscription.name,
                onBack = onBack,
                actions = {
                    IconAction(
                        icon = if (subscription.isActive) AnimaIcons.Pause else AnimaIcons.Play,
                        contentDescription = stringResource(
                            if (subscription.isActive) Res.string.detail_pause
                            else Res.string.detail_resume,
                        ),
                        onClick = onTogglePaused,
                    )
                    IconAction(AnimaIcons.Pencil, stringResource(Res.string.common_edit), onEdit)
                    IconAction(
                        icon = AnimaIcons.Trash,
                        contentDescription = stringResource(Res.string.common_delete),
                        onClick = { showDeleteDialog = true },
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HeaderCard(subscription, category, today, accent)

                if (subscription.trial != null) {
                    TrialPanel(subscription, today, onEndTrial)
                }

                Spacer(Modifier.height(4.dp))
                SectionHeader(stringResource(Res.string.detail_price_history)) {
                    GhostButton(
                        label = stringResource(Res.string.detail_record_change),
                        onClick = { showPriceDialog = true },
                        icon = AnimaIcons.TrendUp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                AnimaCard(contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PriceChangeHeadline(subscription, today, accent)
                        PriceHistoryChart(history = history, today = today, accent = accent)
                        if (history.size > 1) {
                            Hairline()
                            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                history.reversed().forEachIndexed { index, point ->
                                    val previous = history.getOrNull(history.size - index - 2)
                                    PriceHistoryRow(
                                        point = point,
                                        previous = previous,
                                        today = today,
                                        canDelete = history.size > 1,
                                        onDelete = { onDeletePricePoint(point.effectiveFrom) },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                SectionHeader(stringResource(Res.string.detail_details))
                AnimaCard(contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            LabelledValue(
                                label = stringResource(Res.string.detail_next_charge),
                                value = subscription.nextRenewal(today).formatShort(today),
                                modifier = Modifier.weight(1f),
                            )
                            LabelledValue(
                                label = stringResource(Res.string.detail_that_is),
                                value = relativeDayLabel(subscription.daysUntilRenewal(today))
                                    .replaceFirstChar { it.uppercase() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(Modifier.fillMaxWidth()) {
                            LabelledValue(
                                label = stringResource(Res.string.detail_billing),
                                value = subscription.cycle.label(),
                                modifier = Modifier.weight(1f),
                            )
                            LabelledValue(
                                label = stringResource(Res.string.detail_tracking_since),
                                value = (history.firstOrNull()?.effectiveFrom ?: subscription.anchorDate)
                                    .formatShort(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(Modifier.fillMaxWidth()) {
                            LabelledValue(
                                label = stringResource(Res.string.detail_paid_with),
                                value = method?.displayName
                                    ?: stringResource(Res.string.common_not_set),
                                modifier = Modifier.weight(1f),
                                valueColor = if (method == null) AnimaTheme.palette.faint
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            LabelledValue(
                                label = stringResource(Res.string.detail_category),
                                value = category.displayLabel(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                if (subscription.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    SectionHeader(stringResource(Res.string.detail_notes))
                    AnimaCard(contentPadding = PaddingValues(16.dp)) {
                        Text(
                            text = subscription.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AnimaTheme.palette.muted,
                        )
                    }
                }
            }
        }
    }

    if (showPriceDialog) {
        RecordPriceDialog(
            currency = subscription.currency,
            today = today,
            currentPrice = subscription.currentPrice(today),
            onDismiss = { showPriceDialog = false },
            onConfirm = { price, date, note ->
                onRecordPrice(price, date, note)
                showPriceDialog = false
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(stringResource(Res.string.detail_delete_title, subscription.name))
            },
            text = { Text(stringResource(Res.string.detail_delete_body)) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text(
                        stringResource(Res.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(Res.string.common_keep))
                }
            },
        )
    }
}

@Composable
private fun HeaderCard(
    subscription: Subscription,
    category: Category,
    today: LocalDate,
    accent: Color,
) {
    val price = subscription.currentPrice(today)
    val isMonthly = subscription.cycle.period == BillingPeriod.MONTH && subscription.cycle.interval == 1

    AnimaCard(contentPadding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubscriptionIcon(subscription.icon, accent, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(subscription.name, style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(
                            text = category.displayLabel(),
                            accent = Color(category.colorArgb),
                            leading = category.glyph,
                        )
                        if (!subscription.isActive) {
                            Pill(stringResource(Res.string.sub_paused), accent = AnimaTheme.palette.faint)
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(price.format(), style = MaterialTheme.typography.displaySmall)
                Text(
                    text = subscription.cycle.shortSuffix(),
                    style = MaterialTheme.typography.titleMedium,
                    color = AnimaTheme.palette.muted,
                    modifier = Modifier.padding(bottom = 4.dp, start = 2.dp),
                )
            }

            Text(
                text = if (isMonthly) {
                    stringResource(Res.string.detail_yearly, subscription.yearlyCost(today).format())
                } else {
                    stringResource(
                        Res.string.detail_monthly_and_yearly,
                        subscription.monthlyCost(today).format(),
                        subscription.yearlyCost(today).format(),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AnimaTheme.palette.muted,
            )
        }
    }
}

/** "Up 28.6% since March 2024" — the one sentence the price history is kept for. */
@Composable
private fun PriceChangeHeadline(subscription: Subscription, today: LocalDate, accent: Color) {
    val change = subscription.priceChangeSinceStart(today)
    val first = subscription.history.firstOrNull()

    if (change == null || first == null) {
        Text(
            text = stringResource(Res.string.detail_one_price),
            style = MaterialTheme.typography.bodyMedium,
            color = AnimaTheme.palette.muted,
        )
        return
    }

    val percent = (abs(change) * 1000).roundToInt() / 10.0
    val rising = change > 0
    val color = when {
        change == 0.0 -> AnimaTheme.palette.muted
        rising -> AnimaTheme.palette.rise
        else -> AnimaTheme.palette.fall
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = when {
                change == 0.0 -> stringResource(Res.string.detail_same_price)
                // The sign and the percent sign are built here rather than in the string,
                // so no translation has to carry a bare "%" through its own formatter.
                rising -> stringResource(Res.string.detail_up_since, "$percent%")
                else -> stringResource(Res.string.detail_down_since, "$percent%")
            },
            style = MaterialTheme.typography.titleSmall,
            color = color,
        )
        Text(
            text = stringResource(
                Res.string.detail_price_from_to,
                first.price.format(),
                first.effectiveFrom.formatShort(),
                subscription.currentPrice(today).format(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.muted,
        )
    }
}

@Composable
private fun PriceHistoryRow(
    point: PricePoint,
    previous: PricePoint?,
    today: LocalDate,
    canDelete: Boolean,
    onDelete: () -> Unit,
) {
    val delta = previous?.let { point.price.minor - it.price.minor }
    val deltaColor = when {
        delta == null || delta == 0L -> AnimaTheme.palette.faint
        delta > 0 -> AnimaTheme.palette.rise
        else -> AnimaTheme.palette.fall
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = point.effectiveFrom.formatLong(),
                style = MaterialTheme.typography.bodyMedium,
            )
            val note = point.note
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = AnimaTheme.palette.faint,
                )
            } else if (point.effectiveFrom > today) {
                Text(
                    text = stringResource(
                        Res.string.detail_takes_effect,
                        relativeDayLabel(today.daysUntil(point.effectiveFrom)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = AnimaTheme.palette.warning,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(point.price.format(), style = MaterialTheme.typography.titleSmall)
            if (delta != null && delta != 0L) {
                Text(
                    text = (if (delta > 0) "+" else "−") + Money(abs(delta), point.price.currency).format(),
                    style = MaterialTheme.typography.bodySmall,
                    color = deltaColor,
                )
            }
        }
        if (canDelete) {
            Spacer(Modifier.width(4.dp))
            IconAction(
                icon = AnimaIcons.Close,
                contentDescription = stringResource(Res.string.detail_remove_price_point),
                onClick = onDelete,
                tint = AnimaTheme.palette.faint,
            )
        }
    }
}

@Composable
private fun TrialPanel(subscription: Subscription, today: LocalDate, onEndTrial: () -> Unit) {
    val trial = subscription.trial ?: return
    val days = subscription.daysUntilTrialEnds(today) ?: return
    val warning = AnimaTheme.palette.warning

    AnimaCard(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.detail_trial), style = EyebrowStyle, color = warning)
            Text(
                text = when {
                    days < 0 -> stringResource(Res.string.detail_trial_ended, relativeDayLabel(days))
                    days == 0 -> stringResource(Res.string.detail_trial_ends_today)
                    else -> stringResource(
                        Res.string.detail_trial_ends_on,
                        relativeDayLabel(days),
                        trial.endsOn.formatLong(),
                    )
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = pluralStringResource(
                    Res.plurals.detail_trial_reminder,
                    trial.remindDaysBefore,
                    trial.remindDaysBefore,
                    subscription.currentPrice(today).format(),
                    subscription.cycle.shortSuffix(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AnimaTheme.palette.muted,
            )
            GhostButton(
                label = stringResource(Res.string.detail_end_trial),
                onClick = onEndTrial,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Adding a price change is a one-field job, so it stays in a dialog rather than a screen. */
@Composable
private fun RecordPriceDialog(
    currency: String,
    today: LocalDate,
    currentPrice: Money,
    onDismiss: () -> Unit,
    onConfirm: (Money, LocalDate, String?) -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf(formatAmountForInput(currentPrice)) }
    var date by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(today) }
    var note by rememberSaveable { mutableStateOf("") }

    val parsed = amount.toAmountMinorOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.detail_record_price_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AnimaTextField(
                    value = amount,
                    onValueChange = { amount = it.filterAmountInput() },
                    label = stringResource(Res.string.detail_new_price),
                    placeholder = stringResource(Res.string.detail_price_placeholder),
                    keyboardType = KeyboardType.Decimal,
                    prefix = currency,
                )
                DateField(
                    label = stringResource(Res.string.detail_effective_from),
                    date = date,
                    onDateChange = { date = it },
                )
                AnimaTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = stringResource(Res.string.detail_why),
                    placeholder = stringResource(Res.string.detail_why_placeholder),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let { onConfirm(Money(it, currency), date, note.ifBlank { null }) } },
            ) { Text(stringResource(Res.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/** Keeps the amount field to digits and a single separator. */
internal fun String.filterAmountInput(): String {
    val cleaned = filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')
    val firstDot = cleaned.indexOf('.')
    if (firstDot < 0) return cleaned
    val head = cleaned.substring(0, firstDot + 1)
    val tail = cleaned.substring(firstDot + 1).filter { it.isDigit() }.take(2)
    return head + tail
}

internal fun String.toAmountMinorOrNull(): Long? {
    val value = trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (value < 0) return null
    return (value * 100).roundToLong()
}

internal fun formatAmountForInput(money: Money): String {
    val whole = money.minor / 100
    val cents = (money.minor % 100).toString().padStart(2, '0')
    return "$whole.$cents"
}
