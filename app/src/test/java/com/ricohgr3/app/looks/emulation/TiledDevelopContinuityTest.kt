package com.ricohgr3.app.looks.emulation

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class TiledDevelopContinuityTest {
    @Test
    fun `one whole-frame skin mask stays continuous when a face crosses a tile join`() {
        val width = 320
        val height = 200
        val sourceR = FloatArray(width * height) { 0.24f }
        val sourceG = FloatArray(width * height) { 0.25f }
        val sourceB = FloatArray(width * height) { 0.27f }
        for (y in 45..154) {
            for (x in 112..218) {
                val index = y * width + x
                sourceR[index] = 0.58f
                sourceG[index] = 0.36f
                sourceB[index] = 0.25f
            }
        }
        val face = FaceRegion(
            left = 104f / width,
            top = 36f / height,
            right = 226f / width,
            bottom = 164f / height,
        )
        val globalMask = SkinTone.detect(
            sourceR,
            sourceG,
            sourceB,
            width,
            height,
            listOf(face),
        )
        assertTrue(globalMask.hasSkin)

        val entry = FilmLookCatalog.entryFor("portra400")!!
        val look = entry.look.copy(
            adaptive = AdaptiveParams.NONE,
            colorBalance = FilmColorBalance.UNSPECIFIED,
            imageStructure = ImageStructureParams.NONE,
            halation = HalationParams.NONE,
            grain = GrainParams.NONE,
            foliageTone = FoliageToneParams.NONE,
            skyTone = SkyToneParams.NONE,
            whitePointRecovery = WhitePointRecoveryParams.NONE,
        )
        val lut = FilmLutFactory.build(entry.model)
        val fullR = sourceR.copyOf()
        val fullG = sourceG.copyOf()
        val fullB = sourceB.copyOf()
        DevelopPipeline.apply(
            fullR,
            fullG,
            fullB,
            width,
            height,
            look,
            lut,
            faceRegions = listOf(face),
        )

        val tiledR = FloatArray(width * height)
        val tiledG = FloatArray(width * height)
        val tiledB = FloatArray(width * height)
        renderRegion(
            sourceR, sourceG, sourceB,
            tiledR, tiledG, tiledB,
            width, height,
            decodeLeft = 0, decodeRight = 184,
            coreLeft = 0, coreRight = 160,
            look, lut, sceneProfile = null, renderSeed = 0L,
            skinMask = globalMask,
        )
        renderRegion(
            sourceR, sourceG, sourceB,
            tiledR, tiledG, tiledB,
            width, height,
            decodeLeft = 136, decodeRight = width,
            coreLeft = 160, coreRight = width,
            look, lut, sceneProfile = null, renderSeed = 0L,
            skinMask = globalMask,
        )

        for (index in fullR.indices) {
            assertTrue(abs(fullR[index] - tiledR[index]) < 1e-6f)
            assertTrue(abs(fullG[index] - tiledG[index]) < 1e-6f)
            assertTrue(abs(fullB[index] - tiledB[index]) < 1e-6f)
        }
    }

    @Test
    fun `full-height regions preserve top-connected sky and reject lower blue objects`() {
        val width = 40
        val height = 24
        val sourceR = FloatArray(width * height) { 0.32f }
        val sourceG = FloatArray(width * height) { 0.31f }
        val sourceB = FloatArray(width * height) { 0.30f }
        for (y in 0..7) {
            for (x in 0 until width) {
                val index = y * width + x
                sourceR[index] = 0.16f
                sourceG[index] = 0.48f
                sourceB[index] = 0.86f
            }
        }
        for (y in 14..18) {
            for (x in 26..34) {
                val index = y * width + x
                sourceR[index] = 0.16f
                sourceG[index] = 0.48f
                sourceB[index] = 0.86f
            }
        }

        val fullR = sourceR.copyOf()
        val fullG = sourceG.copyOf()
        val fullB = sourceB.copyOf()
        DevelopPipeline.applySkyCyanShift(fullR, fullG, fullB, width, height, 0.25f, 0.20f)

        val tiledR = FloatArray(width * height)
        val tiledG = FloatArray(width * height)
        val tiledB = FloatArray(width * height)
        applySkyRegion(sourceR, sourceG, sourceB, tiledR, tiledG, tiledB, width, height, 0, 24, 0, 20)
        applySkyRegion(sourceR, sourceG, sourceB, tiledR, tiledG, tiledB, width, height, 16, 40, 20, 40)

        for (index in fullR.indices) {
            assertTrue(abs(fullR[index] - tiledR[index]) < 1e-6f)
            assertTrue(abs(fullG[index] - tiledG[index]) < 1e-6f)
            assertTrue(abs(fullB[index] - tiledB[index]) < 1e-6f)
        }
        val sky = 3 * width + 10
        val blueObject = 16 * width + 30
        assertTrue("connected sky should move", abs(fullG[sky] - sourceG[sky]) > 1e-3f)
        assertTrue("disconnected blue object should stay unchanged", abs(fullG[blueObject] - sourceG[blueObject]) < 1e-6f)
    }

    @Test
    fun `overlapped vertical regions match whole-frame spatial rendering at their join`() {
        val width = 320
        val height = 200
        val sourceR = FloatArray(width * height)
        val sourceG = FloatArray(width * height)
        val sourceB = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val horizontal = x.toFloat() / (width - 1)
                val vertical = y.toFloat() / (height - 1)
                val highlight = if (x in 150..170 && y in 72..128) 0.42f else 0f
                sourceR[index] = (0.10f + 0.72f * horizontal + highlight).coerceAtMost(1f)
                sourceG[index] = (0.08f + 0.48f * vertical + highlight).coerceAtMost(1f)
                sourceB[index] = (0.16f + 0.38f * (1f - horizontal) + highlight).coerceAtMost(1f)
            }
        }

        val entry = FilmLookCatalog.entryFor("cinestill800t")!!
        val look = entry.look.copy(
            skinTone = SkinToneParams.NONE,
            foliageTone = FoliageToneParams.NONE,
            skyTone = SkyToneParams.NONE,
        )
        val lut = FilmLutFactory.build(entry.model)
        val sceneProfile = DevelopPipeline.analyzeScene(
            sourceR.copyOf(),
            sourceG.copyOf(),
            sourceB.copyOf(),
            width,
            height,
        )
        val renderSeed = stableRenderSeed("continuous-tile-test")
        val fullR = sourceR.copyOf()
        val fullG = sourceG.copyOf()
        val fullB = sourceB.copyOf()
        DevelopPipeline.apply(
            fullR,
            fullG,
            fullB,
            width,
            height,
            look,
            lut,
            iso = 800,
            options = DevelopOptions(
                sceneProfile = sceneProfile,
                renderSeed = renderSeed,
                spatialLongEdgePixels = width,
            ),
        )

        val tiledR = FloatArray(width * height)
        val tiledG = FloatArray(width * height)
        val tiledB = FloatArray(width * height)
        renderRegion(
            sourceR, sourceG, sourceB,
            tiledR, tiledG, tiledB,
            width, height,
            decodeLeft = 0, decodeRight = 184,
            coreLeft = 0, coreRight = 160,
            look, lut, sceneProfile, renderSeed,
        )
        renderRegion(
            sourceR, sourceG, sourceB,
            tiledR, tiledG, tiledB,
            width, height,
            decodeLeft = 136, decodeRight = width,
            coreLeft = 160, coreRight = width,
            look, lut, sceneProfile, renderSeed,
        )

        var maximumError = 0f
        var joinError = 0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val error = maxOf(
                    abs(fullR[index] - tiledR[index]),
                    abs(fullG[index] - tiledG[index]),
                    abs(fullB[index] - tiledB[index]),
                )
                maximumError = maxOf(maximumError, error)
                if (x in 158..161) joinError = maxOf(joinError, error)
            }
        }
        assertTrue("tile join error was $joinError", joinError < 2e-4f)
        assertTrue("maximum tile error was $maximumError", maximumError < 2e-4f)
    }

    private fun renderRegion(
        sourceR: FloatArray,
        sourceG: FloatArray,
        sourceB: FloatArray,
        destinationR: FloatArray,
        destinationG: FloatArray,
        destinationB: FloatArray,
        fullWidth: Int,
        fullHeight: Int,
        decodeLeft: Int,
        decodeRight: Int,
        coreLeft: Int,
        coreRight: Int,
        look: FilmLook,
        lut: LutCube,
        sceneProfile: SceneProfile?,
        renderSeed: Long,
        skinMask: SkinMask? = null,
    ) {
        val regionWidth = decodeRight - decodeLeft
        val r = FloatArray(regionWidth * fullHeight)
        val g = FloatArray(regionWidth * fullHeight)
        val b = FloatArray(regionWidth * fullHeight)
        for (y in 0 until fullHeight) {
            sourceR.copyInto(r, y * regionWidth, y * fullWidth + decodeLeft, y * fullWidth + decodeRight)
            sourceG.copyInto(g, y * regionWidth, y * fullWidth + decodeLeft, y * fullWidth + decodeRight)
            sourceB.copyInto(b, y * regionWidth, y * fullWidth + decodeLeft, y * fullWidth + decodeRight)
        }
        val fullLongEdgeMillimetres = 36f
        val regionLongEdge = maxOf(regionWidth, fullHeight)
        DevelopPipeline.apply(
            r,
            g,
            b,
            regionWidth,
            fullHeight,
            look,
            lut,
            iso = 800,
            options = DevelopOptions(
                sceneProfile = sceneProfile,
                renderSeed = renderSeed,
                spatialLongEdgePixels = fullWidth,
                filmPlane = PhysicalFilmGrain.FilmPlane(
                    longEdgeMillimeters = fullLongEdgeMillimetres,
                    originXMillimeters = decodeLeft.toFloat() / fullWidth * fullLongEdgeMillimetres,
                    framingScale = regionLongEdge.toFloat() / fullWidth,
                ),
                skinMaskMapping = skinMask?.let { mask ->
                    SkinMaskMapping(
                        mask = mask,
                        originX = decodeLeft,
                        originY = 0,
                        fullWidth = fullWidth,
                        fullHeight = fullHeight,
                    )
                },
            ),
        )
        for (y in 0 until fullHeight) {
            for (x in coreLeft until coreRight) {
                val sourceIndex = y * regionWidth + x - decodeLeft
                val destinationIndex = y * fullWidth + x
                destinationR[destinationIndex] = r[sourceIndex]
                destinationG[destinationIndex] = g[sourceIndex]
                destinationB[destinationIndex] = b[sourceIndex]
            }
        }
    }

    private fun applySkyRegion(
        sourceR: FloatArray,
        sourceG: FloatArray,
        sourceB: FloatArray,
        destinationR: FloatArray,
        destinationG: FloatArray,
        destinationB: FloatArray,
        fullWidth: Int,
        fullHeight: Int,
        decodeLeft: Int,
        decodeRight: Int,
        coreLeft: Int,
        coreRight: Int,
    ) {
        val regionWidth = decodeRight - decodeLeft
        val r = FloatArray(regionWidth * fullHeight)
        val g = FloatArray(regionWidth * fullHeight)
        val b = FloatArray(regionWidth * fullHeight)
        for (y in 0 until fullHeight) {
            sourceR.copyInto(r, y * regionWidth, y * fullWidth + decodeLeft, y * fullWidth + decodeRight)
            sourceG.copyInto(g, y * regionWidth, y * fullWidth + decodeLeft, y * fullWidth + decodeRight)
            sourceB.copyInto(b, y * regionWidth, y * fullWidth + decodeLeft, y * fullWidth + decodeRight)
        }
        DevelopPipeline.applySkyCyanShift(r, g, b, regionWidth, fullHeight, 0.25f, 0.20f)
        for (y in 0 until fullHeight) {
            for (x in coreLeft until coreRight) {
                val sourceIndex = y * regionWidth + x - decodeLeft
                val destinationIndex = y * fullWidth + x
                destinationR[destinationIndex] = r[sourceIndex]
                destinationG[destinationIndex] = g[sourceIndex]
                destinationB[destinationIndex] = b[sourceIndex]
            }
        }
    }
}
