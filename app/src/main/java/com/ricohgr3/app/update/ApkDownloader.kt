package com.ricohgr3.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.ricohgr3.app.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads a release APK, verifies its published SHA-256 when present, and
 * opens Android's package installer. Android still requires user confirmation.
 */
class ApkDownloader(
    private val context: Context,
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val authority = "${BuildConfig.APPLICATION_ID}.updates"

    fun downloadAndInstall(release: AppRelease): Flow<DownloadState> = flow {
        emit(DownloadState.Downloading(0f))
        val target = apkFile(release.versionName)
        try {
            download(release, target) { fraction ->
                emit(DownloadState.Downloading(fraction))
            }

            emit(DownloadState.Verifying)
            val expected = release.sha256Url?.let { fetchExpectedSha256(it) }
            if (expected != null) {
                val actual = sha256Of(target)
                if (!actual.equals(expected, ignoreCase = true)) {
                    target.delete()
                    emit(DownloadState.Failed("Checksum mismatch — download rejected"))
                    return@flow
                }
            }

            installApk(target)
            emit(DownloadState.ReadyToInstall)
        } catch (error: IOException) {
            target.delete()
            emit(DownloadState.Failed(error.message ?: "Download failed"))
        } catch (error: ActivityNotFoundException) {
            target.delete()
            emit(DownloadState.Failed("Android's package installer is unavailable"))
        } catch (error: SecurityException) {
            target.delete()
            emit(DownloadState.Failed(error.message ?: "Installation permission was denied"))
        }
    }.flowOn(ioDispatcher)

    private suspend fun download(
        release: AppRelease,
        target: File,
        onProgress: suspend (Float) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(release.apkUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Ricoh-GR3-Android/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
            val body = response.body ?: throw IOException("Download returned an empty response")
            val totalBytes =
                if (release.apkSizeBytes > 0) release.apkSizeBytes else body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BufferSizeBytes)
                    var downloadedBytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        if (totalBytes > 0) {
                            onProgress(
                                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun fetchExpectedSha256(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Ricoh-GR3-Android/${BuildConfig.VERSION_NAME}")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Checksum download failed (${response.code})")
            }
            val body = response.body ?: throw IOException("Checksum response was empty")
            body.string()
                .trim()
                .substringBefore(' ')
                .takeIf { it.matches(Sha256Regex) }
                ?: throw IOException("Published checksum is invalid")
        }
    }

    private fun installApk(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ApkMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun apkFile(versionName: String): File {
        val directory = File(context.getExternalFilesDir(null), "updates")
        val safeVersion = versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(directory, "ricoh-gr3-$safeVersion.apk")
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BufferSizeBytes)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ApkMimeType = "application/vnd.android.package-archive"
        const val BufferSizeBytes = 64 * 1024
        val Sha256Regex = Regex("[A-Fa-f0-9]{64}")
    }
}
