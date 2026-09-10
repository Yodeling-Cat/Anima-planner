package lux.uno.anima.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import lux.uno.anima.ui.theme.AnimaTheme

/**
 * The five doors on the home screen. Each area owns an accent that follows it into its
 * own section, so the app always says where you are without a label.
 */
enum class LifeArea(
    val title: String,
    val tagline: String,
    val glyph: String,
    private val lightAccent: Long,
    private val darkAccent: Long,
    val isReady: Boolean,
) {
    FINANCE(
        title = "Finance",
        tagline = "Subscriptions, budget, where it actually goes",
        glyph = "🪙",
        lightAccent = 0xFF2E6F5E,
        darkAccent = 0xFF6FC5A8,
        isReady = true,
    ),
    HEALTH(
        title = "Health",
        tagline = "Body, sleep, movement",
        glyph = "🌿",
        lightAccent = 0xFFB35A42,
        darkAccent = 0xFFE59379,
        isReady = false,
    ),
    PHILOSOPHY(
        title = "Philosophy",
        tagline = "Questions worth sitting with",
        glyph = "🕯️",
        lightAccent = 0xFF4A5578,
        darkAccent = 0xFF9AA6D0,
        isReady = false,
    ),
    RELIGION(
        title = "Religion",
        tagline = "Practice, ritual, stillness",
        glyph = "🕊️",
        lightAccent = 0xFF9A6B17,
        darkAccent = 0xFFE0B658,
        isReady = false,
    ),
    FUN(
        title = "Fun",
        tagline = "The part that is not a project",
        glyph = "🎲",
        lightAccent = 0xFF8C4A6B,
        darkAccent = 0xFFD190B0,
        isReady = false,
    );

    /** Accent tuned for the current theme. */
    val accent: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (AnimaTheme.palette.isDark) darkAccent else lightAccent)
}
