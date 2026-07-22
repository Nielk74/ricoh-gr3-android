package com.ricohgr3.app.gallery

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullResolutionTilePlanTest {
    @Test
    fun `256 MiB class heap uses bounded overlapping strips covering every source column`() {
        val tiles = planFullResolutionTiles(
            width = 6_000,
            height = 4_000,
            heapHeadroomBytes = 250L * MIB,
        )

        assertEquals(9, tiles.size)
        assertEquals(0, tiles.first().core.left)
        assertEquals(6_000, tiles.last().core.right)
        tiles.forEach { tile ->
            assertEquals(0, tile.decode.top)
            assertEquals(4_000, tile.decode.bottom)
            assertTrue(tile.decode.left <= tile.core.left)
            assertTrue(tile.decode.right >= tile.core.right)
        }
        tiles.zipWithNext().forEach { (left, right) ->
            assertEquals(left.core.right, right.core.left)
            assertTrue(left.decode.right > right.decode.left)
        }
    }

    @Test
    fun `portrait frames also keep every strip connected to the real top edge`() {
        val tiles = planFullResolutionTiles(4_000, 6_000, 250L * MIB)

        assertEquals(12, tiles.size)
        assertEquals(0, tiles.first().core.left)
        assertEquals(4_000, tiles.last().core.right)
        tiles.forEach { tile ->
            assertEquals(0, tile.decode.top)
            assertEquals(6_000, tile.decode.bottom)
        }
    }

    @Test
    fun `large heap processes a 24 MP frame as one region`() {
        val tiles = planFullResolutionTiles(6_000, 4_000, 1024L * MIB)
        assertEquals(1, tiles.size)
        assertEquals(ImageRegion(0, 0, 6_000, 4_000), tiles.single().core)
    }

    @Test(expected = IOException::class)
    fun `planner fails explicitly when even the minimum safe region cannot fit`() {
        planFullResolutionTiles(6_000, 4_000, 120L * MIB)
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
