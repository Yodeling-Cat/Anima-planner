package lux.uno.anima.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lux.uno.anima.resources.*
import lux.uno.anima.ui.components.AnimaTopBar
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.theme.AnimaTheme
import lux.uno.anima.ui.theme.EyebrowStyle
import org.jetbrains.compose.resources.stringResource

/** Placeholder for the areas that have no feature behind them yet. */
@Composable
fun ComingSoonScreen(
    area: LifeArea,
    /** Null where a rail is showing, which is the way back to the dashboard instead. */
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ScreenSurface(modifier) { contentModifier ->
        Column(contentModifier.safeDrawingPadding().fillMaxSize()) {
            AnimaTopBar(title = area.title, onBack = onBack)
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(area.glyph, fontSize = 44.sp)
                Text(
                    text = area.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = area.accent,
                )
                Text(
                    text = area.tagline,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AnimaTheme.palette.muted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.padding(4.dp))
                Text(
                    text = stringResource(Res.string.coming_soon_not_built),
                    style = EyebrowStyle,
                    color = AnimaTheme.palette.faint,
                )
            }
            Spacer(Modifier.weight(1.4f))
        }
    }
}
