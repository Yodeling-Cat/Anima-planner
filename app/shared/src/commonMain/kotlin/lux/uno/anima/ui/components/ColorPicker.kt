package lux.uno.anima.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import lux.uno.anima.resources.*
import lux.uno.anima.ui.theme.AnimaTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * A saturation/value field over a hue strip, plus a hex field for people who already know
 * the colour they want. Alpha is deliberately absent — these colours are accents behind
 * text and a translucent one would wreck contrast.
 */
@Composable
fun ColorPickerDialog(
    initialArgb: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val initial = remember(initialArgb) { Color(initialArgb).toHsv() }
    var hue by remember { mutableStateOf(initial.hue) }
    var saturation by remember { mutableStateOf(initial.saturation) }
    var value by remember { mutableStateOf(initial.value) }
    var hexDraft by remember { mutableStateOf(Color(initialArgb).toHexString()) }

    val color = Color.hsv(hue, saturation, value)
    val argb = color.toOpaqueArgb()

    // Typing in the field drives the wheel; dragging the wheel rewrites the field.
    fun applyHsv(newHue: Float, newSaturation: Float, newValue: Float) {
        hue = newHue
        saturation = newSaturation
        value = newValue
        hexDraft = Color.hsv(newHue, newSaturation, newValue).toHexString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.color_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SaturationValueField(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onChange = { s, v -> applyHsv(hue, s, v) },
                )
                HueStrip(hue = hue, onChange = { applyHsv(it, saturation, value) })

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(color)
                            .border(1.dp, AnimaTheme.palette.hairline, RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    AnimaTextField(
                        value = hexDraft,
                        onValueChange = { typed ->
                            val cleaned = typed.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                            hexDraft = cleaned
                            cleaned.toColorOrNull()?.toHsv()?.let { parsed ->
                                hue = parsed.hue
                                saturation = parsed.saturation
                                value = parsed.value
                            }
                        },
                        label = stringResource(Res.string.color_picker_hex),
                        placeholder = stringResource(Res.string.color_picker_hex_placeholder),
                        prefix = "#",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(argb) }) { Text(stringResource(Res.string.color_picker_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

@Composable
private fun SaturationValueField(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (saturation: Float, value: Float) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(hue) {
                fun report(position: Offset) {
                    onChange(
                        (position.x / size.width).coerceIn(0f, 1f),
                        1f - (position.y / size.height).coerceIn(0f, 1f),
                    )
                }
                detectTapGestures { report(it) }
            }
            .pointerInput(hue) {
                fun report(position: Offset) {
                    onChange(
                        (position.x / size.width).coerceIn(0f, 1f),
                        1f - (position.y / size.height).coerceIn(0f, 1f),
                    )
                }
                detectDragGestures(
                    onDragStart = { report(it) },
                    onDrag = { change, _ -> report(change.position) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))),
            )
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
            )
            val marker = Offset(saturation * size.width, (1f - value) * size.height)
            drawCircle(Color.White, radius = 7.dp.toPx(), center = marker, style = Stroke(2.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.4f), radius = 9.dp.toPx(), center = marker, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun HueStrip(hue: Float, onChange: (Float) -> Unit) {
    val hues = remember { (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) } }
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(50))
            .pointerInput(Unit) {
                fun report(position: Offset) {
                    onChange((position.x / size.width).coerceIn(0f, 1f) * 359.99f)
                }
                detectTapGestures { report(it) }
            }
            .pointerInput(Unit) {
                fun report(position: Offset) {
                    onChange((position.x / size.width).coerceIn(0f, 1f) * 359.99f)
                }
                detectDragGestures(
                    onDragStart = { report(it) },
                    onDrag = { change, _ -> report(change.position) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.horizontalGradient(hues))
            val x = (hue / 360f) * size.width
            drawCircle(
                Color.White,
                radius = size.height / 2f - 2.dp.toPx(),
                center = Offset(x.coerceIn(size.height / 2f, size.width - size.height / 2f), size.height / 2f),
                style = Stroke(2.5.dp.toPx()),
            )
        }
    }
}

/** Hue in degrees, saturation and value in 0..1. */
data class Hsv(val hue: Float, val saturation: Float, val value: Float)

fun Color.toHsv(): Hsv {
    val r = red
    val g = green
    val b = blue
    val maximum = max(r, max(g, b))
    val minimum = min(r, min(g, b))
    val delta = maximum - minimum

    val hue = when {
        delta < 0.00001f -> 0f
        maximum == r -> 60f * (((g - b) / delta) % 6f)
        maximum == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }

    return Hsv(
        hue = if (hue < 0f) hue + 360f else hue,
        saturation = if (maximum <= 0f) 0f else delta / maximum,
        value = maximum,
    )
}

/** `6C7BFF` — no alpha, no leading hash. */
fun Color.toHexString(): String {
    fun channel(component: Float) = (component * 255).roundToInt().coerceIn(0, 255)
        .toString(16).uppercase().padStart(2, '0')
    return channel(red) + channel(green) + channel(blue)
}

/** Parses `6C7BFF` or `#6C7BFF`; anything else returns null. */
fun String.toColorOrNull(): Color? {
    val digits = removePrefix("#").trim()
    if (digits.length != 6) return null
    val rgb = digits.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}

/** Drops any alpha so stored colours are always fully opaque. */
fun Color.toOpaqueArgb(): Long {
    fun channel(component: Float) = (component * 255).roundToInt().coerceIn(0, 255).toLong()
    return 0xFF000000L or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}

/** True when [argb] is not one of the ready-made swatches, so the row shows it as custom. */
fun isCustomColor(argb: Long): Boolean = argb !in AccentSwatches
