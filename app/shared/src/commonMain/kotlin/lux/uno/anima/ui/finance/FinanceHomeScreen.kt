package lux.uno.anima.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import kotlinx.datetime.LocalDate
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.model.BillingPeriod
import lux.uno.anima.finance.model.FinanceOverview
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.cardsNeedingAttention
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.model.UpcomingCharge
import lux.uno.anima.finance.model.buildOverview
import lux.uno.anima.finance.model.currentPrice
import lux.uno.anima.finance.model.daysUntilTrialEnds
import lux.uno.anima.finance.model.format
import lux.uno.anima.finance.model.monthlyCost
import lux.uno.anima.finance.model.nextRenewal
import lux.uno.anima.resources.*
import lux.uno.anima.ui.components.AnimaCard
import lux.uno.anima.ui.components.AnimaTopBar
import lux.uno.anima.ui.components.ChoiceChip
import lux.uno.anima.ui.components.EmptyState
import lux.uno.anima.ui.components.GlyphTile
import lux.uno.anima.ui.components.GhostButton
import lux.uno.anima.ui.components.IconAction
import lux.uno.anima.ui.components.PrimaryButton
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.components.SectionHeader
import lux.uno.anima.ui.finance.components.SubscriptionRow
import lux.uno.anima.ui.finance.components.color
import lux.uno.anima.ui.finance.components.expiryStatus
import lux.uno.anima.ui.icons.SubscriptionIcon
import lux.uno.anima.ui.text.LocalMoneyDisplay
import lux.uno.anima.ui.text.displayLabel
import lux.uno.anima.ui.text.formatShort
import lux.uno.anima.ui.text.shown
import lux.uno.anima.ui.text.relativeDayLabel
import lux.uno.anima.ui.text.shortSuffix
import lux.uno.anima.ui.theme.AnimaIcons
import lux.uno.anima.ui.theme.AnimaTheme
import lux.uno.anima.ui.theme.EyebrowStyle
import androidx.compose.runtime.saveable.rememberSaveable
import lux.uno.anima.ui.components.enumSaver
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How the main list is ordered. Defaults to what bills next, which is what people ask first. */
enum class SubscriptionSort(private val labelRes: StringResource) {
    UPCOMING(Res.string.sort_upcoming),
    COST(Res.string.sort_cost),
    NAME(Res.string.sort_name);

    val label: String
        @Composable
        get() = stringResource(labelRes)
}

@Composable
fun FinanceHomeScreen(
    data: FinanceData,
    today: LocalDate,
    /** Null where a rail is showing, which is the way back to the dashboard instead. */
    onBack: (() -> Unit)?,
    onOpenSubscription: (String) -> Unit,
    onAddSubscription: () -> Unit,
    onOpenPaymentMethods: () -> Unit,
    onOpenCategories: () -> Unit,
    modifier: Modifier = Modifier,
    /** The subscription a pane beside this list is showing, where there is one. */
    selectedId: String? = null,
) {
    var sort by rememberSaveable(stateSaver = enumSaver<SubscriptionSort>()) {
        mutableStateOf(SubscriptionSort.UPCOMING)
    }

    // The same table the rest of the screen's amounts are drawn with. Read from composition
    // rather than taken as a parameter for the reason MoneyDisplay itself is: a screen that
    // has to be handed the rates is a screen that will one day be added without them, and
    // an overview built without them silently reports every currency as unconvertible.
    val rates = LocalMoneyDisplay.current.rates
    val overview = remember(data, today, rates) {
        buildOverview(
            data.subscriptions,
            data.categories,
            today,
            data.settings.preferredCurrency,
            rates,
        )
    }
    val methodsById = remember(data) { data.paymentMethods.associateBy { it.id } }

    val active = remember(data, today, sort) {
        data.subscriptions.filter { it.isActive }.sortedWith(
            when (sort) {
                SubscriptionSort.UPCOMING -> compareBy { it.nextRenewal(today) }
                SubscriptionSort.COST -> compareByDescending { it.monthlyCost(today).minor }
                SubscriptionSort.NAME -> compareBy { it.name.lowercase() }
            },
        )
    }
    val paused = remember(data) { data.subscriptions.filterNot { it.isActive } }
    val cardAlerts = remember(data, today) { data.paymentMethods.cardsNeedingAttention(today) }

    ScreenSurface(modifier) { contentModifier ->
        Box(contentModifier.safeDrawingPadding().fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AnimaTopBar(
                    title = stringResource(Res.string.finance_title),
                    onBack = onBack,
                    actions = {
                        IconAction(
                            AnimaIcons.Sliders,
                            stringResource(Res.string.finance_categories),
                            onOpenCategories,
                        )
                        IconAction(
                            AnimaIcons.Card,
                            stringResource(Res.string.finance_payment_methods),
                            onOpenPaymentMethods,
                        )
                    },
                )

                if (data.subscriptions.isEmpty()) {
                    EmptyState(
                        glyph = "🪙",
                        title = stringResource(Res.string.finance_empty_title),
                        body = stringResource(Res.string.finance_empty_body),
                        action = {
                            PrimaryButton(
                                stringResource(Res.string.finance_empty_action),
                                onAddSubscription,
                                icon = AnimaIcons.Plus,
                            )
                        },
                    )
                    return@Column
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { SummaryCard(overview) }

                    if (cardAlerts.isNotEmpty()) {
                        item { Spacer(Modifier.height(6.dp)) }
                        item { SectionHeader(stringResource(Res.string.finance_section_cards)) }
                        items(cardAlerts, key = { "card-${it.id}" }) { method ->
                            CardExpiryCard(method, today, onOpenPaymentMethods)
                        }
                    }

                    if (overview.trialsEndingSoon.isNotEmpty()) {
                        item { Spacer(Modifier.height(6.dp)) }
                        item { SectionHeader(stringResource(Res.string.finance_section_trials)) }
                        items(overview.trialsEndingSoon, key = { "trial-${it.id}" }) { subscription ->
                            TrialCard(subscription, today) { onOpenSubscription(subscription.id) }
                        }
                    }

                    if (overview.upcoming.isNotEmpty()) {
                        item { Spacer(Modifier.height(6.dp)) }
                        item { SectionHeader(stringResource(Res.string.finance_section_coming_up)) }
                        item {
                            AnimaCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                                Column {
                                    overview.upcoming.take(4).forEach { charge ->
                                        UpcomingRow(charge, today) { onOpenSubscription(charge.subscription.id) }
                                    }
                                }
                            }
                        }
                    }

                    if (overview.categories.size > 1) {
                        item { Spacer(Modifier.height(6.dp)) }
                        item { SectionHeader(stringResource(Res.string.finance_section_where_it_goes)) }
                        item { CategoryBreakdown(overview.categories.take(5)) }
                    }

                    item { Spacer(Modifier.height(6.dp)) }
                    item {
                        SectionHeader(stringResource(Res.string.finance_section_subscriptions)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SubscriptionSort.entries.forEach { option ->
                                    ChoiceChip(
                                        label = option.label,
                                        selected = sort == option,
                                        onClick = { sort = option },
                                    )
                                }
                            }
                        }
                    }

                    items(active, key = { it.id }) { subscription ->
                        SubscriptionRow(
                            subscription = subscription,
                            today = today,
                            paymentMethod = methodsById[subscription.paymentMethodId],
                            monthlyEquivalent = subscription.monthlyEquivalentOrNull(today),
                            onClick = { onOpenSubscription(subscription.id) },
                            selected = subscription.id == selectedId,
                        )
                    }

                    if (paused.isNotEmpty()) {
                        item { Spacer(Modifier.height(6.dp)) }
                        item { SectionHeader(stringResource(Res.string.finance_section_paused)) }
                        items(paused, key = { "paused-${it.id}" }) { subscription ->
                            SubscriptionRow(
                                subscription = subscription,
                                today = today,
                                paymentMethod = methodsById[subscription.paymentMethodId],
                                monthlyEquivalent = null,
                                onClick = { onOpenSubscription(subscription.id) },
                                selected = subscription.id == selectedId,
                            )
                        }
                    }
                }
            }

            if (data.subscriptions.isNotEmpty()) {
                AddButton(
                    onClick = onAddSubscription,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                )
            }
        }
    }
}

/**
 * The monthly figure is only interesting next to the yearly one, so both are always shown.
 *
 * One currency, always. Where subscriptions are billed in several, they are folded into the
 * preferred one and the card says so — a row of per-currency chips left the reader to add up
 * three numbers in their head, which is the arithmetic this card exists to do for them.
 */
@Composable
private fun SummaryCard(overview: FinanceOverview) {
    val primary = overview.primaryTotal
    // The headline currency's own count, not every active subscription: the ones in a
    // currency the total could not reach are not part of the amount above it.
    val activeCount = primary?.subscriptionCount ?: 0

    AnimaCard(contentPadding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(Res.string.finance_monthly_outflow),
                style = EyebrowStyle,
                color = AnimaTheme.palette.faint,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = primary?.monthly?.format() ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            Text(
                text = pluralStringResource(
                    Res.plurals.finance_active_count,
                    activeCount,
                    activeCount,
                ).let { count ->
                    primary?.let {
                        stringResource(Res.string.finance_total_line, it.yearly.format(), count)
                    } ?: count
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AnimaTheme.palette.muted,
            )

            // Spend the total could not reach. Said plainly rather than left out, because a
            // month's outflow quietly missing a subscription is the one error here nobody
            // would catch by looking.
            if (overview.unconvertedCurrencies.isNotEmpty()) {
                Text(
                    stringResource(
                        Res.string.finance_unconverted_note,
                        overview.unconvertedCurrencies.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = AnimaTheme.palette.warning,
                )
            }
        }
    }
}

/** An expiring or dead card, sitting with the trials because both are deadlines. */
@Composable
private fun CardExpiryCard(method: PaymentMethod, today: LocalDate, onClick: () -> Unit) {
    val status = method.expiryStatus(today) ?: return
    val accent = status.severity.color()

    AnimaCard(onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphTile(method.kind.glyph, Color(method.colorArgb), size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(method.displayName, style = MaterialTheme.typography.titleSmall)
                Text(status.full, style = MaterialTheme.typography.bodySmall, color = accent)
            }
            Text(
                text = method.expiry?.label.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = AnimaTheme.palette.muted,
            )
        }
    }
}

@Composable
private fun TrialCard(subscription: Subscription, today: LocalDate, onClick: () -> Unit) {
    val days = subscription.daysUntilTrialEnds(today) ?: return
    val urgent = days <= (subscription.trial?.remindDaysBefore ?: 3)
    val accent = if (urgent) AnimaTheme.palette.warning else AnimaTheme.palette.muted

    AnimaCard(onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubscriptionIcon(subscription.icon, Color(subscription.colorArgb), size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(subscription.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when (days) {
                        0 -> stringResource(Res.string.finance_trial_ends_today)
                        1 -> stringResource(Res.string.finance_trial_ends_tomorrow)
                        else -> stringResource(
                            Res.string.finance_trial_ends_in,
                            relativeDayLabel(days),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                )
            }
            Text(
                text = stringResource(
                    Res.string.finance_then_price,
                    subscription.currentPrice(today).shown(),
                    subscription.cycle.shortSuffix(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AnimaTheme.palette.muted,
            )
        }
    }
}

@Composable
private fun UpcomingRow(charge: UpcomingCharge, today: LocalDate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubscriptionIcon(
            icon = charge.subscription.icon,
            accent = Color(charge.subscription.colorArgb),
            size = 24.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = charge.subscription.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = charge.date.formatShort(today),
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.faint,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = charge.amount.shown(),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun CategoryBreakdown(slices: List<lux.uno.anima.finance.model.CategorySlice>) {
    AnimaCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            slices.forEach { slice ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val categoryColor = Color(slice.category.colorArgb)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(slice.category.glyph, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = slice.category.displayLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = slice.monthly.format(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AnimaTheme.palette.muted,
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(AnimaTheme.palette.hairline),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(slice.share.toFloat().coerceIn(0.02f, 1f))
                                .height(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(categoryColor),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            AnimaIcons.Plus,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(Res.string.finance_add),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Monthly equivalent, or null when the cycle is already monthly and the figure would repeat. */
private fun Subscription.monthlyEquivalentOrNull(today: LocalDate): Money? =
    if (cycle.period == BillingPeriod.MONTH && cycle.interval == 1) null else monthlyCost(today)
