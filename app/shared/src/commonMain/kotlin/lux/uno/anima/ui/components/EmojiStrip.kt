package lux.uno.anima.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lux.uno.anima.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * A curated emoji strip with a free-text box beside it, so anything not on the strip can
 * still be pasted in. Used for both subscription icons and category icons.
 */
@Composable
fun EmojiStrip(
    selected: String,
    choices: List<String>,
    accent: Color,
    onSelect: (String) -> Unit,
    onTyped: (String) -> Unit,
    modifier: Modifier = Modifier,
    showField: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showField) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphTile(selected.ifBlank { "✦" }, accent, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                AnimaTextField(
                    value = selected,
                    // Four characters is enough for a flag or a skin-tone modifier sequence.
                    onValueChange = { onTyped(it.take(4)) },
                    label = stringResource(Res.string.emoji_icon_label),
                    placeholder = "✦",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalScrollRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) accent.copy(alpha = 0.2f) else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) accent.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option, fontSize = 18.sp)
                }
            }
        }
    }
}

/** Kept next to the strip so both pickers offer the same starting set. */
val DefaultEmojiChoices: List<String> = listOf(
    "✦", "🎬", "🎧", "📺", "🎮", "📱", "💻", "🛠", "☁️", "✨",
    "📚", "🗞", "🍳", "🧗", "🏋️", "🌿", "🧘", "🚗", "🚆", "🏠",
    "💡", "📶", "🔒", "📈", "🐾", "☕️", "🍷", "🎨", "🎵", "🧠",
)
