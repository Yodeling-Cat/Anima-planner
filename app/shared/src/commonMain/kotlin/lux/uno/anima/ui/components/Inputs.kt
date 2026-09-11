package lux.uno.anima.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import lux.uno.anima.ui.theme.AnimaIcons
import lux.uno.anima.ui.theme.AnimaTheme
import lux.uno.anima.ui.theme.EyebrowStyle

/** A titled block of form controls. */
@Composable
fun FormSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title)
        content()
    }
}

/**
 * Text input with the label above the box rather than floating inside it — one less thing
 * moving while you type, and it keeps every field the same height.
 */
@Composable
fun AnimaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    prefix: String? = null,
    /**
     * Lets a field show something other than it stores — punctuation in a date, say. Doing
     * it here rather than rewriting the value keeps the caret where the typist left it.
     */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            Text(label.uppercase(), style = EyebrowStyle, color = AnimaTheme.palette.faint)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = LocalTextStyle.current.merge(
                MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AnimaTheme.palette.elevated)
                        .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                        .heightIn(min = 50.dp)
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (prefix != null) {
                        Text(
                            text = prefix,
                            style = MaterialTheme.typography.bodyLarge,
                            color = AnimaTheme.palette.muted,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = AnimaTheme.palette.faint,
                            )
                        }
                        inner()
                    }
                    if (trailing != null) {
                        Spacer(Modifier.width(8.dp))
                        trailing()
                    }
                }
            },
        )
    }
}

/** Selectable chip. Selection is carried by the accent, not by a checkmark. */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    leading: String? = null,
) {
    val dark = AnimaTheme.palette.isDark
    val background by animateColorAsState(
        if (selected) accent.copy(alpha = if (dark) 0.26f else 0.15f) else Color.Transparent,
    )
    val border by animateColorAsState(
        if (selected) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Text(leading, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) accent else AnimaTheme.palette.muted,
        )
    }
}

/** Filled action button — one per screen, reserved for the thing you came to do. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val background = if (enabled) color else color.copy(alpha = 0.35f)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
}

/** Low-emphasis button: outline only. */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** A labelled row that opens something else — used for dates and pickers. */
@Composable
fun PickerRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = AnimaIcons.ChevronRight,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), style = EyebrowStyle, color = AnimaTheme.palette.faint)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AnimaTheme.palette.elevated)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(icon, null, tint = AnimaTheme.palette.faint, modifier = Modifier.size(18.dp))
        }
    }
}

/** Title, optional explanation, and a switch. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AnimaTheme.palette.muted)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/**
 * Ready-made swatches plus a custom entry that opens the full picker. The custom circle
 * shows a hue wheel until a colour outside the presets is chosen, then shows that colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatchRow(
    colors: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    allowCustom: Boolean = true,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val custom = allowCustom && isCustomColor(selected)

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        colors.forEach { argb ->
            Swatch(
                brush = SolidColor(Color(argb)),
                selected = argb == selected,
                onClick = { onSelect(argb) },
            )
        }
        if (allowCustom) {
            Swatch(
                brush = if (custom) SolidColor(Color(selected)) else HueWheelBrush,
                selected = custom,
                onClick = { pickerOpen = true },
                content = {
                    if (!custom) {
                        Icon(
                            imageVector = AnimaIcons.Pencil,
                            contentDescription = "Custom colour",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                },
            )
        }
    }

    if (pickerOpen) {
        ColorPickerDialog(
            initialArgb = selected,
            onDismiss = { pickerOpen = false },
            onConfirm = {
                onSelect(it)
                pickerOpen = false
            },
        )
    }
}

@Composable
private fun Swatch(
    brush: Brush,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(50))
            .background(brush)
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onBackground
                else AnimaTheme.palette.hairline,
                shape = RoundedCornerShape(50),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The wheel shown on the custom swatch before a custom colour has been chosen. */
private val HueWheelBrush: Brush = Brush.sweepGradient(
    (0..6).map { Color.hsv(it * 60f % 360f, 0.75f, 0.95f) },
)

/** The palette offered wherever a subscription or payment method picks a colour. */
val AccentSwatches: List<Long> = listOf(
    0xFF6C7BFF,
    0xFF2E6F5E,
    0xFF3ECF7E,
    0xFFE0A458,
    0xFFE0504F,
    0xFFB07CF5,
    0xFF5AA9E6,
    0xFF8C4A6B,
)
