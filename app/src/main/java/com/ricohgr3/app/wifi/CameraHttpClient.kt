package com.ricohgr3.app.wifi

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Lenient JSON reader for camera responses: tolerate unknown/added firmware fields. */
internal val RicohJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/**
 * OkHttp-based implementation of [CameraWifiController], rooted at the camera AP.
 *
 * The default [baseUrl] is `http://192.168.0.1/v1/` (per the OpenAPI `servers` list). Timeouts
 * are deliberately short because the AP is a single-hop local link — a slow response almost
 * always means the phone left the AP, not real latency. The client is configured with
 * [Proxy.NO_PROXY] so a system HTTP proxy can never divert local-camera traffic.
 *
 * Not thread-confined; OkHttp is safe to share. Reuse a single instance for the app session.
 *
 * @param baseUrl override for tests (e.g. a MockWebServer URL).
 * @param okHttpClient inject a preconfigured client (e.g. one bound to the camera [android.net.Network]).
 */
class CameraHttpClient(
    baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val okHttpClient: OkHttpClient = defaultClient(),
) : CameraWifiController {

    private val base: HttpUrl = baseUrl

    override suspend fun ping(): CameraTime =
        getJson(urlBuilder().addPathSegment("ping").build())

    override suspend fun props(): CameraProps =
        getJson(urlBuilder().addPathSegment("props").build())

    override suspend fun listPhotos(storage: String?, limit: Int?, after: String?): PhotoList {
        val url = urlBuilder()
            .addPathSegment("photos")
            .apply {
                storage?.let { addQueryParameter("storage", it) }
                limit?.let { addQueryParameter("limit", it.toString()) }
                after?.let { addQueryParameter("after", it) }
            }
            .build()
        return getJson(url)
    }

    override suspend fun photoInfo(folder: String, file: String, storage: String?): PhotoInfo {
        val url = urlBuilder()
            .addPathSegment("photos")
            .addPathSegment(folder)
            .addPathSegment(file)
            .addPathSegment("info")
            .apply { storage?.let { addQueryParameter("storage", it) } }
            .build()
        return getJson(url)
    }

    override suspend fun downloadPhoto(
        folder: String,
        file: String,
        size: ImageSize,
        storage: String?,
    ): ByteArray = withContext(Dispatchers.IO) {
        val url = urlBuilder()
            .addPathSegment("photos")
            .addPathSegment(folder)
            .addPathSegment(file)
            .apply {
                size.queryValue?.let { addQueryParameter("size", it) }
                storage?.let { addQueryParameter("storage", it) }
            }
            .build()
        okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("downloadPhoto ${resp.code} for $folder/$file")
            // TODO(scaffold): buffers the whole file in memory. For real downloads, stream
            // resp.body!!.source() to a File/OutputStream instead.
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    override suspend fun setCameraParams(params: CaptureParams): CameraProps {
        val fields = params.toFormFields()
        require(fields.isNotEmpty()) { "setCameraParams requires at least one non-null parameter" }
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val url = urlBuilder().addPathSegment("params").addPathSegment("camera").build()
        val request = Request.Builder().url(url).put(body).build()
        return executeJson(request)
    }

    override suspend fun shoot(): ShootResult {
        val url = urlBuilder().addPathSegment("camera").addPathSegment("shoot").build()
        // Empty body POST (GRsync posts b"{}" to /device/finish; shoot takes no params).
        val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()
        return executeJson(request)
    }

    override fun liveview(): Flow<ByteArray> = callbackFlow {
        val url = urlBuilder().addPathSegment("liveview").build()
        val request = Request.Builder().url(url).get().build()
        val call = okHttpClient.newCall(request)
        val response = call.execute()
        if (!response.isSuccessful) {
            response.close()
            close(IOException("liveview HTTP ${response.code}"))
            return@callbackFlow
        }
        val source = response.body?.byteStream()
        if (source == null) {
            response.close()
            close(IOException("liveview empty body"))
            return@callbackFlow
        }
        try {
            MjpegFrameParser.readStream(source) { frame -> trySend(frame) }
        } catch (e: IOException) {
            // Stream closed / cancelled — normal on teardown.
        } finally {
            response.close()
        }
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    // --- internals -------------------------------------------------------------------------

    private fun urlBuilder(): HttpUrl.Builder = base.newBuilder()

    private suspend inline fun <reified T> getJson(url: HttpUrl): T =
        executeJson(Request.Builder().url(url).get().build())

    private suspend inline fun <reified T> executeJson(request: Request): T =
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} for ${request.url}: $text")
                }
                RicohJson.decodeFromString<T>(text)
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "http://192.168.0.1/v1/"

        fun defaultClient(): OkHttpClient = defaultClientBuilder().build()

        private fun defaultClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // liveview is an infinite stream: no overall call timeout, rely on read timeout +
            // flow cancellation for teardown.
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        /**
         * Build a client whose sockets are opened on [network] (the camera AP), via
         * [Network.getSocketFactory]. Only camera requests use this socket factory; the rest of the
         * app stays on Android's normal internet route. DNS is left at OkHttp's default — irrelevant
         * here since the camera is reached by its literal IP (`192.168.0.1`), not a hostname.
         */
        fun clientForNetwork(network: Network): OkHttpClient = defaultClientBuilder()
            .socketFactory(network.socketFactory)
            .build()

        /**
         * Create a [CameraHttpClient] whose OkHttp sockets are pinned to [network]. Use this when
         * you have the camera's joined [Network] from [WifiApConnector.Listener.onAvailable] and
         * want camera-only routing without changing the process's default network.
         *
         * @param baseUrl override for tests; defaults to `http://192.168.0.1/v1/`.
         */
        fun forNetwork(
            network: Network,
            baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
        ): CameraHttpClient = CameraHttpClient(baseUrl = baseUrl, okHttpClient = clientForNetwork(network))
    }
}
