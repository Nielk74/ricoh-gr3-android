package com.ricohgr3.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure encoding/parsing tests for the GATT payload helpers — no BluetoothGatt required.
 */
class RicohGattProfileTest {

    @Test
    fun `shutter payload with AF is START_SHOOTING then AF`() {
        assertArrayEquals(byteArrayOf(1, 1), RicohGattProfile.shutterPayload(af = true))
    }

    @Test
    fun `shutter payload without AF is START_SHOOTING then NO_AF`() {
        assertArrayEquals(byteArrayOf(1, 0), RicohGattProfile.shutterPayload(af = false))
    }

    @Test
    fun `shutter payload uses profile constants`() {
        val expected = byteArrayOf(
            RicohGattProfile.OperationCode.START_SHOOTING,
            RicohGattProfile.OperationParameter.AF,
        )
        assertArrayEquals(expected, RicohGattProfile.shutterPayload(af = true))
    }

    // ---- WLAN handoff helpers ----------------------------------------------

    @Test
    fun `network type payload enable is AP_MODE`() {
        assertArrayEquals(byteArrayOf(1), RicohGattProfile.networkTypePayload(enable = true))
        assertArrayEquals(
            byteArrayOf(RicohGattProfile.NetworkType.AP_MODE),
            RicohGattProfile.networkTypePayload(enable = true),
        )
    }

    @Test
    fun `network type payload disable is OFF`() {
        assertArrayEquals(byteArrayOf(0), RicohGattProfile.networkTypePayload(enable = false))
        assertArrayEquals(
            byteArrayOf(RicohGattProfile.NetworkType.OFF),
            RicohGattProfile.networkTypePayload(enable = false),
        )
    }

    @Test
    fun `parseWlanString decodes plain utf8`() {
        val bytes = "GR_ABCDEF".toByteArray(Charsets.UTF_8)
        assertEquals("GR_ABCDEF", RicohGattProfile.parseWlanString(bytes))
    }

    @Test
    fun `parseWlanString strips trailing NUL padding`() {
        val bytes = "secretpass".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0, 0)
        assertEquals("secretpass", RicohGattProfile.parseWlanString(bytes))
    }

    @Test
    fun `parseWlanString strips surrounding whitespace`() {
        val bytes = "  GR_WIFI \n".toByteArray(Charsets.UTF_8)
        assertEquals("GR_WIFI", RicohGattProfile.parseWlanString(bytes))
    }

    @Test
    fun `parseWlanString preserves inner characters`() {
        val bytes = "pa ss-12_34".toByteArray(Charsets.UTF_8)
        assertEquals("pa ss-12_34", RicohGattProfile.parseWlanString(bytes))
    }
}
