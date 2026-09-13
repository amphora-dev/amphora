package app.amphora.gamesession.wineandroid

import android.view.Surface

/**
 * JNI bridge for wineandroid host Surfaces.
 *
 * [android.view.Surface] → `ANativeWindow` stays in the `:session` process.
 * Each HWND gets a socketpair; the peer fd is sent to unix wineandroid.drv via
 * SCM_RIGHTS on [WineAndroidProtocol.HOST_SURFACE_CHANGED]. Wine installs a
 * forwarding parent for `register_native_window`; buffer ops use the same
 * native_handle fd layout as `dlls/wineandroid.drv/device.c`.
 */
object WineAndroidNative {
    init {
        System.loadLibrary("winlator")
    }

    external fun nativeAcquireWindow(surface: Surface): Long

    external fun nativeReleaseWindow(handle: Long)

    external fun nativeStartBufferServe(windowHandle: Long, hwnd: Int, sockFd: Int): Long

    external fun nativeStopBufferServe(servePtr: Long, sockFd: Int)

    external fun nativeBumpGeneration(servePtr: Long)

    external fun nativeWindowWidth(windowHandle: Long): Int

    external fun nativeWindowHeight(windowHandle: Long): Int
}
