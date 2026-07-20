package com.ricohgr3.app.looks

import com.ricohgr3.app.looks.emulation.RenderingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the [CameraLook] -> `effect` mapping to the exact spec strings (PHASE7-LOOKS.md).
 * These values are sent to real GR III hardware, so any drift must fail the build.
 */
class CameraLookTest {

    @Test
    fun standardIsUnset() {
        assertNull("Standard must be unset (no effect sent)", CameraLook.STANDARD.effect)
        assertNull(CameraLook.STANDARD.toEffectParam())
        assertNull(
            "Standard's CaptureParams must drop the effect field",
            CameraLook.STANDARD.toCaptureParams().toFormFields()["effect"],
        )
    }

    @Test
    fun effectStringsMatchSpecExactly() {
        val expected = mapOf(
            CameraLook.STANDARD to null,
            CameraLook.VIVID to "col_vivid",
            CameraLook.POSITIVE_FILM to "efc_posiFilm",
            CameraLook.BLEACH_BYPASS to "efc_bleachBypass",
            CameraLook.RETRO to "efc_retro",
            CameraLook.HDR_TONE to "efc_HDRTone",
            CameraLook.MONOCHROME to "efc_monochrome",
            CameraLook.SOFT_MONOCHROME to "efc_softMonochrome",
            CameraLook.HARD_MONOCHROME to "efc_hardMonochrome",
            CameraLook.HIGH_CONTRAST to "efc_highContrast",
            CameraLook.CUSTOM1 to "col_custom1",
            CameraLook.CUSTOM2 to "col_custom2",
        )
        // Every enum constant is covered, and no unexpected constants exist.
        assertEquals(expected.keys, CameraLook.entries.toSet())
        for ((look, effect) in expected) {
            assertEquals("effect for $look", effect, look.effect)
            assertEquals("toEffectParam for $look", effect, look.toEffectParam())
        }
    }

    @Test
    fun toCaptureParamsCarriesEffectForNonStandard() {
        assertEquals(
            "efc_posiFilm",
            CameraLook.POSITIVE_FILM.toCaptureParams().toFormFields()["effect"],
        )
        assertEquals(
            "col_custom2",
            CameraLook.CUSTOM2.toCaptureParams().toFormFields()["effect"],
        )
    }

    @Test
    fun fromEffectRoundTripsAndFallsBackToStandard() {
        for (look in CameraLook.entries) {
            assertEquals(look, CameraLook.fromEffect(look.effect))
        }
        assertEquals(CameraLook.STANDARD, CameraLook.fromEffect(null))
        assertEquals(CameraLook.STANDARD, CameraLook.fromEffect("off"))
        assertEquals(CameraLook.STANDARD, CameraLook.fromEffect("not_a_real_effect"))
    }

    @Test
    fun displayNamesArePresent() {
        for (look in CameraLook.entries) {
            assertTrue("display name for $look", look.displayName.isNotBlank())
        }
        assertEquals("Positive Film", CameraLook.POSITIVE_FILM.displayName)
    }

    @Test
    fun preferenceCodecRoundTripsFilmStockIds() {
        // The sticky store now persists film-stock ids (null = Standard).
        for (id in com.ricohgr3.app.looks.emulation.FilmLookCatalog.ids) {
            assertEquals(id, LookPreferenceCodec.decode(LookPreferenceCodec.encode(id)))
        }
        // Standard (null) round-trips through the empty string.
        assertEquals(null, LookPreferenceCodec.decode(LookPreferenceCodec.encode(null)))
        assertEquals(null, LookPreferenceCodec.decode(null))
        // An unknown/removed stock id degrades to Standard (null), never crashes.
        assertEquals(null, LookPreferenceCodec.decode("GONE_STOCK"))
        assertFalse(LookPreferenceCodec.encode("velvia").isBlank())
    }

    @Test
    fun renderingIntentPreferenceCodecIsMigrationSafe() {
        for (intent in RenderingIntent.entries) {
            assertEquals(
                intent,
                LookPreferenceCodec.decodeRenderingIntent(
                    LookPreferenceCodec.encodeRenderingIntent(intent),
                ),
            )
        }
        assertEquals(RenderingIntent.SMART, LookPreferenceCodec.decodeRenderingIntent(null))
        assertEquals(RenderingIntent.SMART, LookPreferenceCodec.decodeRenderingIntent("future"))
    }
}
