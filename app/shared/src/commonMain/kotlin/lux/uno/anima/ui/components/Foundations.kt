package lux.uno.anima.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lux.uno.anima.resources.*
import lux.uno.anima.ui.theme.AnimaIcons
import lux.uno.anima.ui.theme.AnimaTheme
import lux.uno.anima.ui.theme.EyebrowStyle
import org.jetbrains.compose.resources.stringResource

/** Widest the content column is allowed to get, so desktop windows stay readable. */
val ContentMaxWidth = 620.dp

/**
 * Page frame: paints the background, then centres a column of at most [maxWidth] so
 * the same screen reads well on a phone and on a maximised desktop window.
 *
 * The cap comes before the fill, and that order is the whole thing. `widthIn` keeps the
 * incoming constraints, so a `fillMaxSize` ahead of it hands it a width that is already
 * fixed to the window and leaves it nothing to narrow — which is how a maximised desktop
 * window ended up drawing phone layouts two thousand pixels wide.
 */
@Composable
fun ScreenSurface(
    modifier: Modifier = Modifier,
    maxWidth: Dp = ContentMaxWidth,
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        content(Modifier.widthIn(max = maxWidth).fillMaxSize())
    }
}

/** Slim top bar: an optional back affordance, a title that can be empty, trailing actions. */
@Composable
fun AnimaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(AnimaIcons.ArrowLeft, stringResource(Res.string.common_back), onBack)
        } else {
            Spacer(Modifier.size(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        actions()
    }
}

/** A 40dp tappable icon with no chrome until it is pressed. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else AnimaTheme.palette.faint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Small uppercase label that opens a section, with room for a trailing affordance. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = EyebrowStyle,
            color = AnimaTheme.palette.faint,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** The rounded card everything in this app sits on. */
@Composable
fun AnimaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(interaction, null, onClick = onClick) else Modifier),
        shape = shape,
        color = AnimaTheme.palette.elevated,
        border = BorderStroke(1.dp, AnimaTheme.palette.hairline),
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/** The coloured, rounded square that carries a subscription emoji. */
@Composable
fun GlyphTile(
    glyph: String,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(accent.copy(alpha = if (AnimaTheme.palette.isDark) 0.22f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, fontSize = (size.value * 0.46f).sp)
    }
}

/** A compact tinted label — payment method, category, status. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = AnimaTheme.palette.muted,
    leading: String? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = if (AnimaTheme.palette.isDark) 0.18f else 0.11f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (leading != null) Text(leading, fontSize = 11.sp)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Hairline divider matching the card borders. */
@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(1.dp)
            .background(AnimaTheme.palette.hairline),
    )
}

/** Centered "nothing here yet" block with one clear thing to do. */
@Composable
fun EmptyState(
    glyph: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(glyph, fontSize = 40.sp)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = AnimaTheme.palette.muted,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            action()
        }
    }
}

/** Label above a value, the pattern used all over the detail screen. */
@Composable
fun LabelledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalContentColor.current,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label.uppercase(), style = EyebrowStyle, color = AnimaTheme.palette.faint)
        Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor)
    }
}
