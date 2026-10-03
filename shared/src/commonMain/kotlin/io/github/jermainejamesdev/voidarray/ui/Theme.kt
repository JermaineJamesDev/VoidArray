package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jermainejamesdev.voidarray.core.ThemeMode
import org.jetbrains.compose.resources.Font
import voidarray.shared.generated.resources.Res
import voidarray.shared.generated.resources.cinzel

// "Void & Jade": ink-black void, jade primary, antique-gold secondary, cinnabar error. The light variant is
// aged rice paper with deeper jade and gold. Every surface-container role is set explicitly because
// Material3's defaults are purple-tinted neutrals that clash with both palettes.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FC9A3),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0F4D3B),
    onPrimaryContainer = Color(0xFFA8F0D2),
    secondary = Color(0xFFC9A45C),
    onSecondary = Color(0xFF3B2A00),
    secondaryContainer = Color(0xFF4A3A16),
    onSecondaryContainer = Color(0xFFF2DDAA),
    tertiary = Color(0xFF8FA8D8),
    onTertiary = Color(0xFF0E2348),
    tertiaryContainer = Color(0xFF26344F),
    onTertiaryContainer = Color(0xFFD6E2FF),
    error = Color(0xFFE06A64),
    onError = Color(0xFF3A0806),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFD9D5),
    background = Color(0xFF0B0D14),
    onBackground = Color(0xFFE8E4D8),
    surface = Color(0xFF0B0D14),
    onSurface = Color(0xFFE8E4D8),
    surfaceVariant = Color(0xFF232A3C),
    onSurfaceVariant = Color(0xFFB5B0A3),
    outline = Color(0xFF5A6175),
    outlineVariant = Color(0xFF2A3040),
    surfaceContainerLowest = Color(0xFF07080D),
    surfaceContainerLow = Color(0xFF10131C),
    surfaceContainer = Color(0xFF141824),
    surfaceContainerHigh = Color(0xFF1B2030),
    surfaceContainerHighest = Color(0xFF232A3C),
    inverseSurface = Color(0xFFE8E4D8),
    inverseOnSurface = Color(0xFF1E1B16),
    inversePrimary = Color(0xFF1F7A5C),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F7A5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBDEBD6),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF9A7426),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3DEB0),
    onSecondaryContainer = Color(0xFF2E2000),
    tertiary = Color(0xFF4A5F8A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9E2FF),
    onTertiaryContainer = Color(0xFF021A43),
    error = Color(0xFFB0322B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410001),
    background = Color(0xFFF3EDE0),
    onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFF3EDE0),
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFE5DCCA),
    onSurfaceVariant = Color(0xFF5A5346),
    outline = Color(0xFF8C8370),
    outlineVariant = Color(0xFFD6CCB8),
    surfaceContainerLowest = Color(0xFFFFFCF5),
    surfaceContainerLow = Color(0xFFFBF7EE),
    surfaceContainer = Color(0xFFF2EBDD),
    surfaceContainerHigh = Color(0xFFECE4D4),
    surfaceContainerHighest = Color(0xFFE5DCCA),
    inverseSurface = Color(0xFF33302A),
    inverseOnSurface = Color(0xFFF6F0E3),
    inversePrimary = Color(0xFF5FC9A3),
)

// Cut corners evoke jade tablets and talismans while staying cheap to draw.
private val VoidArrayShapes = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(10.dp),
    large = CutCornerShape(14.dp),
    extraLarge = CutCornerShape(20.dp),
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
fun VoidArrayTheme(mode: ThemeMode, reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val display = displayFamily()
    // Cinzel carries headings and card titles; body and labels stay in the platform font for legibility.
    val typography = remember(display) {
        val base = Typography()
        base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = display),
            displayMedium = base.displayMedium.copy(fontFamily = display),
            displaySmall = base.displaySmall.copy(fontFamily = display),
            headlineLarge = base.headlineLarge.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
            headlineMedium = base.headlineMedium.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontFamily = display, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        )
    }
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(
            colorScheme = if (isDarkTheme(mode)) DarkColors else LightColors,
            typography = typography,
            shapes = VoidArrayShapes,
            content = content,
        )
    }
}
