package lux.uno.anima.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import lux.uno.anima.resources.*
import lux.uno.anima.ui.theme.AnimaTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The five doors on the home screen. Each area owns an accent that follows it into its
 * own section, so the app always says where you are without a label.
 */
@Serializable
enum class LifeArea(
    private val titleRes: StringResource,
    private val taglineRes: StringResource,
    val glyph: String,
    private val lightAccent: Long,
    private val darkAccent: Long,
    val isReady: Boolean,
) {
    FINANCE(
        titleRes = Res.string.area_finance_title,
        taglineRes = Res.string.area_finance_tagline,
        glyph = "🪙",
        lightAccent = 0xFF2E6F5E,
        darkAccent = 0xFF6FC5A8,
        isReady = true,
    ),
    HEALTH(
        titleRes = Res.string.area_health_title,
        taglineRes = Res.string.area_health_tagline,
        glyph = "🌿",
        lightAccent = 0xFFB35A42,
        darkAccent = 0xFFE59379,
        isReady = false,
    ),
    PHILOSOPHY(
        titleRes = Res.string.area_philosophy_title,
        taglineRes = Res.string.area_philosophy_tagline,
        glyph = "🕯️",
        lightAccent = 0xFF4A5578,
        darkAccent = 0xFF9AA6D0,
        isReady = false,
    ),
    RELIGION(
        titleRes = Res.string.area_religion_title,
        taglineRes = Res.string.area_religion_tagline,
        glyph = "🕊️",
        lightAccent = 0xFF9A6B17,
        darkAccent = 0xFFE0B658,
        isReady = false,
    ),
    FUN(
        titleRes = Res.string.area_fun_title,
        taglineRes = Res.string.area_fun_tagline,
        glyph = "🎲",
        lightAccent = 0xFF8C4A6B,
        darkAccent = 0xFFD190B0,
        isReady = false,
    );

    /** The name and the one-line description, in the reader's language. */
    val title: String
        @Composable
        get() = stringResource(titleRes)

    val tagline: String
        @Composable
        get() = stringResource(taglineRes)

    /** Accent tuned for the current theme. */
    val accent: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (AnimaTheme.palette.isDark) darkAccent else lightAccent)
}
