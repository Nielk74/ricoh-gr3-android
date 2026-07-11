package com.ricohgr3.app.ble

import java.util.UUID

/**
 * BLE GATT profile for the Ricoh GR III / GR IIIx, from the reverse-engineered
 * specification (see research/references/ricoh-gr-bluetooth-api).
 *
 * These UUIDs are unofficial and derived from community reverse engineering plus
 * Ricoh's public THETA API specs. Use at your own risk.
 */
object RicohGattProfile {

    /** Standard BLE "Device Information" service (0x180A) — model, firmware, serial. */
    val DEVICE_INFORMATION_SERVICE: UUID = shortUuid(0x180A)
    val MODEL_NUMBER_STRING: UUID = shortUuid(0x2A24)
    val SERIAL_NUMBER_STRING: UUID = shortUuid(0x2A25)
    val FIRMWARE_REVISION_STRING: UUID = shortUuid(0x2A26)
    val MANUFACTURER_NAME_STRING: UUID = shortUuid(0x2A29)

    /**
     * Shooting service — the "Operation Request" characteristic fires the shutter.
     * Write [OperationCode, Parameter] as two signed bytes.
     */
    val SHOOTING_SERVICE: UUID = UUID.fromString("9F00F387-8345-4BBC-8B92-B87B52E3091A")
    val OPERATION_REQUEST: UUID = UUID.fromString("559644B8-E0BC-4011-929B-5CF9199851E7")

    /** OperationCode values for [OPERATION_REQUEST]. */
    object OperationCode {
        const val NOP: Byte = 0
        const val START_SHOOTING: Byte = 1
        const val STOP_SHOOTING: Byte = 2
    }

    /** Parameter values for [OPERATION_REQUEST]. */
    object OperationParameter {
        const val NO_AF: Byte = 0
        const val AF: Byte = 1
        const val GREEN_BUTTON: Byte = 2
    }

    /**
     * Encode the [OPERATION_REQUEST] payload that fires the shutter.
     * [af] triggers autofocus before capture. Pure + unit-testable (no GATT).
     *
     * @return `[START_SHOOTING, AF]` when [af] is true, else `[START_SHOOTING, NO_AF]`.
     */
    fun shutterPayload(af: Boolean): ByteArray {
        val param = if (af) OperationParameter.AF else OperationParameter.NO_AF
        return byteArrayOf(OperationCode.START_SHOOTING, param)
    }

    /**
     * WLAN Control service — read the camera's Wi-Fi AP credentials (SSID / passphrase)
     * and switch its network type to wake the access point for the HTTP `/v1/` API.
     *
     * Source: research/references/ricoh-gr-bluetooth-api/wlan_control_command/
     *   ssid.md, passphrase.md, network_type.md, channel.md
     */
    val WLAN_CONTROL_SERVICE: UUID = UUID.fromString("F37F568F-9071-445D-A938-5441F2E82399")

    /** SSID characteristic (Read/Write, utf8s). Source: ssid.md */
    val WLAN_SSID: UUID = UUID.fromString("90638E5A-E77D-409D-B550-78F7E1CA5AB4")

    /** Passphrase characteristic (Read/Write, utf8s). Source: passphrase.md */
    val WLAN_PASSPHRASE: UUID = UUID.fromString("0F38279C-FE9E-461B-8596-81287E8C9A81")

    /**
     * Network Type characteristic (Read/Write/Notify, sint8). Writing
     * [NetworkType.AP_MODE] wakes the camera's Wi-Fi access point. Source: network_type.md
     */
    val WLAN_NETWORK_TYPE: UUID = UUID.fromString("9111CDD0-9F01-45C4-A2D4-E09E8FB0424D")

    /** Channel characteristic (Read/Write, sint8: 0=Auto, 1-11=channel). Source: channel.md */
    val WLAN_CHANNEL: UUID = UUID.fromString("51DE6EBC-0F22-4357-87E4-B1FA1D385AB8")

    /** Values for the [WLAN_NETWORK_TYPE] sint8 field (network_type.md). */
    object NetworkType {
        const val OFF: Byte = 0
        const val AP_MODE: Byte = 1
    }

    /**
     * Parse a WLAN Control string characteristic ([WLAN_SSID] / [WLAN_PASSPHRASE]).
     * The camera stores these as utf8s; decode as UTF-8 and strip any trailing NUL
     * padding / whitespace some firmwares include. Pure + unit-testable (no GATT).
     */
    fun parseWlanString(value: ByteArray): String =
        value.toString(Charsets.UTF_8).trim { it == '\u0000' || it.isWhitespace() }

    /**
     * Encode the [WLAN_NETWORK_TYPE] payload: `true` → AP mode (wake the Wi-Fi AP),
     * `false` → OFF. A single sint8 byte per network_type.md. Pure + unit-testable.
     */
    fun networkTypePayload(enable: Boolean): ByteArray =
        byteArrayOf(if (enable) NetworkType.AP_MODE else NetworkType.OFF)

    /** Client Characteristic Configuration Descriptor — enables notifications. */
    val CCCD: UUID = shortUuid(0x2902)

    /** Build a 128-bit UUID from a 16-bit Bluetooth SIG assigned number. */
    private fun shortUuid(short: Int): UUID =
        UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))
}
