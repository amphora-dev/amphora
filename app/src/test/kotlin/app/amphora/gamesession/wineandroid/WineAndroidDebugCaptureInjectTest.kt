package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WineAndroidDebugCaptureInjectTest {
    @Test
    fun extraConstantMatchesSmokeContract() {
        assertEquals(
            "app.amphora.debug.CAPTURE_HWND",
            WineAndroidDebugCaptureInject.EXTRA_CAPTURE_HWND,
        )
        assertEquals(-1, WineAndroidDebugCaptureInject.SENTINEL_DESKTOP)
    }

    @Test
    fun nullWhenNotDebuggableOrAbsent() {
        assertNull(
            WineAndroidDebugCaptureInject.captureHwndIfDebuggable(
                present = true,
                value = 0x42,
                debuggable = false,
            ),
        )
        assertNull(
            WineAndroidDebugCaptureInject.captureHwndIfDebuggable(
                present = false,
                value = 0x42,
                debuggable = true,
            ),
        )
    }

    @Test
    fun returnsValueIncludingZeroAndSentinelWhenPresent() {
        assertEquals(
            0x10,
            WineAndroidDebugCaptureInject.captureHwndIfDebuggable(
                present = true,
                value = 0x10,
                debuggable = true,
            ),
        )
        assertEquals(
            0,
            WineAndroidDebugCaptureInject.captureHwndIfDebuggable(
                present = true,
                value = 0,
                debuggable = true,
            ),
        )
        assertEquals(
            WineAndroidDebugCaptureInject.SENTINEL_DESKTOP,
            WineAndroidDebugCaptureInject.captureHwndIfDebuggable(
                present = true,
                value = WineAndroidDebugCaptureInject.SENTINEL_DESKTOP,
                debuggable = true,
            ),
        )
    }

    @Test
    fun resolveSentinelDefersUntilDesktopReady() {
        assertNull(
            WineAndroidDebugCaptureInject.resolveCaptureTarget(
                requested = WineAndroidDebugCaptureInject.SENTINEL_DESKTOP,
                desktopHwnd = 0,
            ),
        )
        assertEquals(
            0x55,
            WineAndroidDebugCaptureInject.resolveCaptureTarget(
                requested = WineAndroidDebugCaptureInject.SENTINEL_DESKTOP,
                desktopHwnd = 0x55,
            ),
        )
    }

    @Test
    fun resolveConcretePassesThroughIncludingRelease() {
        assertEquals(
            0x99,
            WineAndroidDebugCaptureInject.resolveCaptureTarget(requested = 0x99, desktopHwnd = 0),
        )
        assertEquals(
            0,
            WineAndroidDebugCaptureInject.resolveCaptureTarget(requested = 0, desktopHwnd = 0x55),
        )
    }

    @Test
    fun relayForwardOmitsOrPuts() {
        assertNull(WineAndroidDebugCaptureInject.relayForward(capturePresent = false, captureValue = 1))
        assertEquals(
            WineAndroidDebugCaptureInject.SENTINEL_DESKTOP,
            WineAndroidDebugCaptureInject.relayForward(
                capturePresent = true,
                captureValue = WineAndroidDebugCaptureInject.SENTINEL_DESKTOP,
            ),
        )
        assertEquals(
            0,
            WineAndroidDebugCaptureInject.relayForward(capturePresent = true, captureValue = 0),
        )
    }
}
