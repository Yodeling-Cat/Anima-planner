package lux.uno.anima.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.model.CardExpiry
import lux.uno.anima.finance.model.toDigits
import lux.uno.anima.finance.reminders.CARD_EXPIRY_WARNING_MONTHS
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.PaymentMethodKind
import lux.uno.anima.platform.newId
import lux.uno.anima.resources.*
import lux.uno.anima.ui.components.AccentSwatches
import lux.uno.anima.ui.components.AnimaCard
import lux.uno.anima.ui.components.AnimaTextField
import lux.uno.anima.ui.components.AnimaTopBar
import lux.uno.anima.ui.components.ChoiceChip
import lux.uno.anima.ui.components.ColorSwatchRow
import lux.uno.anima.ui.components.EmptyState
import lux.uno.anima.ui.components.GlyphTile
import lux.uno.anima.ui.components.IconAction
import lux.uno.anima.ui.components.PrimaryButton
import lux.uno.anima.ui.components.Pill
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.finance.components.color
import lux.uno.anima.ui.finance.components.expiryStatus
import lux.uno.anima.ui.text.formatLong
import lux.uno.anima.ui.text.label
import lux.uno.anima.ui.theme.AnimaIcons
import lux.uno.anima.ui.theme.AnimaTheme
import androidx.compose.runtime.saveable.rememberSaveable
import lux.uno.anima.ui.components.enumSaver
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Manage the cards and accounts subscriptions are charged to. */
@Composable
fun PaymentMethodsScreen(
    data: FinanceData,
    today: LocalDate,
    notificationsEnabled: Boolean,
    onBack: () -> Unit,
    onSave: (PaymentMethod) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ids rather than copies, for the same reason as the categories screen.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

    val editing = editingId?.let { id -> data.paymentMethods.firstOrNull { it.id == id } }
    val pendingDelete = pendingDeleteId?.let { id -> data.paymentMethods.firstOrNull { it.id == id } }

    val usageCount = remember(data) {
        data.subscriptions.groupingBy { it.paymentMethodId }.eachCount()
    }

    ScreenSurface(modifier) { contentModifier ->
        Column(contentModifier.safeDrawingPadding().fillMaxSize()) {
            AnimaTopBar(
                title = stringResource(Res.string.payment_methods_title),
                onBack = onBack,
                actions = {
                    IconAction(
                        AnimaIcons.Plus,
                        stringResource(Res.string.payment_methods_add),
                        { creating = true },
                    )
                },
            )

            if (data.paymentMethods.isEmpty()) {
                EmptyState(
                    glyph = "💳",
                    title = stringResource(Res.string.payment_methods_empty_title),
                    body = stringResource(Res.string.payment_methods_empty_body),
                    action = {
                        PrimaryButton(
                            stringResource(Res.string.payment_methods_empty_action),
                            { creating = true },
                            icon = AnimaIcons.Plus,
                        )
                    },
                )
                return@Column
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                data.paymentMethods.forEach { method ->
                    val used = usageCount[method.id] ?: 0
                    AnimaCard(
                        onClick = { editingId = method.id },
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlyphTile(method.kind.glyph, Color(method.colorArgb), size = 40.dp)
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(method.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = if (used == 0) {
                                        stringResource(
                                            Res.string.payment_method_unused,
                                            method.kind.label(),
                                        )
                                    } else {
                                        pluralStringResource(
                                            Res.plurals.payment_method_used,
                                            used,
                                            method.kind.label(),
                                            used,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AnimaTheme.palette.muted,
                                )
                                method.expiryStatus(today)?.let { status ->
                                    Spacer(Modifier.padding(top = 2.dp))
                                    Pill(text = status.short, accent = status.severity.color())
                                }
                            }
                            IconAction(
                                icon = AnimaIcons.Trash,
                                contentDescription = stringResource(
                                    Res.string.payment_method_delete_description,
                                    method.name,
                                ),
                                onClick = { pendingDeleteId = method.id },
                                tint = AnimaTheme.palette.faint,
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        PaymentMethodDialog(
            existing = editing,
            today = today,
            notificationsEnabled = notificationsEnabled,
            onDismiss = { creating = false; editingId = null },
            onConfirm = { method ->
                onSave(method)
                creating = false
                editingId = null
            },
        )
    }

    pendingDelete?.let { method ->
        val used = usageCount[method.id] ?: 0
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = {
                Text(stringResource(Res.string.payment_method_delete_title, method.name))
            },
            text = {
                Text(
                    if (used == 0) {
                        stringResource(Res.string.payment_method_delete_unused)
                    } else {
                        pluralStringResource(Res.plurals.payment_method_delete_used, used, used)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(method.id); pendingDeleteId = null }) {
                    Text(
                        stringResource(Res.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(Res.string.common_keep))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentMethodDialog(
    existing: PaymentMethod?,
    today: LocalDate,
    notificationsEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PaymentMethod) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var kind by rememberSaveable(stateSaver = enumSaver<PaymentMethodKind>()) {
        mutableStateOf(existing?.kind ?: PaymentMethodKind.CARD)
    }
    var last4 by rememberSaveable { mutableStateOf(existing?.last4.orEmpty()) }
    var colorArgb by rememberSaveable { mutableStateOf(existing?.colorArgb ?: AccentSwatches.first()) }
    var expiryDigits by rememberSaveable { mutableStateOf(existing?.expiry?.toDigits().orEmpty()) }

    val expiry = CardExpiry.fromDigits(expiryDigits)
    val expiryIncomplete = expiryDigits.isNotEmpty() && expiry == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (existing == null) {
                    stringResource(Res.string.payment_method_new)
                } else {
                    stringResource(Res.string.payment_method_edit, existing.name)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AnimaTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(Res.string.payment_method_name),
                    placeholder = stringResource(Res.string.payment_method_name_placeholder),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaymentMethodKind.entries.forEach { option ->
                        ChoiceChip(
                            label = option.label(),
                            selected = kind == option,
                            onClick = { kind = option },
                            leading = option.glyph,
                            accent = Color(colorArgb),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                AnimaTextField(
                    value = last4,
                    onValueChange = { last4 = it.filter(Char::isDigit).take(4) },
                    label = stringResource(Res.string.payment_method_last4),
                    placeholder = stringResource(Res.string.payment_method_last4_placeholder),
                    keyboardType = KeyboardType.Number,
                )
                if (kind == PaymentMethodKind.CARD) {
                    CardExpiryField(
                        digits = expiryDigits,
                        onDigitsChange = { expiryDigits = it },
                        expiry = expiry,
                        incomplete = expiryIncomplete,
                        today = today,
                        notificationsEnabled = notificationsEnabled,
                    )
                }
                ColorSwatchRow(
                    colors = AccentSwatches,
                    selected = colorArgb,
                    onSelect = { colorArgb = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !expiryIncomplete,
                onClick = {
                    onConfirm(
                        PaymentMethod(
                            id = existing?.id ?: newId("pm"),
                            name = name.trim(),
                            kind = kind,
                            last4 = last4.ifBlank { null },
                            colorArgb = colorArgb,
                            // Expiry belongs to cards only; switching kind drops it.
                            expiry = expiry.takeIf { kind == PaymentMethodKind.CARD },
                        ),
                    )
                },
            ) { Text(stringResource(Res.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/**
 * MM/YY entry. The four digits are the state; the slash is only ever presentation, so
 * backspacing over it behaves the way people expect.
 */
@Composable
private fun CardExpiryField(
    digits: String,
    onDigitsChange: (String) -> Unit,
    expiry: CardExpiry?,
    incomplete: Boolean,
    today: LocalDate,
    notificationsEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AnimaTextField(
            value = digits,
            onValueChange = { onDigitsChange(it.filter(Char::isDigit).take(4)) },
            label = stringResource(Res.string.payment_method_expiry),
            placeholder = stringResource(Res.string.payment_method_expiry_placeholder),
            keyboardType = KeyboardType.Number,
            visualTransformation = ExpiryVisualTransformation,
        )
        Text(
            text = expiryHelpText(expiry, incomplete, today, notificationsEnabled),
            style = MaterialTheme.typography.bodySmall,
            color = if (incomplete) MaterialTheme.colorScheme.error else AnimaTheme.palette.faint,
        )
    }
}

/**
 * Shows `0926` as `09/26` without that slash ever entering the value. Rewriting the text
 * itself would drag the caret back to where the old string ended, which turns `0926` into
 * `09/62` as the fourth digit lands.
 */
private val ExpiryVisualTransformation = VisualTransformation { text ->
    val digits = text.text.take(4)
    val formatted =
        if (digits.length <= 2) digits else digits.substring(0, 2) + "/" + digits.substring(2)

    TransformedText(
        AnnotatedString(formatted),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                if (offset <= 2) offset else offset + 1

            override fun transformedToOriginal(offset: Int): Int =
                if (offset <= 2) offset else offset - 1
        },
    )
}

@Composable
private fun expiryHelpText(
    expiry: CardExpiry?,
    incomplete: Boolean,
    today: LocalDate,
    notificationsEnabled: Boolean,
): String {
    if (incomplete) return stringResource(Res.string.expiry_hint_incomplete)
    if (expiry == null) return stringResource(Res.string.expiry_hint_none)

    val expiresOn = expiry.lastValidDay
    val warnOn = expiresOn.minus(CARD_EXPIRY_WARNING_MONTHS, DateTimeUnit.MONTH)
    val schedule = when {
        expiresOn < today ->
            stringResource(Res.string.expiry_hint_expired, expiresOn.formatLong())
        warnOn <= today ->
            stringResource(Res.string.expiry_hint_soon, expiresOn.formatLong())
        else -> stringResource(
            Res.string.expiry_hint_future,
            warnOn.formatLong(),
            expiresOn.formatLong(),
        )
    }

    return if (notificationsEnabled) schedule
    else stringResource(Res.string.expiry_hint_no_notifications, schedule)
}
