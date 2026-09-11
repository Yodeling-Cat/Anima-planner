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
import lux.uno.anima.finance.today
import lux.uno.anima.resources.*
import lux.uno.anima.ui.text.formatLong
import lux.uno.anima.ui.components.Pill
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.theme.AnimaTheme
import lux.uno.anima.ui.theme.EyebrowStyle
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import lux.uno.anima.ui.components.IconAction
import lux.uno.anima.ui.theme.AnimaIcons
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
    /**
     * Null where a rail is showing, which carries the account itself. Two doors onto one
     * screen is one more than the screen needs, and the rail's is the one that is there
     * from everywhere rather than only from here.
     */
    onOpenAccount: (() -> Unit)?,
    /** Always drawn — see `syncDotFor`, which decides what colour it is saying. */
    syncDot: Color,
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
            Row(verticalAlignment = Alignment.Top) {
                Greeting(Modifier.weight(1f))
                if (onOpenAccount != null) AccountDoor(syncDot = syncDot, onClick = onOpenAccount)
            }
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

/**
 * The way into the account screen, and the only sync signal on the dashboard.
 *
 * A dot rather than words: the detail belongs on the account screen, and the dashboard only
 * has to say whether anything wants attention.
 */
@Composable
private fun AccountDoor(syncDot: Color, onClick: () -> Unit) {
    Box {
        IconAction(AnimaIcons.Person, stringResource(Res.string.dashboard_account), onClick)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(syncDot),
        )
    }
}

@Composable
private fun Greeting(modifier: Modifier = Modifier) {
    val hour = currentHour()
    val salutation = stringResource(
        when (hour) {
            in 0..4 -> Res.string.greeting_still_up
            in 5..11 -> Res.string.greeting_morning
            in 12..17 -> Res.string.greeting_afternoon
            else -> Res.string.greeting_evening
        },
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.dashboard_eyebrow), style = EyebrowStyle, color = AnimaTheme.palette.faint)
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
                        text = pluralStringResource(
                            Res.plurals.dashboard_trials_ending,
                            glance.alertCount,
                            glance.alertCount,
                        ),
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
                            text = stringResource(Res.string.dashboard_per_month),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AnimaTheme.palette.muted,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                    Text(
                        text = pluralStringResource(
                            Res.plurals.dashboard_subscription_count,
                            glance.subscriptionCount,
                            glance.subscriptionCount,
                        ).let { count ->
                            glance.nextChargeLabel?.let {
                                stringResource(Res.string.dashboard_glance_with_next, count, it)
                            } ?: count
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
