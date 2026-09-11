package lux.uno.anima.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lux.uno.anima.resources.*
import lux.uno.anima.ui.dashboard.LifeArea
import lux.uno.anima.ui.theme.AnimaIcons
import lux.uno.anima.ui.theme.AnimaTheme
import org.jetbrains.compose.resources.stringResource

/** How much width the rail takes out of the window when it is showing. */
val NavRailWidth = 88.dp

/**
 * The permanent way between sections, shown wherever the window is wide enough for it.
 *
 * It does not replace the back stack, it drives it: every item here is a `popTo`, so
 * arriving at Finance from the rail and arriving from the dashboard leave the same stack
 * behind them and Escape still unwinds it one screen at a time. Two ways to reach a screen
 * are fine; two stacks describing where you have been are not.
 *
 * Every area is listed, ready or not. The four that are not built yet lead to the same
 * "not built" screen the dashboard opens — hiding them until they ship would make the rail
 * change shape under someone as the app grows, and the point of a permanent rail is that
 * it is the one thing on screen that does not move.
 */
@Composable
fun AnimaNavRail(
    current: Screen,
    onHome: () -> Unit,
    onOpenArea: (LifeArea) -> Unit,
    onOpenAccount: () -> Unit,
    /** The same dot the dashboard shows, so sync state is visible from every screen. */
    syncDot: Color?,
    modifier: Modifier = Modifier,
) {
    val here = current.area
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(NavRailWidth)
            .background(AnimaTheme.palette.elevated)
            // The rail owns the left edge and the full height, so it takes those insets
            // rather than leaving them for the screen beside it — which would pad the
            // content away from an edge the rail is already covering.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RailItem(
            label = stringResource(Res.string.nav_home),
            selected = current == Screen.Dashboard,
            accent = MaterialTheme.colorScheme.onSurface,
            onClick = onHome,
            icon = AnimaIcons.Home,
        )

        Spacer(Modifier.height(6.dp))

        LifeArea.entries.forEach { area ->
            RailItem(
                label = area.title,
                selected = here == area,
                accent = area.accent,
                onClick = { onOpenArea(area) },
                glyph = area.glyph,
                // A door that opens onto "not built yet" should not look as live as one
                // that opens onto something. Dimmed rather than absent: it still says the
                // area exists and is coming.
                dimmed = !area.isReady,
            )
        }

        Spacer(Modifier.weight(1f))

        RailItem(
            label = stringResource(Res.string.dashboard_account),
            selected = current == Screen.Account,
            accent = MaterialTheme.colorScheme.onSurface,
            onClick = onOpenAccount,
            icon = AnimaIcons.Person,
            badge = syncDot,
        )
    }
}

/**
 * One rail destination: a glyph or an icon over its name.
 *
 * The name is always there rather than only on hover. A rail of five emoji is a guessing
 * game, and the width the labels cost is width a desktop window has going spare.
 */
@Composable
private fun RailItem(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    glyph: String? = null,
    dimmed: Boolean = false,
    badge: Color? = null,
) {
    val background by animateColorAsState(
        if (selected) accent.copy(alpha = if (AnimaTheme.palette.isDark) 0.20f else 0.13f)
        else Color.Transparent,
        label = "railItemBackground",
    )
    val content by animateColorAsState(
        when {
            selected -> accent
            dimmed -> AnimaTheme.palette.faint
            else -> AnimaTheme.palette.muted
        },
        label = "railItemContent",
    )

    Column(
        modifier = modifier
            .width(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                glyph != null -> Text(glyph, fontSize = 20.sp)
                icon != null -> Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            }
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 14.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(badge),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
