package com.ricohgr3.app.looks.emulation
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class FujiLutVerify {
    private val dir = File("src/main/assets/luts")
    private fun lut(id: String) = LutCube.parse(File(dir, "$id.cube").readText())
    private fun dev(id: String, r: Float, g: Float, b: Float): Triple<Float,Float,Float> {
        val look = FilmLookCatalog.entryFor(id)!!.look
        val rr=floatArrayOf(r); val gg=floatArrayOf(g); val bb=floatArrayOf(b)
        // colour only: skip grain/halation noise by using a look w/o them for tone check
        val bare = look.copy(grain = GrainParams.NONE, halation = HalationParams.NONE, splitTone = SplitTone.NONE)
        DevelopPipeline.apply(rr,gg,bb,1,1,bare,lut(id))
        return Triple(rr[0],gg[0],bb[0])
    }
    @Test fun allFujiLutsPresentAndParse() {
        for (id in FilmLookCatalog.ids) {
            val f = File(dir, "$id.cube")
            assertTrue("missing asset $id.cube", f.exists())
            val c = lut(id)
            assertEquals("$id size", 32, c.size)
        }
    }
    @Test fun toneIsPhotographicNotWashedOrCrushed() {
        // mid-grey (sRGB .5) must land in a sane band, not ~.7 (washed) or ~.19 (crushed).
        for (id in FilmLookCatalog.ids) {
            val (r,_,_) = dev(id, 0.5f,0.5f,0.5f)
            println("%-15s mid-grey -> %.3f".format(id, r))
            assertTrue("$id mid-grey washed ($r)", r < 0.62f)
            assertTrue("$id mid-grey crushed ($r)", r > 0.30f)
        }
    }
    @Test fun looksAreDifferentiated() {
        // velvia (vivid) must be more saturated than pro_neg_std (soft) on a red patch.
        fun sep(id:String):Float { val (r,g,_)=dev(id,0.7f,0.3f,0.3f); return r-g }
        val velvia = sep("velvia"); val soft = sep("pro_neg_std")
        println("red-green sep: velvia=$velvia pro_neg_std=$soft")
        assertTrue("velvia should be punchier than pro neg std", velvia > soft)
    }
    @Test fun bleachBypassIsLowSaturation() {
        val (r,g,b) = dev("bleach_bypass", 0.7f,0.25f,0.25f)
        val (r2,g2,_) = dev("velvia", 0.7f,0.25f,0.25f)
        println("bleach r-g=${r-g}  velvia r-g=${r2-g2}")
        assertTrue("bleach bypass desaturated vs velvia", (r-g) < (r2-g2))
    }
}
