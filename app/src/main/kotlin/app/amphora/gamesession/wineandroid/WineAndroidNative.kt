package app.amphora.gamesession.wineandroid

import android.view.Surface

/**
 * JNI for the upstream wineandroid host IPC server.
 *
 * Binds abstract `\0\Device\WineAndroid` (SEQPACKET) and serves device.c ioctls.
 * Surfaces stay in `:session`; [nativeRegisterSurface] attaches ANativeWindow
 * for buffer ioctls and writes SURFACE_CHANGED on the desktop event pipe.
 */
object WineAndroidNative {
    init {
        System.loadLibrary("winlator")
    }

    external fun nativeStartServer(callbacks: WineAndroidIpcCallbacks): Boolean

    external fun nativeStopServer()

    external fun nativeRegisterSurface(hwnd: Int, surface: Surface, opengl: Boolean): Boolean

    external fun nativeUnregisterSurface(hwnd: Int, opengl: Boolean)

    external fun nativeNotifyDesktopChanged(width: Int, height: Int)

    external fun nativeNotifyConfigChanged(dpi: Int)
}
