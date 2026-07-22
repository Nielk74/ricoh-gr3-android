package com.ricohgr3.app.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoRepositoryTest {
    @Test(expected = CancellationException::class)
    fun `result mapping never swallows coroutine cancellation`() {
        PhotoResult.runCatchingResult<Unit> { throw CancellationException("stopped") }
    }

    @Test
    fun `ordinary transport exceptions remain user-facing errors`() {
        val result = PhotoResult.runCatchingResult<Unit> { throw java.io.IOException("offline") }

        assertEquals("offline", (result as PhotoResult.Error).message)
    }
}
