package lux.uno.anima.ui.finance.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import lux.uno.anima.finance.model.Money
import lux.uno.anima.finance.model.PricePoint
import lux.uno.anima.finance.model.format
import lux.uno.anima.resources.*
import lux.uno.anima.ui.text.formatShort
import lux.uno.anima.ui.theme.AnimaTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Price over time as a step line: a price holds flat until the day the next one takes over,
 * which is how subscription pricing actually behaves. A smooth line would imply a gradual
 * drift that never happened.
 */
@Composable
fun PriceHistoryChart(
    history: List<PricePoint>,
    today: LocalDate,
    accent: Color,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 156.dp,
) {
    if (history.isEmpty()) return

    val sorted = history.sortedBy { it.effectiveFrom }
    val start = sorted.first().effectiveFrom
    val end = if (today > start) today else start
    val span = start.daysUntil(end).coerceAtLeast(1)

    val values = sorted.map { it.price.minor }
    val rawMin = values.min()
    val rawMax = values.max()
    // Give a flat history some vertical room so the line does not sit on the frame.
    val padding = ((rawMax - rawMin) * 0.25).toLong().coerceAtLeast(rawMax / 12).coerceAtLeast(1)
    val minValue = (rawMin - padding).coerceAtLeast(0)
    val maxValue = rawMax + padding
    val range = (maxValue - minValue).coerceAtLeast(1)

    val gridColor = AnimaTheme.palette.hairline
    val dotFill = AnimaTheme.palette.elevated

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val topInset = 10f
                val bottomInset = 10f
                val sideInset = 6.dp.toPx()
                val plotWidth = w - sideInset * 2
                val plotHeight = h - topInset - bottomInset

                fun x(date: LocalDate): Float =
                    sideInset + start.daysUntil(date).toFloat() / span * plotWidth
                fun y(minor: Long): Float =
                    topInset + plotHeight * (1f - (minor - minValue).toFloat() / range.toFloat())

                // Three faint guides so the eye can judge distance between prices.
                repeat(3) { index ->
                    val guideY = topInset + plotHeight * (index + 0.5f) / 3f
                    drawLine(
                        color = gridColor,
                        start = Offset(sideInset, guideY),
                        end = Offset(w - sideInset, guideY),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 6f)),
                    )
                }

                // Build the staircase, then close it downward for the fill.
                val line = Path()
                var previousY = y(values.first())
                line.moveTo(sideInset, previousY)
                sorted.drop(1).forEach { point ->
                    val px = x(point.effectiveFrom)
                    line.lineTo(px, previousY)
                    previousY = y(point.price.minor)
                    line.lineTo(px, previousY)
                }
                line.lineTo(w - sideInset, previousY)

                val fill = Path().apply {
                    addPath(line)
                    lineTo(w - sideInset, h)
                    lineTo(sideInset, h)
                    close()
                }
                drawPath(
                    path = fill,
                    brush = Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0f)),
                    ),
                )
                drawPath(
                    path = line,
                    color = accent,
                    style = Stroke(width = 2.2f.dp.toPx(), cap = StrokeCap.Round),
                )

                // A ring on every day the price actually changed.
                sorted.forEach { point ->
                    val center = Offset(x(point.effectiveFrom), y(point.price.minor))
                    drawCircle(color = dotFill, radius = 4.5f.dp.toPx(), center = center)
                    drawCircle(
                        color = accent,
                        radius = 4.5f.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2f.dp.toPx()),
                    )
                }
            }

            AxisLabel(
                text = Money(maxValue, sorted.first().price.currency).format(),
                modifier = Modifier.align(Alignment.TopStart),
            )
            AxisLabel(
                text = Money(minValue, sorted.first().price.currency).format(),
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AxisLabel(start.formatShort())
            AxisLabel(if (end == start) "" else stringResource(Res.string.chart_today))
        }
    }
}

@Composable
private fun AxisLabel(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AnimaTheme.palette.faint,
        modifier = modifier.padding(horizontal = 2.dp),
    )
}
