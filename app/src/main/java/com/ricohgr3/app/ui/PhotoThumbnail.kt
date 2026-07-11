package com.ricohgr3.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.data.PhotoResult

/**
 * Loads and decodes a photo thumbnail from the camera on first composition, keyed by [id].
 *
 * The camera has no internet and images are large, so we pull only the ~30 KiB
 * [com.ricohgr3.app.wifi.ImageSize.THUMB] rendition and decode it off the main thread inside a
 * [LaunchedEffect]. While loading (or when the camera is unreachable) a neutral paper-edge
 * placeholder shows — the grid layout never depends on the bytes arriving.
 */
@Composable
fun PhotoThumbnail(
    id: PhotoId,
    repository: PhotoRepository,
    modifier: Modifier = Modifier,
    placeholderColor: androidx.compose.ui.graphics.Color,
) {
    var bitmap by remember(id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(id) {
        when (val result = repository.downloadThumbnail(id)) {
            is PhotoResult.Success -> {
                val bytes = result.value
                bitmap = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            is PhotoResult.Error -> bitmap = null // keep placeholder
        }
    }

    Box(modifier = modifier.background(placeholderColor)) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = id.toString(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
