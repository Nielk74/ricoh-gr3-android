package com.ricohgr3.app.wifi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response / request models for the Ricoh GR III / GR IIIx Wi-Fi HTTP `/v1/` API.
 *
 * These are derived directly from the CursedHardware OpenAPI 3.0.3 spec at
 * `research/references/ricoh-wireless-protocol/openapi.yaml` (the primary source of truth)
 * and the GR III enum definitions in `definitions/capture_ricoh_gr_iii.yaml`.
 *
 * The camera returns JSON with `additionalProperties: false` in the spec, but firmware
 * revisions add/remove fields freely, so every field here is nullable and the parser is
 * configured with `ignoreUnknownKeys = true` (see [RicohJson]). Only model the subset the
 * app actually needs; the raw JSON is always available for debugging.
 */

/** `/v1/ping` response and the `datetime` fragment reused across several responses. */
@Serializable
data class CameraTime(
    val datetime: String? = null,
)

/**
 * Standard error envelope. Note: the camera returns HTTP 200 with `errCode: 200` on
 * success for JSON endpoints too — `/v1/props` embeds these fields (see GRsync, which
 * checks `props['errCode'] != 200`). So a non-200 [errCode] is the real success signal,
 * not the HTTP status.
 */
@Serializable
data class ApiError(
    val errCode: Int? = null,
    val errMsg: String? = null,
)

/**
 * `/v1/props` response — the full property blob. The OpenAPI `Properties` schema is an
 * `allOf` merge of CameraInfo + CameraParams + Battery + Wifi/Bluetooth/GPS/Focus settings
 * + operation mode + storages, all at the top level of one flat JSON object. We flatten the
 * fields the app cares about here; unmodelled fields are ignored.
 */
@Serializable
data class CameraProps(
    val errCode: Int? = null,
    val errMsg: String? = null,

    // CameraInfo
    val manufacturer: String? = null,
    val model: String? = null,
    val serialNo: String? = null,
    val firmwareVersion: String? = null,
    val macAddress: String? = null,

    // Battery
    val battery: Int? = null,
    val batteryUsed: String? = null,

    // CameraParams — current values
    val av: String? = null,
    val tv: String? = null,
    val sv: String? = null,
    val xv: String? = null,
    val exposureMode: String? = null,
    val meteringMode: String? = null,
    @SerialName("WBMode") val wbMode: String? = null,
    val effect: String? = null,
    val stillFormat: String? = null,
    val stillSize: String? = null,
    val shootMode: String? = null,
    val captureMode: String? = null,
    val capturing: Boolean? = null,

    // CameraParams — available-value lists
    val avList: List<String>? = null,
    val tvList: List<String>? = null,
    val svList: List<String>? = null,
    val xvList: List<String>? = null,
    val exposureModeList: List<String>? = null,
    val meteringModeList: List<String>? = null,
    @SerialName("WBModeList") val wbModeList: List<String>? = null,
    val effectList: List<String>? = null,
    val stillFormatList: List<String>? = null,
    val stillSizeList: List<String>? = null,
    val shootModeList: List<String>? = null,

    // FocusSettings
    val focused: Boolean? = null,
    val focusMode: String? = null,
    val focusSetting: String? = null,

    // WifiSettings
    val ssid: String? = null,
    val key: String? = null,

    // OperationMode
    val operationMode: String? = null,

    // Misc
    val datetime: String? = null,
    val storages: List<Storage>? = null,
)

/** A single storage slot from the `storages` array in `/v1/props`. */
@Serializable
data class Storage(
    val name: String? = null,
    val format: String? = null,
    val remain: Int? = null,
    val recordableTime: Int? = null,
    val numOfPhotos: Int? = null,
    val numOfMovies: Int? = null,
    val equipped: Boolean? = null,
    val available: Boolean? = null,
    val writable: Boolean? = null,
)

/**
 * `/v1/photos` response: a list of folders, each with a list of file names.
 * Example (from the OpenAPI spec): `{ "dirs": [ { "name": "100RICOH", "files": ["R0000001.JPG", ...] } ] }`.
 */
@Serializable
data class PhotoList(
    val errCode: Int? = null,
    val errMsg: String? = null,
    val dirs: List<PhotoDir> = emptyList(),
)

@Serializable
data class PhotoDir(
    val name: String? = null,
    val files: List<String> = emptyList(),
)

/**
 * `/v1/photos/{folder}/{file}/info` response — EXIF-ish metadata for one photo.
 */
@Serializable
data class PhotoInfo(
    val errCode: Int? = null,
    val errMsg: String? = null,
    val cameraModel: String? = null,
    val dir: String? = null,
    val file: String? = null,
    val orientation: Int? = null,
    val aspectRatio: String? = null,
    val av: String? = null,
    val tv: String? = null,
    val sv: String? = null,
    val xv: String? = null,
    val size: Long? = null,
    val gpsInfo: String? = null,
    val datetime: String? = null,
)

/**
 * `/v1/camera/shoot` (and start/compose/finish/cancel) response.
 * `captureId` is always present per the spec's `required` list.
 */
@Serializable
data class ShootResult(
    val errCode: Int? = null,
    val errMsg: String? = null,
    val captureId: Int? = null,
    val captured: Boolean? = null,
    val focused: Boolean? = null,
)

/**
 * Requested image dimension for `GET /v1/photos/{folder}/{file}?size=...`.
 * `FULL` omits the `size` query param entirely (native 6000x4000 for the GR III).
 *
 * Sizes from the OpenAPI `ImageDimension` parameter:
 *  - FULL  -> 6000x4000 (~36 MiB)
 *  - XS    -> 1920x1280 (~4 MiB)
 *  - VIEW  -> 720x480   (~500 KiB)
 *  - THUMB -> 160x120   (~30 KiB)
 */
enum class ImageSize(val queryValue: String?) {
    FULL(null),
    XS("xs"),
    VIEW("view"),
    THUMB("thumb"),
}

/**
 * `PUT /v1/params/camera` capture-parameter form fields. Every field is optional; only
 * non-null fields are sent (the spec requires `minProperties: 1`). Field names here are the
 * exact `rest_key`s the camera expects (see `capture_ricoh_gr_iii.yaml`); values are the
 * `val` strings from that file (e.g. ISO 1600 -> sv="1600", f/2.8 -> av="2.8",
 * effect Positive Film -> effect="efc_posiFilm").
 *
 * Use [CaptureParams.toFormFields] to serialize to the `application/x-www-form-urlencoded`
 * body the camera expects.
 */
data class CaptureParams(
    /** ISO, e.g. "auto", "100", "1600". `sv` in the API. */
    val iso: String? = null,
    /** Shutter speed as the API `tv` string, e.g. "1.500" for 1/500s, "2.1" for 2s. */
    val shutterSpeed: String? = null,
    /** Aperture as the API `av` string, e.g. "2.8", "11". */
    val aperture: String? = null,
    /** Exposure compensation as the API `xv` string, e.g. "0.0", "+1.0", "-0.7". */
    val exposureComp: String? = null,
    /** Exposure mode, e.g. "P", "AV", "TV", "M". */
    val exposureMode: String? = null,
    /** Metering mode, e.g. "multi", "center", "spot", "highlight". */
    val meteringMode: String? = null,
    /** White balance mode, e.g. "auto", "daylight", "cloud". API key `WBMode`. */
    val wbMode: String? = null,
    /** Custom Image / effect, e.g. "off", "col_vivid", "efc_posiFilm", "efc_monochrome". */
    val effect: String? = null,
    /** Still capture format: "jpeg", "dng", "rawdng". */
    val stillFormat: String? = null,
    /** Still image quality/size, e.g. "L3", "M3", "S3", "XS3". */
    val stillSize: String? = null,
    /** Capture method / drive mode, e.g. "single", "continuous", "self10s". */
    val shootMode: String? = null,
    /** Focus mode: "af" or "mf". */
    val focusMode: String? = null,
) {
    /** The API `rest_key` -> value map, dropping null fields. */
    fun toFormFields(): Map<String, String> = buildMap {
        iso?.let { put("sv", it) }
        shutterSpeed?.let { put("tv", it) }
        aperture?.let { put("av", it) }
        exposureComp?.let { put("xv", it) }
        exposureMode?.let { put("exposureMode", it) }
        meteringMode?.let { put("meteringMode", it) }
        wbMode?.let { put("WBMode", it) }
        effect?.let { put("effect", it) }
        stillFormat?.let { put("stillFormat", it) }
        stillSize?.let { put("stillSize", it) }
        shootMode?.let { put("shootMode", it) }
        focusMode?.let { put("focusMode", it) }
    }
}
