package app.amphora.core.common.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResultTest {
    @Test
    fun successBlockYieldsSuccess() {
        val r = appResult { 42 }
        assertTrue(r is AppResult.Success)
        assertEquals(42, (r as AppResult.Success).data)
    }

    @Test
    fun throwingBlockYieldsFailure() {
        val r = appResult { error("boom") }
        assertTrue(r is AppResult.Failure)
        assertTrue((r as AppResult.Failure).error is AmphoraError.Unknown)
    }
}
