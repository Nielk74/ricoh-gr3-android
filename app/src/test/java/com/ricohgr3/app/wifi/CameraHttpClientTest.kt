package com.ricohgr3.app.wifi

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraHttpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CameraHttpClient

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name"))
            .bufferedReader().use { it.readText() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Root the client at the mock server's /v1/ base.
        client = CameraHttpClient(baseUrl = server.url("/v1/").toString().toHttpUrl())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun pingParsesDatetime() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("ping.json")))

        val result = client.ping()

        assertEquals("2026-07-11T10:30:00+09:00", result.datetime)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/ping", request.path)
    }

    @Test
    fun propsParsesCoreFieldsAndLists() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("props.json")))

        val props = client.props()

        assertEquals(200, props.errCode)
        assertEquals("RICOH GR IIIx", props.model)
        assertEquals("1.50", props.firmwareVersion)
        assertEquals(87, props.battery)
        assertEquals("2.8", props.av)
        assertEquals("1600", props.sv)
        assertEquals("efc_posiFilm", props.effect)
        assertEquals("auto", props.wbMode) // @SerialName WBMode
        assertEquals("rawdng", props.stillFormat)
        assertEquals("GR_A1B2C3", props.ssid)
        assertEquals(listOf("auto", "100", "800", "1600", "3200"), props.svList)
        assertEquals(1, props.storages?.size)
        assertEquals("sd1", props.storages?.first()?.name)
        assertEquals("/v1/props", server.takeRequest().path)
    }

    @Test
    fun listPhotosParsesDirsAndFiles() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("photos.json")))

        val list = client.listPhotos()

        assertEquals(2, list.dirs.size)
        assertEquals("100RICOH", list.dirs[0].name)
        assertEquals(listOf("R0000001.JPG", "R0000002.DNG", "R0000003.JPG"), list.dirs[0].files)
        assertEquals("/v1/photos", server.takeRequest().path)
    }

    @Test
    fun listPhotosAppendsQueryParams() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("photos.json")))

        client.listPhotos(storage = "sd1", limit = 10, after = "2026-01-01T00:00:00Z")

        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/v1/photos?"))
        assertTrue(path.contains("storage=sd1"))
        assertTrue(path.contains("limit=10"))
    }

    @Test
    fun setCameraParamsSendsPutFormBodyToCorrectPath() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("props.json")))

        client.setCameraParams(CaptureParams(iso = "1600", aperture = "2.8", effect = "efc_posiFilm"))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/params/camera", request.path)
        assertTrue(
            request.getHeader("Content-Type")?.startsWith("application/x-www-form-urlencoded") == true
        )
        val body = request.body.readUtf8()
        // FormBody url-encodes the rest_key=value pairs.
        assertTrue(body.contains("sv=1600"))
        assertTrue(body.contains("av=2.8"))
        assertTrue(body.contains("effect=efc_posiFilm"))
    }

    @Test
    fun shootPostsToShootEndpoint() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"errCode":200,"errMsg":"OK","captureId":42,"captured":true}"""))

        val result = client.shoot()

        assertEquals(42, result.captureId)
        assertEquals(true, result.captured)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/camera/shoot", request.path)
    }

    @Test
    fun photoInfoParsesMetadata() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"cameraModel":"RICOH GR IIIx","dir":"100RICOH","file":"R0000001.JPG","av":"2.8","tv":"1.500","sv":"1600","size":12345678}"""
            )
        )

        val info = client.photoInfo("100RICOH", "R0000001.JPG")

        assertEquals("RICOH GR IIIx", info.cameraModel)
        assertEquals(12345678L, info.size)
        assertEquals("/v1/photos/100RICOH/R0000001.JPG/info", server.takeRequest().path)
    }

    @Test
    fun downloadPhotoReturnsBodyBytesAndOmitsSizeForFull() = runBlocking {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

        val result = client.downloadPhoto("100RICOH", "R0000001.JPG", size = ImageSize.FULL)

        assertTrue(bytes.contentEquals(result))
        // FULL must not append a ?size= param.
        assertEquals("/v1/photos/100RICOH/R0000001.JPG", server.takeRequest().path)
    }

    @Test
    fun downloadPhotoProgressStreamsKnownLengthAndKeepsFullResolutionUrl() = runBlocking {
        val bytes = ByteArray(1_200_000) { (it % 251).toByte() }
        val updates = mutableListOf<Pair<Long, Long?>>()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

        val result = client.downloadPhotoWithProgress(
            folder = "100RICOH",
            file = "R0000001.JPG",
            size = ImageSize.FULL,
            onProgress = { read, total -> updates += read to total },
        )

        assertTrue(bytes.contentEquals(result))
        assertTrue(updates.size >= 3)
        assertEquals(0L to bytes.size.toLong(), updates.first())
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), updates.last())
        assertTrue(updates.zipWithNext().all { (left, right) -> right.first >= left.first })
        assertEquals("/v1/photos/100RICOH/R0000001.JPG", server.takeRequest().path)
    }

    @Test
    fun downloadPhotoAppendsSizeParamForThumb() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))

        client.downloadPhoto("100RICOH", "R0000001.JPG", size = ImageSize.THUMB)

        assertEquals("/v1/photos/100RICOH/R0000001.JPG?size=thumb", server.takeRequest().path)
    }

    @Test
    fun setCameraParamsWithNoFieldsThrows() {
        var threw = false
        try {
            runBlocking { client.setCameraParams(CaptureParams()) }
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        // Nothing should have been sent.
        assertEquals(0, server.requestCount)
        assertNull(server.takeRequest(0, java.util.concurrent.TimeUnit.MILLISECONDS))
    }
}
