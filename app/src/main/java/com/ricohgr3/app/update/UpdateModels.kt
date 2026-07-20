package com.ricohgr3.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Result of checking the app's public GitHub repository for a newer release. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val release: AppRelease) : UpdateStatus
    data class Failed(val reason: String) : UpdateStatus
}

/** Progress from downloading an APK through handing it to Android's installer. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val fraction: Float) : DownloadState
    data object Verifying : DownloadState
    data object ReadyToInstall : DownloadState
    data class Failed(val reason: String) : DownloadState
}

internal val DownloadState.canStartDownload: Boolean
    get() = this is DownloadState.Idle || this is DownloadState.Failed

data class AppRelease(
    val versionName: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val sha256Url: String?,
    val htmlUrl: String,
)

@Serializable
internal data class GithubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GithubAssetDto> = emptyList(),
)

@Serializable
internal data class GithubAssetDto(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)
