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
        val requestedPages = mutableListOf<Int>()
        val client = githubClient(
            pages = mapOf(
                1 to
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
            ),
            requestedPages = requestedPages,
        )
        val checker = checker(client)

        val status = checker.check()

        assertTrue(status is UpdateStatus.Available)
        assertEquals("0.7.0", (status as UpdateStatus.Available).release.versionName)
        assertEquals(listOf(1), requestedPages)
    }

    @Test
    fun `checker follows GitHub pagination to an installable release on a later page`() =
        runBlocking {
            val requestedPages = mutableListOf<Int>()
            val client = githubClient(
                pages = mapOf(
                    1 to
                        """
                        [
                          {
                            "tag_name": "v0.9.0",
                            "draft": false,
                            "prerelease": false,
                            "assets": []
                          }
                        ]
                        """.trimIndent(),
                    2 to
                        """
                        [
                          {
                            "tag_name": "v0.8.0",
                            "draft": false,
                            "prerelease": false,
                            "assets": [
                              {
                                "name": "app-release.apk",
                                "size": 54321,
                                "browser_download_url": "https://example.com/v0.8.0.apk"
                              }
                            ]
                          }
                        ]
                        """.trimIndent(),
                ),
                requestedPages = requestedPages,
            )
            val checker = checker(client)

            val status = checker.check()

            assertTrue(status is UpdateStatus.Available)
            assertEquals("0.8.0", (status as UpdateStatus.Available).release.versionName)
            assertEquals(listOf(1, 2), requestedPages)
        }

    private fun checker(client: OkHttpClient): AppUpdateChecker =
        AppUpdateChecker(
            client = client,
            json = Json { ignoreUnknownKeys = true },
            repo = "Nielk74/ricoh-gr3-android",
            currentVersionName = "0.6.0",
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun githubClient(
        pages: Map<Int, String>,
        requestedPages: MutableList<Int>,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                assertEquals(
                    "/repos/Nielk74/ricoh-gr3-android/releases",
                    request.url.encodedPath,
                )
                assertEquals("100", request.url.queryParameter("per_page"))
                val pageNumber = request.url.queryParameter("page")?.toInt()
                checkNotNull(pageNumber)
                requestedPages += pageNumber
                val responseJson = checkNotNull(pages[pageNumber]) {
                    "Unexpected GitHub release page $pageNumber"
                }
                val response = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody("application/json".toMediaType()))
                if (pages.containsKey(pageNumber + 1)) {
                    response.header(
                        "Link",
                        "<https://api.github.com/repos/Nielk74/ricoh-gr3-android/releases" +
                            "?per_page=100&page=${pageNumber + 1}>; rel=\"next\", " +
                            "<https://api.github.com/repos/Nielk74/ricoh-gr3-android/releases" +
                            "?per_page=100&page=${pages.keys.max()}>; rel=\"last\"",
                    )
                }
                response.build()
            }
            .build()
}
