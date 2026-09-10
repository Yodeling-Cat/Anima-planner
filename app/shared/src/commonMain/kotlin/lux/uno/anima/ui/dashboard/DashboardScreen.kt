package lux.uno.anima.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lux.uno.anima.finance.currentHour
import lux.uno.anima.finance.formatLong
import lux.uno.anima.finance.today
import lux.uno.anima.ui.components.Pill
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.theme.AnimaTheme
import lux.uno.anima.ui.theme.EyebrowStyle

/** The handful of finance numbers the dashboard shows without opening the section. */
data class FinanceGlance(
    val monthlyTotal: String?,
    val subscriptionCount: Int,
    val nextChargeLabel: String?,
    val alertCount: Int,
)

@Composable
fun DashboardScreen(
    glance: FinanceGlance,
    onOpen: (LifeArea) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenSurface(modifier) { contentModifier ->
        Column(
            modifier = contentModifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 28.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Greeting()
            Spacer(Modifier.height(6.dp))

            FinanceDoor(glance = glance, onClick = { onOpen(LifeArea.FINANCE) })

            val rest = LifeArea.entries.filter { it != LifeArea.FINANCE }
            rest.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEach { area ->
                        AreaDoor(
                            area = area,
                            onClick = { onOpen(area) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Greeting() {
    val hour = currentHour()
    val salutation = when (hour) {
        in 0..4 -> "Still up"
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ANIMA", style = EyebrowStyle, color = AnimaTheme.palette.faint)
        Text(
            text = salutation,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = today().formatLong(),
            style = MaterialTheme.typography.bodyMedium,
            color = AnimaTheme.palette.muted,
        )
    }
}

/** Finance gets a wide card because it is the only area with something to report today. */
@Composable
private fun FinanceDoor(
    glance: FinanceGlance,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val area = LifeArea.FINANCE
    val accent = area.accent
    DoorSurface(accent = accent, onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(area.glyph, fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = area.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (glance.alertCount > 0) {
                    Pill(
                        text = if (glance.alertCount == 1) "1 trial ending" else "${glance.alertCount} trials ending",
                        accent = AnimaTheme.palette.warning,
                        leading = "•",
                    )
                }
            }

            if (glance.monthlyTotal != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = glance.monthlyTotal,
                            style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "per month",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AnimaTheme.palette.muted,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                    Text(
                        text = buildString {
                            append(glance.subscriptionCount)
                            append(if (glance.subscriptionCount == 1) " subscription" else " subscriptions")
                            if (glance.nextChargeLabel != null) append(" · ${glance.nextChargeLabel}")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = AnimaTheme.palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = area.tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AnimaTheme.palette.muted,
                )
            }
        }
    }
}

@Composable
private fun AreaDoor(
    area: LifeArea,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = area.accent
    DoorSurface(accent = accent, onClick = onClick, modifier = modifier.heightIn(min = 132.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(area.glyph, fontSize = 22.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                text = area.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = area.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = AnimaTheme.palette.muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Shared card shell: a faint wash of the accent, a hairline, and one tap target. */
@Composable
private fun DoorSurface(
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dark = AnimaTheme.palette.isDark
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AnimaTheme.palette.elevated)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = if (dark) 0.16f else 0.10f),
                        accent.copy(alpha = if (dark) 0.05f else 0.03f),
                    ),
                ),
            )
            .border(BorderStroke(1.dp, accent.copy(alpha = if (dark) 0.22f else 0.16f)), shape)
            .clickable(onClick = onClick),
    ) {
        content()
    }
}
