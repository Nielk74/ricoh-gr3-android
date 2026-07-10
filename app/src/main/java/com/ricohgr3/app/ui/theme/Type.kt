package com.ricohgr3.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Two type roles from Concept A:
 *  - a light grotesk (system sans) for the few real words and large display,
 *  - a monospace "camera-OSD / film-rebate" face for all chrome: frame numbers,
 *    ISO/shutter/focal metadata, and uppercase labels.
 *
 * We use system families (no bundled webfont) to keep the APK lean; the *treatment*
 * — weight, tracking, case — carries the identity. Swap [Mono]/[Sans] for bundled
 * fonts later without touching call sites.
 */
val Sans = FontFamily.SansSerif
val Mono = FontFamily.Monospace

/** Uppercase, wide-tracked mono — the "film-edge rebate" label used throughout. */
val LabelMono = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    letterSpacing = 1.6.sp, // ~.14em at this size
)

/** Metadata readout (ISO 400 · 1/250 · 28mm) — mono, tabular feel. */
val MetaMono = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    letterSpacing = 0.5.sp,
)

val GrTypography = Typography(
    // Large, light display — "Your frames, developed."
    displaySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Light,
        fontSize = 44.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // Reused for mono labels via labelSmall.
    labelSmall = LabelMono,
)
