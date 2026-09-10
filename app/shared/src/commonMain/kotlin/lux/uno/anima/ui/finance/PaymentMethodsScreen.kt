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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.model.PaymentMethod
import lux.uno.anima.finance.model.PaymentMethodKind
import lux.uno.anima.platform.newId
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
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.theme.AnimaIcons
import lux.uno.anima.ui.theme.AnimaTheme

/** Manage the cards and accounts subscriptions are charged to. */
@Composable
fun PaymentMethodsScreen(
    data: FinanceData,
    onBack: () -> Unit,
    onSave: (PaymentMethod) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<PaymentMethod?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PaymentMethod?>(null) }

    val usageCount = remember(data) {
        data.subscriptions.groupingBy { it.paymentMethodId }.eachCount()
    }

    ScreenSurface(modifier) { contentModifier ->
        Column(contentModifier.safeDrawingPadding().fillMaxSize()) {
            AnimaTopBar(
                title = "Payment methods",
                onBack = onBack,
                actions = { IconAction(AnimaIcons.Plus, "Add", { creating = true }) },
            )

            if (data.paymentMethods.isEmpty()) {
                EmptyState(
                    glyph = "💳",
                    title = "No payment methods",
                    body = "Add the cards and accounts you pay from, and every subscription can "
                        + "say which one it hits.",
                    action = { PrimaryButton("Add one", { creating = true }, icon = AnimaIcons.Plus) },
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
                        onClick = { editing = method },
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlyphTile(method.kind.glyph, Color(method.colorArgb), size = 40.dp)
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(method.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = when (used) {
                                        0 -> "${method.kind.label} · not used yet"
                                        1 -> "${method.kind.label} · 1 subscription"
                                        else -> "${method.kind.label} · $used subscriptions"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AnimaTheme.palette.muted,
                                )
                            }
                            IconAction(
                                icon = AnimaIcons.Trash,
                                contentDescription = "Delete ${method.name}",
                                onClick = { pendingDelete = method },
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
            onDismiss = { creating = false; editing = null },
            onConfirm = { method ->
                onSave(method)
                creating = false
                editing = null
            },
        )
    }

    pendingDelete?.let { method ->
        val used = usageCount[method.id] ?: 0
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${method.name}?") },
            text = {
                Text(
                    if (used == 0) "Nothing is paying through it."
                    else "$used subscription${if (used == 1) "" else "s"} will be left without a payment method.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(method.id); pendingDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentMethodDialog(
    existing: PaymentMethod?,
    onDismiss: () -> Unit,
    onConfirm: (PaymentMethod) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: PaymentMethodKind.CARD) }
    var last4 by remember { mutableStateOf(existing?.last4.orEmpty()) }
    var colorArgb by remember { mutableStateOf(existing?.colorArgb ?: AccentSwatches.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New payment method" else "Edit ${existing.name}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AnimaTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    placeholder = "Personal Visa",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaymentMethodKind.entries.forEach { option ->
                        ChoiceChip(
                            label = option.label,
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
                    label = "Last four digits (optional)",
                    placeholder = "4417",
                    keyboardType = KeyboardType.Number,
                )
                ColorSwatchRow(
                    colors = AccentSwatches,
                    selected = colorArgb,
                    onSelect = { colorArgb = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        PaymentMethod(
                            id = existing?.id ?: newId("pm"),
                            name = name.trim(),
                            kind = kind,
                            last4 = last4.ifBlank { null },
                            colorArgb = colorArgb,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
