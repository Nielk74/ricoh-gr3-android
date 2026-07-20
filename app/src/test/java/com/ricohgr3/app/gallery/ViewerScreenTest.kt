package com.ricohgr3.app.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerScreenTest {
    @Test
    fun `edited DNG export requires the platform RAW renderer`() {
        assertFalse(supportsPlatformDngDevelop(26))
        assertFalse(supportsPlatformDngDevelop(27))
        assertTrue(supportsPlatformDngDevelop(28))
        assertTrue(supportsPlatformDngDevelop(34))
    }
}
