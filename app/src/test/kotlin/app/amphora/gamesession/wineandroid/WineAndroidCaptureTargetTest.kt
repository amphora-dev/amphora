package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class WineAndroidCaptureTargetTest {
    @Test
    fun zeroCaptureUsesHitTest() {
        assertEquals(0x42, WineAndroidCaptureTarget.resolve(captureHwnd = 0, hitTestHwnd = 0x42))
        assertEquals(0, WineAndroidCaptureTarget.resolve(captureHwnd = 0, hitTestHwnd = 0))
    }

    @Test
    fun nonZeroCaptureOverridesHitTest() {
        assertEquals(0x10, WineAndroidCaptureTarget.resolve(captureHwnd = 0x10, hitTestHwnd = 0x99))
        assertEquals(0x10, WineAndroidCaptureTarget.resolve(captureHwnd = 0x10, hitTestHwnd = 0))
    }

    @Test
    fun releaseThenHitTestAgain() {
        val captured = WineAndroidCaptureTarget.resolve(0x55, 0x11)
        assertEquals(0x55, captured)
        assertEquals(0x11, WineAndroidCaptureTarget.resolve(0, 0x11))
    }
}
