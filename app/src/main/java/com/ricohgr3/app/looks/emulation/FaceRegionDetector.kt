package com.ricohgr3.app.looks.emulation

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.WeakHashMap

/**
 * Android semantic gate for selective complexion rendering.
 *
 * ML Kit runs entirely on-device with the model bundled in the APK. Bounds are cached by source
 * bitmap, which matters when the editor's effect slider re-renders the same preview repeatedly.
 * Detection failure is safe: [SkinTone] receives no regions and leaves the stock colour intact.
 */
internal object FaceRegionDetector {
    private const val MAX_ANALYSIS_EDGE = 1_024
    private const val TAG = "FaceRegionDetector"

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.045f)
            .build(),
    )
    private val cache = WeakHashMap<Bitmap, List<FaceRegion>>()
    private val detectorLock = Any()

    fun detect(source: Bitmap): List<FaceRegion> {
        synchronized(cache) {
            cache[source]?.let { return it }
        }

        val regions = runCatching {
            val analysis = scaledForDetection(source)
            try {
                val faces = synchronized(detectorLock) {
                    Tasks.await(detector.process(InputImage.fromBitmap(analysis, 0)))
                }
                faces.mapNotNull { face ->
                    val bounds = face.boundingBox
                    val left = bounds.left.toFloat() / analysis.width
                    val top = bounds.top.toFloat() / analysis.height
                    val right = bounds.right.toFloat() / analysis.width
                    val bottom = bounds.bottom.toFloat() / analysis.height
                    if (right <= left || bottom <= top) {
                        null
                    } else {
                        FaceRegion(
                            left = left.coerceIn(0f, 1f),
                            top = top.coerceIn(0f, 1f),
                            right = right.coerceIn(0f, 1f),
                            bottom = bottom.coerceIn(0f, 1f),
                        )
                    }
                }
            } finally {
                if (analysis !== source) analysis.recycle()
            }
        }.onFailure { error ->
            Log.w(TAG, "Face isolation unavailable; rendering stock colour unchanged", error)
        }.getOrDefault(emptyList())

        synchronized(cache) {
            cache[source] = regions
        }
        return regions
    }

    private fun scaledForDetection(source: Bitmap): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= MAX_ANALYSIS_EDGE) return source
        val scale = MAX_ANALYSIS_EDGE.toFloat() / longEdge
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
