package com.trollman.linernotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.trollman.linernotes.R

/* =============================================================================
 *  LINER NOTES: design tokens
 *
 *  Device casings (warm light grey, or near-black), one signal orange for the main
 *  action, album art for everything else. Three faces: Doto for dot-matrix display
 *  type (32sp and up only), Allo Crisp for everything people read, IBM Plex Mono for
 *  small metadata. All three are bundled (OFL, licences/), so nothing falls back
 *  to Roboto offline. Screens use these tokens and never a raw colour or size.
 * ============================================================================= */

@Immutable
data class LinerColors(
    val dark: Boolean,
    /** The device's body: the page behind everything. */
    val casing: Color,
    /** A raised panel on the casing: cards, the deck. */
    val panel: Color,
    /** A recessed area: tracks, wells, the display window. */
    val well: Color,
    /** Highlight and shadow edges that make a control look raised. */
    val bevelLight: Color,
    val bevelDark: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    /** The one accent: the main action, an indicator LED. */
    val signal: Color,
    val onSignal: Color,
    /** Vinyl. */
    val record: Color,
    val groove: Color,
    /** The dot-matrix display window: always dark, like a real one. */
    val display: Color,
    val onDisplay: Color,
)

val LightLiner = LinerColors(
    dark = false,
    casing = Color(0xFFE6E4E0),
    panel = Color(0xFFF2F1EE),
    well = Color(0xFFD9D7D2),
    bevelLight = Color(0xFFFFFFFF),
    bevelDark = Color(0xFFB9B6B0),
    ink = Color(0xFF141414),
    inkSoft = Color(0xFF4A4845),
    inkFaint = Color(0xFF6E6B66),
    signal = Color(0xFFE8501A),
    onSignal = Color(0xFFFFFFFF),
    record = Color(0xFF111111),
    groove = Color(0xFF262626),
    display = Color(0xFF151515),
    onDisplay = Color(0xFFF2F1EE),
)

val DarkLiner = LinerColors(
    dark = true,
    casing = Color(0xFF121212),
    panel = Color(0xFF1D1D1D),
    well = Color(0xFF0A0A0A),
    bevelLight = Color(0xFF2E2E2E),
    bevelDark = Color(0xFF000000),
    ink = Color(0xFFEDEBE7),
    inkSoft = Color(0xFFB8B5AF),
    inkFaint = Color(0xFF8E8B85),
    signal = Color(0xFFFF6A2E),
    onSignal = Color(0xFF140A05),
    record = Color(0xFF050505),
    groove = Color(0xFF1C1C1C),
    display = Color(0xFF000000),
    onDisplay = Color(0xFFF2F1EE),
)

val LocalLiner = staticCompositionLocalOf { LightLiner }

/** The current colour tokens. */
val liner: LinerColors @Composable get() = LocalLiner.current

// ---- Type ----------------------------------------------------------------------------------

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun allo(weight: FontWeight) = Font(
    R.font.allo_crisp_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val AlloFamily = FontFamily(
    allo(FontWeight.Normal), allo(FontWeight.Medium), allo(FontWeight.SemiBold),
    allo(FontWeight.Bold), allo(FontWeight.ExtraBold),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun doto(weight: FontWeight) = Font(
    R.font.doto_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        // Rounded dots, like an LED matrix rather than a square pixel grid.
        FontVariation.Setting("ROND", 100f),
    ),
)

val DotoFamily = FontFamily(doto(FontWeight.Medium), doto(FontWeight.Bold), doto(FontWeight.Black))

val PlexMonoFamily = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

private fun alloStyle(size: Int, weight: FontWeight, line: Int = (size * 1.35f).toInt(), tracking: Float = 0f) =
    TextStyle(fontFamily = AlloFamily, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

/** Every Material slot filled with Allo Crisp, so no Roboto sneaks in (Kerasma learned this). */
private val LinerTypography = Typography(
    displayLarge = alloStyle(40, FontWeight.ExtraBold, 44, -1f),
    displayMedium = alloStyle(34, FontWeight.ExtraBold, 38, -0.8f),
    displaySmall = alloStyle(28, FontWeight.ExtraBold, 32, -0.6f),
    headlineLarge = alloStyle(26, FontWeight.ExtraBold, 30, -0.5f),
    headlineMedium = alloStyle(22, FontWeight.ExtraBold, 27, -0.4f),
    headlineSmall = alloStyle(19, FontWeight.Bold, 24, -0.2f),
    titleLarge = alloStyle(20, FontWeight.Bold, 25, -0.2f),
    titleMedium = alloStyle(17, FontWeight.Bold, 22),
    titleSmall = alloStyle(15, FontWeight.SemiBold, 20),
    // Notes and descriptions: 16sp and up (SPEC.md, "Legible body copy").
    bodyLarge = alloStyle(17, FontWeight.Normal, 25),
    bodyMedium = alloStyle(16, FontWeight.Normal, 23),
    bodySmall = alloStyle(14, FontWeight.Normal, 20),
    labelLarge = alloStyle(15, FontWeight.SemiBold, 20, 0.1f),
    labelMedium = alloStyle(13, FontWeight.SemiBold, 17, 0.2f),
    labelSmall = alloStyle(12, FontWeight.Medium, 16, 0.3f),
)

/** Dot-matrix display type. Only at 32sp and up, where the dots read as letters. */
fun dotMatrix(size: TextUnit = 48.sp, weight: FontWeight = FontWeight.Bold) = TextStyle(
    fontFamily = DotoFamily,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size * 0.92f,
    letterSpacing = 0.5.sp,
)

/** Small metadata in monospace capitals: year, play counts, labels. */
fun mono(size: TextUnit = 12.sp, weight: FontWeight = FontWeight.Medium) = TextStyle(
    fontFamily = PlexMonoFamily,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size * 1.35f,
    letterSpacing = 0.8.sp,
)

@Composable
fun LinerNotesTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkLiner else LightLiner
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.signal, onPrimary = c.onSignal,
            secondary = c.ink, onSecondary = c.casing,
            background = c.casing, onBackground = c.ink,
            surface = c.casing, onSurface = c.ink,
            surfaceVariant = c.well, onSurfaceVariant = c.inkSoft,
            surfaceContainer = c.panel, surfaceContainerHigh = c.panel, surfaceContainerLow = c.panel,
            outline = c.inkFaint, outlineVariant = c.bevelDark,
        )
    } else {
        lightColorScheme(
            primary = c.signal, onPrimary = c.onSignal,
            secondary = c.ink, onSecondary = c.casing,
            background = c.casing, onBackground = c.ink,
            surface = c.casing, onSurface = c.ink,
            surfaceVariant = c.well, onSurfaceVariant = c.inkSoft,
            surfaceContainer = c.panel, surfaceContainerHigh = c.panel, surfaceContainerLow = c.panel,
            outline = c.inkFaint, outlineVariant = c.bevelDark,
        )
    }
    CompositionLocalProvider(LocalLiner provides c) {
        MaterialTheme(colorScheme = scheme, typography = LinerTypography, content = content)
    }
}
