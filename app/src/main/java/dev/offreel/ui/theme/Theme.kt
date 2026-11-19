package dev.offreel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The offreel palette from the brand sheet. Ember is the one accent, used sparingly. */
object Brand {
    val Ink = Color(0xFF0F1014)
    val Surface = Color(0xFF181A20)
    val Line = Color(0xFF2A2D36)
    val Muted = Color(0xFFA9ABB5)
    val Cream = Color(0xFFF4EFE6)
    val Ember = Color(0xFFFF5A36)

    // Light-theme companions (Muted is too faint on Cream, so light mode uses a darker grey).
    val CreamSurface = Color(0xFFFBF8F2)
    val CreamLine = Color(0xFFE2DBCF)
    val InkMuted = Color(0xFF5F616B)
}

/** Extra colours Material's scheme has no slot for. */
@Immutable
data class OffreelColors(
    /** Play triangle, progress, the single call to action. Never text on light backgrounds. */
    val accent: Color,
    val onAccent: Color,
    val muted: Color,
    val line: Color,
    val isDark: Boolean,
)

val LocalOffreelColors = staticCompositionLocalOf {
    OffreelColors(Brand.Ember, Brand.Ink, Brand.Muted, Brand.Line, true)
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Brand.Cream,
    onPrimary = Brand.Ink,
    secondary = Brand.Muted,
    onSecondary = Brand.Ink,
    background = Brand.Ink,
    onBackground = Brand.Cream,
    surface = Brand.Ink,
    onSurface = Brand.Cream,
    surfaceVariant = Brand.Surface,
    onSurfaceVariant = Brand.Muted,
    surfaceContainer = Brand.Surface,
    surfaceContainerLow = Brand.Surface,
    surfaceContainerHigh = Color(0xFF1F2128),
    surfaceContainerHighest = Brand.Line,
    outline = Brand.Line,
    outlineVariant = Brand.Line,
    error = Color(0xFFFF8A73),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Brand.Ink,
    onPrimary = Brand.Cream,
    secondary = Brand.InkMuted,
    onSecondary = Brand.Cream,
    background = Brand.Cream,
    onBackground = Brand.Ink,
    surface = Brand.Cream,
    onSurface = Brand.Ink,
    surfaceVariant = Brand.CreamSurface,
    onSurfaceVariant = Brand.InkMuted,
    surfaceContainer = Brand.CreamSurface,
    surfaceContainerLow = Brand.CreamSurface,
    surfaceContainerHigh = Color(0xFFF1EBE0),
    surfaceContainerHighest = Brand.CreamLine,
    outline = Brand.CreamLine,
    outlineVariant = Brand.CreamLine,
    error = Color(0xFFB3261E),
)

private val OffreelTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val WordmarkStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.05).em)

enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun OffreelTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val extra = if (dark) {
        OffreelColors(Brand.Ember, Brand.Ink, Brand.Muted, Brand.Line, true)
    } else {
        OffreelColors(Brand.Ember, Brand.Ink, Brand.InkMuted, Brand.CreamLine, false)
    }
    val scheme = if (dark) DarkScheme else LightScheme
    CompositionLocalProvider(LocalOffreelColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = OffreelTypography) {
            // Text outside a Material Surface (the save sheet, the player) would otherwise default to black.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
