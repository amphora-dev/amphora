package app.amphora.gamesession.wineandroid

import android.graphics.Rect
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import java.io.File

/**
 * Kotlin stand-in for WineActivity's public HWND/Surface surface — **no WineActivity.java**.
 *
 * Surface feedback replaces JNI `wine_surface_changed(hwnd, surface, opengl)`:
 * [WineAndroidNative] keeps `ANativeWindow` in `:session`, opens a socketpair,
 * serves native_handle dequeue/queue, and sends the wine peer fd via
 * [WineAndroidProtocol.HOST_SURFACE_CHANGED] SCM_RIGHTS so unix can
 * `register_native_window` a forwarding parent.
 */
class WineAndroidHostBridge(
    private val activity: ComponentActivity,
    private val desktop: WineAndroidDesktop,
    private val onSurfaceChanged: (hwnd: Int, surface: Surface, opengl: Boolean) -> Unit,
) {
    private val windows = LinkedHashMap<Pair<Int, Boolean>, WineAndroidWindow>()
    private var hostSocket: WineAndroidHostSocket? = null
    private val surfaceSessions = LinkedHashMap<Pair<Int, Boolean>, SurfaceSession>()

    private data class SurfaceSession(val servePtr: Long, val opengl: Boolean)

    fun createDesktopWindow(hwnd: Int) {
        Log.i(TAG, "createDesktopWindow hwnd=$hwnd")
    }

    fun createWindow(hwnd: Int, opengl: Boolean, parent: Int, scale: Float, pid: Int) {
        Log.i(TAG, "createWindow hwnd=$hwnd opengl=$opengl parent=$parent scale=$scale pid=$pid")
        activity.runOnUiThread {
            val sibling = windows[hwnd to !opengl]
            val window =
                WineAndroidWindow(
                    hwnd = hwnd,
                    parentHwnd = parent,
                    isClient = opengl,
                    scale = scale,
                )
            if (sibling != null) {
                window.windowRect = android.graphics.Rect(sibling.windowRect)
                window.clientRect = android.graphics.Rect(sibling.clientRect)
            }
            windows[hwnd to opengl] = window
            desktop.attachWindow(window) { attachedHwnd, surface ->
                if (surface != null) {
                    notifySurface(attachedHwnd, surface, opengl, ready = true)
                } else {
                    releaseSurfaceSession(attachedHwnd, opengl, notify = true)
                }
            }
        }
    }

    fun destroyWindow(hwnd: Int) {
        Log.i(TAG, "destroyWindow hwnd=$hwnd")
        activity.runOnUiThread {
            val gdi = windows.remove(hwnd to false)
            val client = windows.remove(hwnd to true)
            desktop.detachWindow(hwnd)
            if (gdi != null) releaseSurfaceSession(hwnd, false, notify = true)
            if (client != null) releaseSurfaceSession(hwnd, true, notify = true)
        }
    }

    fun setParent(hwnd: Int, parent: Int, scale: Float, pid: Int) {
        Log.i(TAG, "setParent hwnd=$hwnd parent=$parent scale=$scale pid=$pid")
        activity.runOnUiThread {
            listOf(false, true).forEach { isClient ->
                val existing = windows[hwnd to isClient] ?: return@forEach
                val updated = existing.copy(parentHwnd = parent, scale = scale)
                windows[hwnd to isClient] = updated
                desktop.updateWindow(updated)
            }
        }
    }

    fun windowPosChanged(
        hwnd: Int,
        flags: Int,
        insertAfter: Int,
        owner: Int,
        style: Int,
        windowRect: Rect,
        clientRect: Rect,
        visibleRect: Rect,
    ) {
        Log.i(
            TAG,
            "windowPosChanged hwnd=$hwnd flags=$flags after=$insertAfter owner=$owner " +
                "style=$style window=$windowRect client=$clientRect visible=$visibleRect",
        )
        activity.runOnUiThread {
            desktop.updateHwndRects(hwnd, windowRect, clientRect)
        }
    }

    fun startHostSocket(socketFile: File) {
        hostSocket?.close()
        val socket = WineAndroidHostSocket(this)
        hostSocket = socket
        socket.start(socketFile)
        Log.i(TAG, "host socket started at ${socketFile.absolutePath}")
    }

    fun updateDesktopMetrics(width: Int, height: Int, scale: Float = 1f) {
        hostSocket?.updateDesktopMetrics(width, height, scale)
    }

    @Deprecated("Use startHostSocket", ReplaceWith("startHostSocket(socketFile)"))
    fun startSocketStub(socketFile: File) = startHostSocket(socketFile)

    fun close() {
        surfaceSessions.keys.toList().forEach { (hwnd, opengl) ->
            releaseSurfaceSession(hwnd, opengl, notify = false)
        }
        hostSocket?.close()
        hostSocket = null
        activity.runOnUiThread {
            windows.keys.map { it.first }.distinct().forEach { desktop.detachWindow(it) }
            windows.clear()
        }
    }

    private fun notifySurface(hwnd: Int, surface: Surface, opengl: Boolean, ready: Boolean) {
        onSurfaceChanged(hwnd, surface, opengl)
        if (!ready) {
            releaseSurfaceSession(hwnd, opengl, notify = true)
            return
        }
        releaseSurfaceSession(hwnd, opengl, notify = false)

        val windowHandle =
            try {
                WineAndroidNative.nativeAcquireWindow(surface)
            } catch (t: Throwable) {
                Log.e(TAG, "nativeAcquireWindow failed hwnd=$hwnd", t)
                0L
            }
        if (windowHandle == 0L) {
            Log.w(TAG, "no ANativeWindow for hwnd=$hwnd — surface_changed without fd")
            hostSocket?.sendSurfaceChanged(hwnd, opengl, ready = true)
            return
        }

        val pair =
            try {
                createBufferSocketPair()
            } catch (t: Throwable) {
                Log.e(TAG, "socketpair failed hwnd=$hwnd", t)
                WineAndroidNative.nativeReleaseWindow(windowHandle)
                hostSocket?.sendSurfaceChanged(hwnd, opengl, ready = true)
                return
            }
        val wineEnd = pair.first
        val hostEnd = pair.second

        val width = WineAndroidNative.nativeWindowWidth(windowHandle)
        val height = WineAndroidNative.nativeWindowHeight(windowHandle)
        val hostFd =
            try {
                hostEnd.detachFd()
            } catch (t: Throwable) {
                Log.e(TAG, "detachFd failed hwnd=$hwnd", t)
                wineEnd.close()
                hostEnd.close()
                WineAndroidNative.nativeReleaseWindow(windowHandle)
                return
            }

        val servePtr =
            WineAndroidNative.nativeStartBufferServe(windowHandle, hwnd, hostFd)
        if (servePtr == 0L) {
            Log.e(TAG, "nativeStartBufferServe failed hwnd=$hwnd")
            wineEnd.close()
            WineAndroidNative.nativeReleaseWindow(windowHandle)
            hostSocket?.sendSurfaceChanged(hwnd, opengl, ready = true)
            return
        }

        surfaceSessions[hwnd to opengl] = SurfaceSession(servePtr = servePtr, opengl = opengl)

        try {
            hostSocket?.sendSurfaceChanged(
                hwnd = hwnd,
                opengl = opengl,
                ready = true,
                width = width,
                height = height,
                wineBufferFd = wineEnd.fileDescriptor,
            )
        } finally {
            try {
                wineEnd.close()
            } catch (_: Exception) {
            }
        }
        // Serve thread holds its own ANativeWindow ref.
        WineAndroidNative.nativeReleaseWindow(windowHandle)
    }

    private fun releaseSurfaceSession(hwnd: Int, opengl: Boolean, notify: Boolean) {
        val session = surfaceSessions.remove(hwnd to opengl) ?: run {
            if (notify) hostSocket?.sendSurfaceChanged(hwnd, opengl = opengl, ready = false)
            return
        }
        WineAndroidNative.nativeStopBufferServe(session.servePtr, -1)
        if (notify) {
            hostSocket?.sendSurfaceChanged(hwnd, session.opengl, ready = false)
        }
    }

    private companion object {
        const val TAG = "WineAndroidHostBridge"
    }
}
