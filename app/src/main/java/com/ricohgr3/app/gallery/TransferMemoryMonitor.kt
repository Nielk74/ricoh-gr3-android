package com.ricohgr3.app.gallery

import android.app.ActivityManager
import android.content.Context
import kotlin.math.max

/** One start-of-transfer snapshot of the limits that actually constrain Android image work. */
internal data class TransferMemorySnapshot(
    val maxHeapBytes: Long,
    val usedHeapBytes: Long,
    val systemAvailableBytes: Long,
    val systemLowMemoryThresholdBytes: Long,
    val systemLowMemory: Boolean,
) {
    val heapHeadroomBytes: Long
        get() = (maxHeapBytes - usedHeapBytes).coerceAtLeast(0L)
}

/**
 * A two-slot plan is a double buffer: one full camera payload may download while the preceding
 * frame is being saved/developed. One slot preserves the previous strictly sequential behavior.
 */
internal data class TransferPipelinePlan(
    val maxResidentDownloads: Int,
    val heapHeadroomBytes: Long,
) {
    init {
        require(maxResidentDownloads in 1..2)
    }

    val isPipelined: Boolean get() = maxResidentDownloads > 1
}

/** Reads both the process heap limit and Android's current device-wide low-memory signal. */
internal class TransferMemoryMonitor(context: Context) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    fun snapshot(): TransferMemorySnapshot {
        val runtime = Runtime.getRuntime()
        val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return TransferMemorySnapshot(
            maxHeapBytes = runtime.maxMemory(),
            usedHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
            systemAvailableBytes = info.availMem,
            systemLowMemoryThresholdBytes = info.threshold,
            systemLowMemory = info.lowMemory,
        )
    }
}

/**
 * Enable double buffering only when its two worst peaks fit:
 *
 * 1. decode: two compressed full-size payloads plus the bounded ARGB decode;
 * 2. develop: one prefetched payload plus the film pipeline's measured working-set estimate.
 *
 * The reserve protects Compose, codecs, OkHttp, and ordinary VM allocations. Device-wide RAM is
 * checked as a second guard, but cannot replace the per-process heap limit.
 */
internal fun chooseTransferPipeline(
    snapshot: TransferMemorySnapshot,
    preset: TransferPreset,
): TransferPipelinePlan {
    val maxPixels = if (preset.look == null) {
        0
    } else {
        developmentPixelLimit(snapshot.maxHeapBytes, preset.quality)
    }
    val decodedBytes = maxPixels.toLong() * ARGB_BYTES_PER_PIXEL
    val developBytes = maxPixels.toLong() * PEAK_DEVELOP_BYTES_PER_PIXEL
    val processReserve = max(MIN_PROCESS_RESERVE_BYTES, snapshot.maxHeapBytes / 5L)

    val decodePeak = if (preset.look == null) {
        FULL_PAYLOAD_RESERVE_BYTES * 2L
    } else {
        FULL_PAYLOAD_RESERVE_BYTES * 2L + decodedBytes
    }
    val developPeak = if (preset.look == null) {
        decodePeak
    } else {
        FULL_PAYLOAD_RESERVE_BYTES + developBytes
    }
    val requiredHeapHeadroom = processReserve + max(decodePeak, developPeak)
    val systemHeadroom =
        (snapshot.systemAvailableBytes - snapshot.systemLowMemoryThresholdBytes).coerceAtLeast(0L)

    val canDoubleBuffer =
        !snapshot.systemLowMemory &&
            snapshot.heapHeadroomBytes >= requiredHeapHeadroom &&
            systemHeadroom >= FULL_PAYLOAD_RESERVE_BYTES * 2L

    return TransferPipelinePlan(
        maxResidentDownloads = if (canDoubleBuffer) 2 else 1,
        heapHeadroomBytes = snapshot.heapHeadroomBytes,
    )
}

/** Conservative allowance for one full GR JPEG/DNG, including response-buffer variance. */
private const val FULL_PAYLOAD_RESERVE_BYTES = 64L * 1024L * 1024L
private const val MIN_PROCESS_RESERVE_BYTES = 32L * 1024L * 1024L
private const val ARGB_BYTES_PER_PIXEL = 4L
