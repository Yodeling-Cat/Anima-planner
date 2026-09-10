package lux.uno.anima.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tokens Material's scheme has no slot for: the quiet greys, the rise/fall pair for money,
 * and the hairlines that carry most of the structure in this UI.
 */
@Immutable
data class AnimaPalette(
    val muted: Color,
    val faint: Color,
    val hairline: Color,
    val elevated: Color,
    val rise: Color,
    val fall: Color,
    val warning: Color,
    val isDark: Boolean,
)

private val LightPalette = AnimaPalette(
    muted = Color(0xFF6F6862),
    faint = Color(0xFF9C948C),
    hairline = Color(0x14100C06),
    elevated = Color(0xFFFFFFFF),
    rise = Color(0xFFB4472F),
    fall = Color(0xFF2E6F5E),
    warning = Color(0xFF9A6B17),
    isDark = false,
)

private val DarkPalette = AnimaPalette(
    muted = Color(0xFF9E9891),
    faint = Color(0xFF6E6862),
    hairline = Color(0x1AFFFFFF),
    elevated = Color(0xFF201E24),
    rise = Color(0xFFE28E76),
    fall = Color(0xFF6FC5A8),
    warning = Color(0xFFE0B658),
    isDark = true,
)

val LocalAnimaPalette = staticCompositionLocalOf { LightPalette }

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6F5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E8E1),
    onPrimaryContainer = Color(0xFF10312A),
    secondary = Color(0xFF4A5578),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F6F2),
    onBackground = Color(0xFF1A1714),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1714),
    surfaceVariant = Color(0xFFEFEAE3),
    onSurfaceVariant = Color(0xFF57504A),
    outline = Color(0xFFD9D2C9),
    error = Color(0xFFB4472F),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FC5A8),
    onPrimary = Color(0xFF06231C),
    primaryContainer = Color(0xFF1D4A3F),
    onPrimaryContainer = Color(0xFFCDEDE1),
    secondary = Color(0xFF9AA6D0),
    onSecondary = Color(0xFF11182F),
    background = Color(0xFF131216),
    onBackground = Color(0xFFF1EDE7),
    surface = Color(0xFF1A181D),
    onSurface = Color(0xFFF1EDE7),
    surfaceVariant = Color(0xFF272429),
    onSurfaceVariant = Color(0xFFB9B2AB),
    outline = Color(0xFF3A363D),
    error = Color(0xFFE28E76),
    onError = Color(0xFF3A140C),
)

private val AnimaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/** Tightened tracking on the large sizes; generous line height on body copy. */
private val AnimaTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontSize = 54.sp, lineHeight = 58.sp, letterSpacing = (-1.8).sp, fontWeight = FontWeight.Medium,
        ),
        displayMedium = base.displayMedium.copy(
            fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-1.2).sp, fontWeight = FontWeight.Medium,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp, fontWeight = FontWeight.Medium,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp, fontWeight = FontWeight.Medium,
        ),
        titleMedium = base.titleMedium.copy(
            fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.1).sp, fontWeight = FontWeight.Medium,
        ),
        titleSmall = base.titleSmall.copy(
            fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
        ),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.Medium),
    )
}

/** The style used for section headers — small, spaced, uppercase. */
val EyebrowStyle: TextStyle
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp)

@Composable
fun AnimaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalAnimaPalette provides if (darkTheme) DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AnimaTypography,
            shapes = AnimaShapes,
        ) {
            // Screens paint their own background instead of sitting on a Surface, so the
            // default content colour has to be set here or text falls back to black.
            CompositionLocalProvider(
                LocalContentColor provides colorScheme.onBackground,
                content = content,
            )
        }
    }
}

/** Shorthand for the extra tokens: `AnimaTheme.palette.muted`. */
object AnimaTheme {
    val palette: AnimaPalette
        @Composable @ReadOnlyComposable
        get() = LocalAnimaPalette.current
}
