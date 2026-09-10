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
import lux.uno.anima.finance.formatShort
import lux.uno.anima.finance.model.BillingPeriod
import lux.uno.anima.finance.model.CurrencyTotal
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.model.UpcomingCharge
import lux.uno.anima.finance.model.buildOverview
import lux.uno.anima.finance.model.currentPrice
import lux.uno.anima.finance.model.daysUntilTrialEnds
import lux.uno.anima.finance.model.format
import lux.uno.anima.finance.model.monthlyCost
import lux.uno.anima.finance.model.nextRenewal
import lux.uno.anima.finance.relativeDayLabel
import lux.uno.anima.ui.components.AnimaCard
import lux.uno.anima.ui.components.AnimaTopBar
import lux.uno.anima.ui.components.ChoiceChip
import lux.uno.anima.ui.components.EmptyState
import lux.uno.anima.ui.components.GhostButton
import lux.uno.anima.ui.components.HorizontalScrollRow
import lux.uno.anima.ui.components.IconAction
import lux.uno.anima.ui.components.Pill
import lux.uno.anima.ui.components.PrimaryButton
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.components.SectionHeader
import lux.uno.anima.ui.finance.components.SubscriptionRow
import lux.uno.anima.ui.icons.SubscriptionIcon
import lux.uno.anima.ui.theme.AnimaIcons
import lux.uno.anima.ui.theme.AnimaTheme
import lux.uno.anima.ui.theme.EyebrowStyle

/** How the main list is ordered. Defaults to what bills next, which is what people ask first. */
enum class SubscriptionSort(val label: String) {
    UPCOMING("Upcoming"),
    COST("Cost"),
    NAME("Name"),
}

@Composable
fun FinanceHomeScreen(
    data: FinanceData,
    today: LocalDate,
    onBack: () -> Unit,
    onOpenSubscription: (String) -> Unit,
    onAddSubscription: () -> Unit,
    onOpenPaymentMethods: () -> Unit,
    onOpenCategories: () -> Unit,
    onLoadSample: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sort by remember { mutableStateOf(SubscriptionSort.UPCOMING) }

    val overview = remember(data, today) {
        buildOverview(data.subscriptions, data.categories, today, data.settings.preferredCurrency)
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

    ScreenSurface(modifier) { contentModifier ->
        Box(contentModifier.safeDrawingPadding().fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AnimaTopBar(
                    title = "Finance",
                    onBack = onBack,
                    actions = {
                        IconAction(AnimaIcons.Sliders, "Categories", onOpenCategories)
                        IconAction(AnimaIcons.Card, "Payment methods", onOpenPaymentMethods)
                    },
                )

                if (data.subscriptions.isEmpty()) {
                    EmptyState(
                        glyph = "🪙",
                        title = "Nothing tracked yet",
                        body = "Add the things that quietly bill you every month, and this screen "
                            + "starts telling you where the money goes.",
                        action = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                PrimaryButton("Add a subscription", onAddSubscription, icon = AnimaIcons.Plus)
                                GhostButton("Fill it with sample data", onLoadSample)
                            }
                        },
                    )
                    return@Column
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { SummaryCard(overview.primaryTotal, overview.totals, overview.activeCount) }

                    if (overview.trialsEndingSoon.isNotEmpty()) {
                        item { Spacer(Modifier.height(6.dp)) }
                        item { SectionHeader("Trials") }
                        items(overview.trialsEndingSoon, key = { "trial-${it.id}" }) { subscription ->
                            TrialCard(subscription, today) { onOpenSubscription(subscription.id) }
                        }
                    }

                    if (overview.upcoming.isNotEmpty()) {
                        item { Spacer(Modifier.height(6.dp)) }
                        item { SectionHeader("Coming up") }
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
                        item { SectionHeader("Where it goes") }
                        item { CategoryBreakdown(overview.categories.take(5)) }
                    }

                    item { Spacer(Modifier.height(6.dp)) }
                    item {
                        SectionHeader("Subscriptions") {
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
                        )
                    }

                    if (paused.isNotEmpty()) {
                        item { Spacer(Modifier.height(6.dp)) }
                        item { SectionHeader("Paused") }
                        items(paused, key = { "paused-${it.id}" }) { subscription ->
                            SubscriptionRow(
                                subscription = subscription,
                                today = today,
                                paymentMethod = methodsById[subscription.paymentMethodId],
                                monthlyEquivalent = null,
                                onClick = { onOpenSubscription(subscription.id) },
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

/** The monthly figure is only interesting next to the yearly one, so both are always shown. */
@Composable
private fun SummaryCard(
    primary: CurrencyTotal?,
    all: List<CurrencyTotal>,
    activeCount: Int,
) {
    AnimaCard(contentPadding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MONTHLY OUTFLOW", style = EyebrowStyle, color = AnimaTheme.palette.faint)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = primary?.monthly?.format() ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            Text(
                text = buildString {
                    if (primary != null) append("${primary.yearly.format()} a year · ")
                    append(activeCount)
                    append(if (activeCount == 1) " active subscription" else " active subscriptions")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AnimaTheme.palette.muted,
            )

            val others = all.filter { it.currency != primary?.currency }
            if (others.isNotEmpty()) {
                HorizontalScrollRow {
                    others.forEach { total ->
                        Pill("${total.monthly.format()}/mo", accent = AnimaTheme.palette.muted)
                    }
                }
            }
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
                    text = when {
                        days == 0 -> "Free trial ends today"
                        days == 1 -> "Free trial ends tomorrow"
                        else -> "Free trial ends ${relativeDayLabel(days)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                )
            }
            Text(
                text = "then ${subscription.currentPrice(today).format()}${subscription.cycle.shortSuffix}",
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
            text = charge.amount.format(),
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
                            text = slice.category.label,
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
            "Add",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Monthly equivalent, or null when the cycle is already monthly and the figure would repeat. */
private fun Subscription.monthlyEquivalentOrNull(today: LocalDate): Money? =
    if (cycle.period == BillingPeriod.MONTH && cycle.interval == 1) null else monthlyCost(today)
