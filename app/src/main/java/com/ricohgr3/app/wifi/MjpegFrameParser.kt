package com.ricohgr3.app.wifi

import java.io.InputStream

/**
 * Splits a Ricoh `/v1/liveview` `multipart/x-mixed-replace` MJPEG byte stream into individual
 * JPEG frames.
 *
 * ## Why marker-scanning instead of boundary-parsing
 * The stream is nominally multipart with a `boundary=...` token in the `Content-Type`, each
 * part carrying `Content-Type: image/jpeg` and (sometimes) a `Content-Length`. However, the
 * exact boundary token the GR III firmware emits is not authoritatively documented, and it
 * varies across THETA/GR firmwares. Rather than depend on a guessed boundary string, we scan
 * for the JPEG frame markers themselves, which are invariant:
 *   - SOI (Start Of Image): `0xFF 0xD8`
 *   - EOI (End Of Image):   `0xFF 0xD9`
 * Everything between an SOI and its matching EOI (inclusive) is one decodable JPEG frame; the
 * multipart headers/boundaries in between are discarded. This is the same robust approach used
 * by community MJPEG decoders. See:
 *   - OpenAPI: `research/references/ricoh-wireless-protocol/openapi.yaml` (`/liveview`)
 *   - https://gist.github.com/TimSC/8324156 (canonical 0xFFD8/0xFFD9 frame splitting)
 *
 * The core [extractFrames] logic is a pure function over a byte buffer so it can be unit-tested
 * with a synthetic multipart stream, with no network involved.
 */
object MjpegFrameParser {

    private const val MARKER: Byte = 0xFF.toByte()
    private const val SOI: Byte = 0xD8.toByte() // Start Of Image
    private const val EOI: Byte = 0xD9.toByte() // End Of Image

    /**
     * Extract every complete JPEG frame present in [buffer] over [0, length).
     *
     * @return [ParseResult] with the list of complete frames (each a fresh `ByteArray` copy
     *   starting at `0xFFD8` and ending at `0xFFD9`) and [ParseResult.consumed] — the number
     *   of leading bytes fully processed. Callers doing incremental parsing should retain
     *   `buffer[consumed until length]` and prepend it to the next chunk, so a frame split
     *   across reads is not lost.
     */
    fun extractFrames(buffer: ByteArray, length: Int = buffer.size): ParseResult {
        val frames = ArrayList<ByteArray>()
        var searchFrom = 0
        var consumed = 0
        while (true) {
            val start = indexOfMarker(buffer, searchFrom, length, SOI)
            if (start < 0) {
                // No further SOI; everything up to a possible trailing lone 0xFF is consumed.
                // Keep a trailing 0xFF (could be the first half of the next SOI marker).
                consumed = if (length > 0 && buffer[length - 1] == MARKER) length - 1 else length
                break
            }
            val end = indexOfMarker(buffer, start + 2, length, EOI)
            if (end < 0) {
                // Incomplete frame: SOI seen but no EOI yet. Consume up to the SOI so the
                // partial frame is retained for the next chunk.
                consumed = start
                break
            }
            val frameEnd = end + 2 // include the EOI marker bytes
            frames.add(buffer.copyOfRange(start, frameEnd))
            searchFrom = frameEnd
            consumed = frameEnd
        }
        return ParseResult(frames, consumed)
    }

    /** Find the next `0xFF [marker]` pair in [from, until), or -1. */
    private fun indexOfMarker(buffer: ByteArray, from: Int, until: Int, marker: Byte): Int {
        var i = from
        while (i < until - 1) {
            if (buffer[i] == MARKER && buffer[i + 1] == marker) return i
            i++
        }
        return -1
    }

    /**
     * Read [input] to end, invoking [onFrame] for each complete JPEG frame. Blocking; run on a
     * background thread. Stops when the stream ends or [input] is closed by the caller.
     */
    fun readStream(input: InputStream, readBufferSize: Int = 16 * 1024, onFrame: (ByteArray) -> Unit) {
        val readBuf = ByteArray(readBufferSize)
        var pending = ByteArray(0)
        while (true) {
            val n = input.read(readBuf)
            if (n < 0) break
            // Append newly read bytes to whatever partial frame we carried over.
            val combined = ByteArray(pending.size + n)
            System.arraycopy(pending, 0, combined, 0, pending.size)
            System.arraycopy(readBuf, 0, combined, pending.size, n)

            val result = extractFrames(combined, combined.size)
            result.frames.forEach(onFrame)
            pending = combined.copyOfRange(result.consumed, combined.size)

            // Guard against unbounded growth if the stream is garbage (no EOI ever).
            if (pending.size > MAX_PENDING_BYTES) pending = ByteArray(0)
        }
    }

    /** ~8 MiB safety cap on a single buffered frame before we give up and resync. */
    private const val MAX_PENDING_BYTES = 8 * 1024 * 1024

    data class ParseResult(val frames: List<ByteArray>, val consumed: Int)
}
