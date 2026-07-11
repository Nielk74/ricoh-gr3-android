package com.ricohgr3.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

private val LocalGrColors = staticCompositionLocalOf { LightGrColors }

/** Accessor for the extended Contact-Sheet tokens: `GrTheme.colors.paper`, etc. */
object GrTheme {
    val colors: GrColors
        @Composable get() = LocalGrColors.current
}

/**
 * App theme. Mostly-white "paper" — the app is LIGHT ONLY and never renders in
 * dark mode, regardless of the system setting. Ricoh red is the sole accent.
 */
@Composable
fun GrTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Light background -> dark status/navigation bar icons for contrast.
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = true
            insets.isAppearanceLightNavigationBars = true
        }
    }

    CompositionLocalProvider(LocalGrColors provides LightGrColors) {
        MaterialTheme(
            colorScheme = LightColorScheme,
            typography = GrTypography,
            content = content,
        )
    }
}
