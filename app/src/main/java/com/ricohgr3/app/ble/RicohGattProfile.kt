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

    /** Client Characteristic Configuration Descriptor — enables notifications. */
    val CCCD: UUID = shortUuid(0x2902)

    /** Build a 128-bit UUID from a 16-bit Bluetooth SIG assigned number. */
    private fun shortUuid(short: Int): UUID =
        UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))
}
