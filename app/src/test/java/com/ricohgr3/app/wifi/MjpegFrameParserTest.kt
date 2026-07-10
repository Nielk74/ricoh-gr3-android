package com.ricohgr3.app.wifi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class MjpegFrameParserTest {

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** Build one fake JPEG frame with SOI, [payload] body, EOI. */
    private fun jpeg(payload: ByteArray): ByteArray = soi + payload + eoi

    /** Wrap frames in a synthetic multipart/x-mixed-replace stream with boundaries + part headers. */
    private fun multipart(vararg frames: ByteArray, boundary: String = "--boundarydonotcross"): ByteArray {
        var out = ByteArray(0)
        for (f in frames) {
            val header = "$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n"
            out += header.toByteArray(Charsets.US_ASCII)
            out += f
            out += "\r\n".toByteArray(Charsets.US_ASCII)
        }
        return out
    }

    @Test
    fun extractsMultipleFramesFromSyntheticMultipartStream() {
        val f1 = jpeg(byteArrayOf(1, 2, 3, 4))
        val f2 = jpeg(byteArrayOf(9, 8, 7))
        val stream = multipart(f1, f2)

        val result = MjpegFrameParser.extractFrames(stream)

        assertEquals(2, result.frames.size)
        assertArrayEquals(f1, result.frames[0])
        assertArrayEquals(f2, result.frames[1])
    }

    @Test
    fun eachFrameStartsWithSoiAndEndsWithEoi() {
        val stream = multipart(jpeg(byteArrayOf(0x42)))
        val frame = MjpegFrameParser.extractFrames(stream).frames.single()
        assertEquals(0xFF.toByte(), frame[0])
        assertEquals(0xD8.toByte(), frame[1])
        assertEquals(0xFF.toByte(), frame[frame.size - 2])
        assertEquals(0xD9.toByte(), frame[frame.size - 1])
    }

    @Test
    fun incompleteTrailingFrameIsNotConsumed() {
        // Full frame followed by a second SOI with NO EOI yet (split across reads).
        val complete = jpeg(byteArrayOf(5, 5, 5))
        val partial = soi + byteArrayOf(1, 2) // no EOI
        val buffer = complete + partial

        val result = MjpegFrameParser.extractFrames(buffer)

        assertEquals(1, result.frames.size)
        assertArrayEquals(complete, result.frames[0])
        // consumed should stop at the start of the partial frame so it can be retained.
        assertEquals(complete.size, result.consumed)
    }

    @Test
    fun readStreamReassemblesFramesSplitAcrossReadBuffers() {
        val f1 = jpeg(ByteArray(100) { it.toByte() })
        val f2 = jpeg(ByteArray(50) { (it * 2).toByte() })
        val stream = multipart(f1, f2)
        // Tiny read buffer forces frames to span multiple reads.
        val collected = mutableListOf<ByteArray>()
        MjpegFrameParser.readStream(ByteArrayInputStream(stream), readBufferSize = 7) {
            collected.add(it)
        }

        assertEquals(2, collected.size)
        assertArrayEquals(f1, collected[0])
        assertArrayEquals(f2, collected[1])
    }

    @Test
    fun noFramesInGarbageStream() {
        val garbage = "not a jpeg at all".toByteArray()
        val result = MjpegFrameParser.extractFrames(garbage)
        assertTrue(result.frames.isEmpty())
    }
}
