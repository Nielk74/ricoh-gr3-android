package com.ricohgr3.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extra "Contact Sheet" tokens that Material3's [androidx.compose.material3.ColorScheme]
 * has no slot for (paper vs. edge grounds, ink tiers, hairlines, the red wash, and the
 * semantic "good" color). Reach them via [GrTheme.colors] inside composables.
 */
@Immutable
data class GrColors(
    val paper: Color,
    val paperEdge: Color,
    val frame: Color,
    val ink: Color,
    val inkSoft: Color,
    val grey: Color,
    val hair: Color,
    val accent: Color,
    val accentPressed: Color,
    val accentWash: Color,
    val good: Color,
)

private val LightGrColors = GrColors(
    paper = PaperLight,
    paperEdge = PaperEdgeLight,
    frame = FrameLight,
    ink = InkLight,
    inkSoft = InkSoftLight,
    grey = GreyLight,
    hair = HairLight,
    accent = RicohRed,
    accentPressed = RicohRedPressed,
    accentWash = RicohRedWash,
    good = GoodLight,
)

private val DarkGrColors = GrColors(
    paper = PaperDark,
    paperEdge = PaperEdgeDark,
    frame = FrameDark,
    ink = InkDark,
    inkSoft = InkSoftDark,
    grey = GreyDark,
    hair = HairDark,
    accent = RicohRed,
    accentPressed = RicohRedPressed,
    accentWash = RicohRedWash,
    good = GoodDark,
)

private val LightColorScheme = lightColorScheme(
    primary = RicohRed,
    onPrimary = Color.White,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = PaperEdgeLight,
    onSurfaceVariant = InkSoftLight,
    outline = HairLight,
    outlineVariant = HairLight,
    error = RicohRed,
)

private val DarkColorScheme = darkColorScheme(
    primary = RicohRed,
    onPrimary = Color.White,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = PaperEdgeDark,
    onSurfaceVariant = InkSoftDark,
    outline = HairDark,
    outlineVariant = HairDark,
    error = RicohRed,
)

private val LocalGrColors = staticCompositionLocalOf { LightGrColors }

/** Accessor for the extended Contact-Sheet tokens: `GrTheme.colors.paper`, etc. */
object GrTheme {
    val colors: GrColors
        @Composable get() = LocalGrColors.current
}

/**
 * App theme. Mostly-white "paper" in light, warm near-black "safelight" in dark;
 * Ricoh red is the sole accent in both. Honors the system/viewer theme.
 */
@Composable
fun GrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val grColors = if (darkTheme) DarkGrColors else LightGrColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalGrColors provides grColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GrTypography,
            content = content,
        )
    }
}
