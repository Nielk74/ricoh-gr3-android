package com.ricohgr3.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Pure encoding tests for the shutter payload — no BluetoothGatt required.
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
}
