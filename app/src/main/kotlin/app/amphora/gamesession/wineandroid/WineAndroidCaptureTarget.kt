package app.amphora.gamesession.wineandroid

/**
 * Pure Win32 SetCapture target selection for wineandroid MOTION routing.
 *
 * Upstream `ANDROID_SetCapture` only ioctl's during GUI_INMOVESIZE / GUI_INMENUMODE;
 * Amphora's host already receives [WineAndroidIpcCallbacks.setCapture]. When
 * [captureHwnd] is non-zero, all touch / generic-motion events should address
 * that HWND (guest desktop ABSOLUTE coords still come from the hit view).
 * Zero releases capture and restores ordinary hit-test hwnd.
 */
object WineAndroidCaptureTarget {
    /** Non-zero [captureHwnd] wins; otherwise the view under the pointer. */
    fun resolve(captureHwnd: Int, hitTestHwnd: Int): Int =
        if (captureHwnd != 0) captureHwnd else hitTestHwnd
}
