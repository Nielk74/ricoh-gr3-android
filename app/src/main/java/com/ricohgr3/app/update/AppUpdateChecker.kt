package com.ricohgr3.app.update

import com.ricohgr3.app.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Resolves the newest installable APK from this app's public GitHub releases.
 *
 * Recent releases are inspected instead of relying on `/releases/latest`: an
 * incomplete release without an APK must not hide the last usable version.
 */
class AppUpdateChecker(
    private val client: OkHttpClient,
    private val json: Json,
    private val repo: String = BuildConfig.GITHUB_REPO,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun check(): UpdateStatus = withContext(ioDispatcher) {
        val release = try {
            fetchLatestInstallableRelease()
        } catch (error: IOException) {
            return@withContext UpdateStatus.Failed(error.message ?: "Update check failed")
        } catch (error: SerializationException) {
            return@withContext UpdateStatus.Failed(error.message ?: "Invalid update response")
        } ?: return@withContext UpdateStatus.Failed("No installable release found")

        if (isNewer(release.versionName, currentVersionName)) {
            UpdateStatus.Available(release)
        } else {
            UpdateStatus.UpToDate
        }
    }

    private fun fetchLatestInstallableRelease(): AppRelease? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases?per_page=$ReleasePageSize")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Ricoh-GR3-Android/${BuildConfig.VERSION_NAME}")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub returned ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("Empty response from GitHub")
            if (body.isBlank()) throw IOException("Empty response from GitHub")
            val releases = json.decodeFromString(
                ListSerializer(GithubReleaseDto.serializer()),
                body,
            )
            return releases
                .mapNotNull { it.toAppRelease() }
                .maxWithOrNull { left, right ->
                    compareSemVer(left.versionName, right.versionName)
                }
        }
    }

    companion object {
        private const val ReleasePageSize = 10

        /** Maps `v0.7.0` (and the older `android-v0.7.0` form) to `0.7.0`. */
        internal fun tagToVersion(tag: String): String =
            tag.removePrefix("android-").removePrefix("v").trim()

        internal fun GithubReleaseDto.toAppRelease(): AppRelease? {
            if (draft || prerelease) return null
            val version = tagToVersion(tagName)
            if (version.isBlank()) return null
            val apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return null
            if (apk.browserDownloadUrl.isBlank()) return null
            val sha256Url = assets
                .firstOrNull { it.name.equals("${apk.name}.sha256", ignoreCase = true) }
                ?.browserDownloadUrl
            return AppRelease(
                versionName = version,
                tag = tagName,
                notes = (body ?: name.orEmpty()).trim(),
                apkUrl = apk.browserDownloadUrl,
                apkSizeBytes = apk.size,
                sha256Url = sha256Url,
                htmlUrl = htmlUrl,
            )
        }

        internal fun isNewer(candidate: String, current: String): Boolean =
            compareSemVer(candidate, current) > 0

        internal fun compareSemVer(left: String, right: String): Int {
            val (leftCore, leftPre) = splitPreRelease(left)
            val (rightCore, rightPre) = splitPreRelease(right)
            val maxLength = maxOf(leftCore.size, rightCore.size)

            for (index in 0 until maxLength) {
                val leftPart = leftCore.getOrElse(index) { 0 }
                val rightPart = rightCore.getOrElse(index) { 0 }
                if (leftPart != rightPart) return leftPart.compareTo(rightPart)
            }

            return when {
                leftPre == null && rightPre == null -> 0
                leftPre == null -> 1
                rightPre == null -> -1
                else -> leftPre.compareTo(rightPre)
            }
        }

        private fun splitPreRelease(version: String): Pair<List<Int>, String?> {
            val trimmed = version.trim()
            val dashIndex = trimmed.indexOf('-')
            val core = if (dashIndex >= 0) trimmed.substring(0, dashIndex) else trimmed
            val prerelease = if (dashIndex >= 0) trimmed.substring(dashIndex + 1) else null
            return core.split('.').map { it.toIntOrNull() ?: 0 } to prerelease
        }
    }
}
