package com.ricohgr3.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette for the "Contact Sheet" design language (Concept A): mostly-white paper,
 * warm near-black ink, and the Ricoh signature red used *only* as a status/record
 * accent (live dot, "edited" mark, the applied look). The photographs are the color;
 * the chrome stays quiet.
 *
 * Neutrals carry a faint warm (paper) bias rather than pure grey, so the UI reads as
 * chosen rather than defaulted. Values mirror the approved artifact concept-a.
 */

// ---- Ricoh red accent ------------------------------------------------------
val RicohRed = Color(0xFFD5202A)
val RicohRedPressed = Color(0xFFB51A22)
/** 8% red on paper — the subtle fill behind a selected/applied look. */
val RicohRedWash = Color(0x14D5202A)

// ---- Light ("paper") -------------------------------------------------------
val PaperLight = Color(0xFFFBFAF8)       // primary ground
val PaperEdgeLight = Color(0xFFF1EFE9)   // recessed / secondary ground
val FrameLight = Color(0xFFE9E6DE)       // photo placeholder / film frame
val InkLight = Color(0xFF16150F)         // primary text
val InkSoftLight = Color(0xFF55524A)     // secondary text
val GreyLight = Color(0xFF8A867C)        // captions, metadata labels
val HairLight = Color(0xFFDEDBD2)        // hairline dividers / outlines

// ---- Dark ("safelight") ----------------------------------------------------
val PaperDark = Color(0xFF14130F)
val PaperEdgeDark = Color(0xFF1C1B15)
val FrameDark = Color(0xFF201E18)
val InkDark = Color(0xFFF3F1E8)
val InkSoftDark = Color(0xFFB7B3A6)
val GreyDark = Color(0xFF85806F)
val HairDark = Color(0xFF2C2A22)

// ---- Semantic (state) colors, separate from the accent --------------------
val GoodLight = Color(0xFF4F7A4A)
val GoodDark = Color(0xFF8FBF88)
