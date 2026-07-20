package com.ricohgr3.app.update

import com.ricohgr3.app.update.AppUpdateChecker.Companion.isNewer
import com.ricohgr3.app.update.AppUpdateChecker.Companion.tagToVersion
import com.ricohgr3.app.update.AppUpdateChecker.Companion.toAppRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun `release tags are normalized to bare versions`() {
        assertEquals("0.7.0", tagToVersion("v0.7.0"))
        assertEquals("0.7.0", tagToVersion("0.7.0"))
        assertEquals("0.7.0", tagToVersion("android-v0.7.0"))
    }

    @Test
    fun `semantic versions compare numeric components and prereleases`() {
        assertTrue(isNewer(candidate = "0.7.0", current = "0.6.0"))
        assertTrue(isNewer(candidate = "0.6.1", current = "0.6.0"))
        assertTrue(isNewer(candidate = "1.0.0", current = "0.99.99"))
        assertFalse(isNewer(candidate = "0.6", current = "0.6.0"))
        assertFalse(isNewer(candidate = "0.7.0-rc1", current = "0.7.0"))
        assertTrue(isNewer(candidate = "0.7.0", current = "0.7.0-rc1"))
    }

    @Test
    fun `release resolves apk and matching checksum assets`() {
        val dto = GithubReleaseDto(
            tagName = "v0.7.0",
            body = "Release notes",
            htmlUrl = "https://github.com/Nielk74/ricoh-gr3-android/releases/tag/v0.7.0",
            assets = listOf(
                GithubAssetDto(
                    "app-release.apk.sha256",
                    82,
                    "https://example.com/app-release.apk.sha256",
                ),
                GithubAssetDto(
                    "app-release.apk",
                    12_345,
                    "https://example.com/app-release.apk",
                ),
            ),
        )

        val release = dto.toAppRelease()

        checkNotNull(release)
        assertEquals("0.7.0", release.versionName)
        assertEquals("https://example.com/app-release.apk", release.apkUrl)
        assertEquals(12_345, release.apkSizeBytes)
        assertEquals("https://example.com/app-release.apk.sha256", release.sha256Url)
        assertEquals("Release notes", release.notes)
    }

    @Test
    fun `draft prerelease and apk-less releases are ignored`() {
        val release = GithubReleaseDto(
            tagName = "v0.7.0",
            assets = listOf(
                GithubAssetDto("app-release.apk", 1, "https://example.com/app-release.apk"),
            ),
        )

        assertNull(release.copy(draft = true).toAppRelease())
        assertNull(release.copy(prerelease = true).toAppRelease())
        assertNull(release.copy(assets = emptyList()).toAppRelease())
    }

    @Test
    fun `checker targets public repository and skips incomplete newest release`() = runBlocking {
        val client = githubClient(
            """
            [
              {
                "tag_name": "v0.8.0",
                "draft": false,
                "prerelease": false,
                "assets": []
              },
              {
                "tag_name": "v0.7.0",
                "draft": false,
                "prerelease": false,
                "assets": [
                  {
                    "name": "app-release.apk",
                    "size": 12345,
                    "browser_download_url": "https://example.com/app-release.apk"
                  }
                ]
              }
            ]
            """.trimIndent(),
        )
        val checker = AppUpdateChecker(
            client = client,
            json = Json { ignoreUnknownKeys = true },
            repo = "Nielk74/ricoh-gr3-android",
            currentVersionName = "0.6.0",
            ioDispatcher = Dispatchers.Unconfined,
        )

        val status = checker.check()

        assertTrue(status is UpdateStatus.Available)
        assertEquals("0.7.0", (status as UpdateStatus.Available).release.versionName)
    }

    private fun githubClient(responseJson: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                assertEquals(
                    "/repos/Nielk74/ricoh-gr3-android/releases",
                    request.url.encodedPath,
                )
                assertEquals("10", request.url.queryParameter("per_page"))
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
}
