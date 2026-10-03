package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.jermainejamesdev.voidarray.core.ThemeMode
import org.jetbrains.compose.resources.Font
import voidarray.shared.generated.resources.Res
import voidarray.shared.generated.resources.cinzel
import voidarray.shared.generated.resources.plex_mono_medium
import voidarray.shared.generated.resources.plex_mono_regular
import voidarray.shared.generated.resources.plex_sans_medium
import voidarray.shared.generated.resources.plex_sans_regular
import voidarray.shared.generated.resources.plex_sans_semibold

// "Refined Void & Jade". Surfaces step up in lightness instead of being framed by borders: the void
// ground, cards (surfaceContainerLow), rows inside cards (surfaceContainer), and controls
// (surfaceContainerHigh). Jade is the action color, gold is reserved for ornament and emphasis, and
// cinnabar marks problems. The light variant is aged paper with deeper jade and gold.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FC9A3),
    onPrimary = Color(0xFF062C20),
    primaryContainer = Color(0xFF173A31),
    onPrimaryContainer = Color(0xFF9DE6CA),
    secondary = Color(0xFFC9A45C),
    onSecondary = Color(0xFF1E1608),
    secondaryContainer = Color(0xFF2E2819),
    onSecondaryContainer = Color(0xFFE8CF98),
    tertiary = Color(0xFF8FA8D8),
    onTertiary = Color(0xFF0E2348),
    tertiaryContainer = Color(0xFF26344F),
    onTertiaryContainer = Color(0xFFD6E2FF),
    error = Color(0xFFEF7B6C),
    onError = Color(0xFF3A0806),
    errorContainer = Color(0xFF3B1D1C),
    onErrorContainer = Color(0xFFF6B3A9),
    background = Color(0xFF0A0C12),
    onBackground = Color(0xFFECE8DC),
    surface = Color(0xFF0A0C12),
    onSurface = Color(0xFFECE8DC),
    surfaceVariant = Color(0xFF222836),
    onSurfaceVariant = Color(0xFFA39E90),
    outline = Color(0xFF555B69),
    outlineVariant = Color(0xFF242A37),
    surfaceContainerLowest = Color(0xFF07080D),
    surfaceContainerLow = Color(0xFF12161F),
    surfaceContainer = Color(0xFF191E29),
    surfaceContainerHigh = Color(0xFF222836),
    surfaceContainerHighest = Color(0xFF2B3242),
    inverseSurface = Color(0xFFECE8DC),
    inverseOnSurface = Color(0xFF12161F),
    inversePrimary = Color(0xFF1B6F53),
    scrim = Color(0xFF040508),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6F53),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3EBDF),
    onPrimaryContainer = Color(0xFF0B3D2C),
    secondary = Color(0xFF8A6420),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1E2BF),
    onSecondaryContainer = Color(0xFF4A3510),
    tertiary = Color(0xFF4A5F8A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9E2FF),
    onTertiaryContainer = Color(0xFF021A43),
    error = Color(0xFFB4382C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF8DCD6),
    onErrorContainer = Color(0xFF5C1610),
    background = Color(0xFFF4EFE4),
    onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFF4EFE4),
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFECE4D4),
    onSurfaceVariant = Color(0xFF5E574A),
    outline = Color(0xFF8C8370),
    outlineVariant = Color(0xFFE2D9C6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFCF6),
    surfaceContainer = Color(0xFFF6F0E4),
    surfaceContainerHigh = Color(0xFFECE4D4),
    surfaceContainerHighest = Color(0xFFE3DAC7),
    inverseSurface = Color(0xFF2A2721),
    inverseOnSurface = Color(0xFFF6F0E3),
    inversePrimary = Color(0xFF5FC9A3),
)

/** Roles Material's color scheme has no slot for. */
@Immutable
data class VoidArrayExtras(
    /** Cautions that are not errors, such as a program file in an offer. */
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Codes, keys, addresses and sizes: tabular figures that line up and read unambiguously. */
    val mono: FontFamily,
)

private val DarkExtras = VoidArrayExtras(
    warning = Color(0xFFE8B04A),
    warningContainer = Color(0xFF332914),
    onWarningContainer = Color(0xFFF2D49A),
    mono = FontFamily.Monospace,
)

private val LightExtras = VoidArrayExtras(
    warning = Color(0xFF8A5E0A),
    warningContainer = Color(0xFFF5E4BF),
    onWarningContainer = Color(0xFF4A3205),
    mono = FontFamily.Monospace,
)

private val LocalExtras = staticCompositionLocalOf { DarkExtras }

/** Access to [VoidArrayExtras] alongside MaterialTheme, e.g. `VoidArrayTheme.extras.mono`. */
object VoidArrayTheme {
    val extras: VoidArrayExtras
        @Composable @ReadOnlyComposable get() = LocalExtras.current
}

// One faceted language for everything: cards cut at 10dp, rows and controls at 6dp, sheets at 16dp.
private val VoidArrayShapes = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(6.dp),
    large = CutCornerShape(10.dp),
    extraLarge = CutCornerShape(16.dp),
)

/** True when the platform asks for reduced motion; formation arrays then stay still. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
private fun displayFamily(): FontFamily = FontFamily(
    Font(Res.font.cinzel, FontWeight.Normal),
    Font(Res.font.cinzel, FontWeight.SemiBold),
    Font(Res.font.cinzel, FontWeight.Bold),
)

@Composable
private fun bodyFamily(): FontFamily = FontFamily(
    Font(Res.font.plex_sans_regular, FontWeight.Normal),
    Font(Res.font.plex_sans_medium, FontWeight.Medium),
    Font(Res.font.plex_sans_semibold, FontWeight.SemiBold),
    // Plex has no bold in the bundle; SemiBold stands in so Bold never falls back to a synthesized weight.
    Font(Res.font.plex_sans_semibold, FontWeight.Bold),
)

@Composable
private fun monoFamily(): FontFamily = FontFamily(
    Font(Res.font.plex_mono_regular, FontWeight.Normal),
    Font(Res.font.plex_mono_medium, FontWeight.Medium),
    Font(Res.font.plex_mono_medium, FontWeight.SemiBold),
)

private fun typography(display: FontFamily, body: FontFamily): Typography {
    val base = Typography()
    // Cinzel is reserved for screen titles and the brand; it is an all-capitals face and tires the eye
    // in anything longer, so every other role uses Plex Sans.
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = display),
        displayMedium = base.displayMedium.copy(fontFamily = display),
        displaySmall = base.displaySmall.copy(fontFamily = display),
        headlineLarge = base.headlineLarge.copy(fontFamily = display, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em),
        headlineMedium = base.headlineMedium.copy(fontFamily = display, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em),
        headlineSmall = base.headlineSmall.copy(fontFamily = display, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em),
        titleLarge = base.titleLarge.copy(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontFamily = body, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontFamily = body, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = body, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = body, letterSpacing = 0.sp),
        bodySmall = base.bodySmall.copy(fontFamily = body, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontFamily = body, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = body, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = body, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
    )
}

@Composable
fun VoidArrayTheme(mode: ThemeMode, reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val display = displayFamily()
    val body = bodyFamily()
    val mono = monoFamily()
    val dark = isDarkTheme(mode)
    val typography = remember(display, body) { typography(display, body) }
    val extras = remember(dark, mono) { (if (dark) DarkExtras else LightExtras).copy(mono = mono) }
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion, LocalExtras provides extras) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = VoidArrayShapes,
            content = content,
        )
    }
}
