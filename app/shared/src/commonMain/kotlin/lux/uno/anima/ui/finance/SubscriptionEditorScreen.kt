package lux.uno.anima.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.model.BillingCycle
import lux.uno.anima.finance.model.Category
import lux.uno.anima.finance.model.Currency
import lux.uno.anima.finance.model.IconRef
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.Subscription
import lux.uno.anima.finance.model.Trial
import lux.uno.anima.finance.model.currentPrice
import lux.uno.anima.finance.model.nextRenewal
import lux.uno.anima.finance.store.ICON_MAX_EDGE
import lux.uno.anima.platform.newId
import lux.uno.anima.resources.*
import lux.uno.anima.ui.components.AccentSwatches
import lux.uno.anima.ui.components.AnimaTextField
import lux.uno.anima.ui.components.AnimaTopBar
import lux.uno.anima.ui.components.ChoiceChip
import lux.uno.anima.ui.components.ColorSwatchRow
import lux.uno.anima.ui.components.DateField
import lux.uno.anima.ui.components.DefaultEmojiChoices
import lux.uno.anima.ui.components.EmojiStrip
import lux.uno.anima.ui.components.FormSection
import lux.uno.anima.ui.components.GhostButton
import lux.uno.anima.ui.components.HorizontalScrollRow
import lux.uno.anima.ui.components.PrimaryButton
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.components.ToggleRow
import lux.uno.anima.ui.icons.LocalIconImages
import lux.uno.anima.ui.icons.SubscriptionIcon
import lux.uno.anima.ui.icons.rememberImagePicker
import lux.uno.anima.ui.text.displayLabel
import lux.uno.anima.ui.text.label
import lux.uno.anima.ui.theme.AnimaIcons
import lux.uno.anima.ui.theme.AnimaTheme
import androidx.compose.runtime.saveable.rememberSaveable
import lux.uno.anima.ui.components.IconRefSaver
import lux.uno.anima.ui.components.LocalDateSaver
import lux.uno.anima.ui.components.StringListSaver
import lux.uno.anima.ui.components.BillingCycleSaver
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val TrialReminderChoices = listOf(1, 3, 7, 14)

/**
 * Create and edit in one screen. [existing] null means a new subscription, in which case the
 * first price typed becomes the opening point of its price history.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubscriptionEditorScreen(
    existing: Subscription?,
    data: FinanceData,
    today: LocalDate,
    onBack: () -> Unit,
    onSave: (Subscription) -> Unit,
    onManagePaymentMethods: () -> Unit,
    onManageCategories: () -> Unit,
    onCreateCategory: (Category) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var icon by rememberSaveable(stateSaver = IconRefSaver) { mutableStateOf(existing?.icon ?: IconRef.Default) }
    var colorArgb by rememberSaveable { mutableStateOf(existing?.colorArgb ?: AccentSwatches.first()) }
    var amount by rememberSaveable {
        mutableStateOf(existing?.let { formatAmountForInput(it.currentPrice(today)) } ?: "")
    }
    var currency by rememberSaveable {
        mutableStateOf(existing?.currency ?: data.settings.preferredCurrency)
    }
    var cycle by rememberSaveable(stateSaver = BillingCycleSaver) {
        mutableStateOf(existing?.cycle ?: BillingCycle.Monthly)
    }
    var nextCharge by rememberSaveable(stateSaver = LocalDateSaver) {
        mutableStateOf(existing?.nextRenewal(today) ?: today.plus(1, DateTimeUnit.MONTH))
    }
    var hasTrial by rememberSaveable { mutableStateOf(existing?.trial != null) }
    var trialEnds by rememberSaveable(stateSaver = LocalDateSaver) {
        mutableStateOf(existing?.trial?.endsOn ?: today.plus(14, DateTimeUnit.DAY))
    }
    var remindDaysBefore by rememberSaveable { mutableStateOf(existing?.trial?.remindDaysBefore ?: 3) }
    var paymentMethodId by rememberSaveable { mutableStateOf(existing?.paymentMethodId) }
    var categoryId by rememberSaveable { mutableStateOf(existing?.categoryId) }
    var notes by rememberSaveable { mutableStateOf(existing?.notes.orEmpty()) }
    var newCategoryOpen by rememberSaveable { mutableStateOf(false) }

    // Adding a payment method from here is adding it for this subscription, so it is chosen
    // as soon as it exists. Leaving it unselected asks the user to tap the card they just
    // created, which reads as the form having ignored them.
    var knownMethodIds by rememberSaveable(stateSaver = StringListSaver) {
        mutableStateOf(data.paymentMethods.map { it.id })
    }
    LaunchedEffect(data.paymentMethods) {
        val ids = data.paymentMethods.map { it.id }
        // Renaming one is not creating one, so only an id that was not here before counts.
        (ids - knownMethodIds.toSet()).lastOrNull()?.let { paymentMethodId = it }
        knownMethodIds = ids
    }

    val parsedAmount = amount.toAmountMinorOrNull()
    val canSave = name.isNotBlank() && parsedAmount != null
    val accent = Color(colorArgb)

    ScreenSurface(modifier) { contentModifier ->
        Column(contentModifier.safeDrawingPadding().fillMaxSize()) {
            AnimaTopBar(
                title = if (existing == null) {
                    stringResource(Res.string.editor_new)
                } else {
                    stringResource(Res.string.editor_edit, existing.name)
                },
                onBack = onBack,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                FormSection(stringResource(Res.string.editor_what_is_it)) {
                    AnimaTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(Res.string.editor_name),
                        placeholder = stringResource(Res.string.editor_name_placeholder),
                    )
                    IconChooser(icon = icon, accent = accent, onChange = { icon = it })
                    ColorSwatchRow(
                        colors = AccentSwatches,
                        selected = colorArgb,
                        onSelect = { colorArgb = it },
                    )
                }

                FormSection(stringResource(Res.string.editor_category)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        data.categories.forEach { option ->
                            ChoiceChip(
                                label = option.displayLabel(),
                                selected = categoryId == option.id,
                                // Tapping the chosen one again clears it back to uncategorised.
                                onClick = {
                                    categoryId = if (categoryId == option.id) null else option.id
                                },
                                accent = Color(option.colorArgb),
                                leading = option.glyph,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        ChoiceChip(
                            label = stringResource(Res.string.editor_new_category),
                            selected = false,
                            onClick = { newCategoryOpen = true },
                            leading = "+",
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        ChoiceChip(
                            label = stringResource(Res.string.editor_manage),
                            selected = false,
                            onClick = onManageCategories,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }

                FormSection(stringResource(Res.string.editor_what_it_costs)) {
                    AnimaTextField(
                        value = amount,
                        onValueChange = { amount = it.filterAmountInput() },
                        label = stringResource(Res.string.editor_amount),
                        placeholder = stringResource(Res.string.editor_amount_placeholder),
                        keyboardType = KeyboardType.Decimal,
                        prefix = Currency.fromCode(currency)?.symbol ?: currency,
                    )
                    HorizontalScrollRow {
                        Currency.entries.forEach { option ->
                            ChoiceChip(
                                label = option.code,
                                selected = currency == option.code,
                                onClick = { currency = option.code },
                                accent = accent,
                            )
                        }
                    }
                    if (existing != null) {
                        Text(
                            text = stringResource(Res.string.editor_price_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = AnimaTheme.palette.faint,
                        )
                    }
                }

                FormSection(stringResource(Res.string.editor_how_often)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BillingCycle.presets.forEach { option ->
                            ChoiceChip(
                                label = option.label(),
                                selected = cycle == option,
                                onClick = { cycle = option },
                                accent = accent,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                    DateField(
                        label = stringResource(Res.string.editor_next_charge),
                        date = nextCharge,
                        onDateChange = { nextCharge = it },
                        // A charge that already happened is not the next one. Existing
                        // subscriptions open on `nextRenewal`, which has already been rolled
                        // forward past today, so this never refuses the value it starts on.
                        earliest = today,
                    )
                }

                FormSection(stringResource(Res.string.editor_trial)) {
                    ToggleRow(
                        title = stringResource(Res.string.editor_is_trial),
                        subtitle = stringResource(Res.string.editor_is_trial_subtitle),
                        checked = hasTrial,
                        onCheckedChange = { hasTrial = it },
                    )
                    if (hasTrial) {
                        DateField(
                            label = stringResource(Res.string.editor_trial_ends),
                            date = trialEnds,
                            onDateChange = { trialEnds = it },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(Res.string.editor_remind_me),
                                style = MaterialTheme.typography.labelSmall,
                                color = AnimaTheme.palette.faint,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TrialReminderChoices.forEach { days ->
                                    ChoiceChip(
                                        label = pluralStringResource(
                                            Res.plurals.editor_days_before,
                                            days,
                                            days,
                                        ),
                                        selected = remindDaysBefore == days,
                                        onClick = { remindDaysBefore = days },
                                        accent = accent,
                                    )
                                }
                            }
                        }
                    }
                }

                FormSection(stringResource(Res.string.editor_paid_with)) {
                    if (data.paymentMethods.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.editor_no_payment_methods),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AnimaTheme.palette.muted,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        data.paymentMethods.forEach { method ->
                            ChoiceChip(
                                label = method.displayName,
                                selected = paymentMethodId == method.id,
                                onClick = {
                                    paymentMethodId = if (paymentMethodId == method.id) null else method.id
                                },
                                accent = Color(method.colorArgb),
                                leading = method.kind.glyph,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        ChoiceChip(
                            label = stringResource(Res.string.editor_manage),
                            selected = false,
                            onClick = onManagePaymentMethods,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }

                FormSection(stringResource(Res.string.editor_notes)) {
                    AnimaTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        placeholder = stringResource(Res.string.editor_notes_placeholder),
                        singleLine = false,
                        minLines = 3,
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                PrimaryButton(
                    label = stringResource(
                        if (existing == null) Res.string.editor_add else Res.string.editor_save,
                    ),
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        parsedAmount?.let { minor ->
                            onSave(
                                buildSubscription(
                                    existing = existing,
                                    today = today,
                                    name = name.trim(),
                                    icon = icon.normalised(),
                                    colorArgb = colorArgb,
                                    price = Money(minor, currency),
                                    cycle = cycle,
                                    nextCharge = nextCharge,
                                    trial = if (hasTrial) Trial(trialEnds, remindDaysBefore) else null,
                                    paymentMethodId = paymentMethodId,
                                    categoryId = categoryId,
                                    notes = notes.trim(),
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    if (newCategoryOpen) {
        CategoryDialog(
            existing = null,
            onDismiss = { newCategoryOpen = false },
            onConfirm = { category ->
                onCreateCategory(category)
                categoryId = category.id
                newCategoryOpen = false
            },
        )
    }
}

/** Either an uploaded image or an emoji, never a half-filled version of both. */
@Composable
private fun IconChooser(
    icon: IconRef,
    accent: Color,
    onChange: (IconRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconImages = LocalIconImages.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberImagePicker { bytes ->
        if (bytes == null) return@rememberImagePicker
        busy = true
        error = null
        scope.launch {
            val storedId = iconImages.add(bytes)
            busy = false
            if (storedId == null) {
                error = getString(Res.string.editor_image_error)
            } else {
                onChange(icon.copy(imageId = storedId))
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon.usesImage) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubscriptionIcon(icon, accent, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(Res.string.editor_custom_image),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(Res.string.editor_image_body, ICON_MAX_EDGE),
                        style = MaterialTheme.typography.bodySmall,
                        color = AnimaTheme.palette.muted,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    label = stringResource(
                        if (busy) Res.string.editor_optimising else Res.string.editor_replace,
                    ),
                    onClick = { if (!busy) picker.launch() },
                )
                GhostButton(
                    label = stringResource(Res.string.editor_use_emoji),
                    onClick = { onChange(icon.copy(imageId = null)) },
                )
            }
        } else {
            EmojiStrip(
                selected = icon.emoji,
                choices = DefaultEmojiChoices,
                accent = accent,
                onSelect = { onChange(icon.copy(emoji = it)) },
                onTyped = { onChange(icon.copy(emoji = it)) },
            )
            GhostButton(
                label = stringResource(
                    if (busy) Res.string.editor_optimising else Res.string.editor_upload,
                ),
                onClick = { if (!busy) picker.launch() },
                icon = AnimaIcons.Plus,
            )
        }

        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Guards against an empty emoji field leaving a record with no visible icon. */
private fun IconRef.normalised(): IconRef =
    if (emoji.isBlank()) copy(emoji = "✦") else this

/**
 * Merges the form back onto the record. Editing the amount rewrites the price that is in force
 * today rather than appending a new point, so a typo correction does not look like a price rise.
 */
private fun buildSubscription(
    existing: Subscription?,
    today: LocalDate,
    name: String,
    icon: IconRef,
    colorArgb: Long,
    price: Money,
    cycle: BillingCycle,
    nextCharge: LocalDate,
    trial: Trial?,
    paymentMethodId: String?,
    categoryId: String?,
    notes: String,
): Subscription {
    val history = when {
        existing == null -> listOf(PricePoint(minOf(today, nextCharge), price))
        else -> {
            val sorted = existing.history
            val currentIndex = sorted.indexOfLast { it.effectiveFrom <= today }
            when {
                sorted.isEmpty() -> listOf(PricePoint(today, price))
                currentIndex < 0 -> sorted.toMutableList().also {
                    it[0] = it[0].copy(price = price)
                }
                else -> sorted.toMutableList().also {
                    it[currentIndex] = it[currentIndex].copy(price = price)
                }
            }
        }
    }

    return Subscription(
        id = existing?.id ?: newId("sub"),
        name = name,
        icon = icon,
        colorArgb = colorArgb,
        cycle = cycle,
        anchorDate = nextCharge,
        priceHistory = history.map {
            // Keep every point in one currency; switching currency rebases the whole history.
            if (it.price.currency == price.currency) it
            else it.copy(price = it.price.copy(currency = price.currency))
        },
        trial = trial,
        paymentMethodId = paymentMethodId,
        categoryId = categoryId,
        notes = notes,
        isActive = existing?.isActive ?: true,
    )
}
