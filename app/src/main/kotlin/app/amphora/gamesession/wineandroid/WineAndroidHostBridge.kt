package app.amphora.gamesession.wineandroid

import android.graphics.Rect
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import java.io.File

/**
 * Kotlin stand-in for WineActivity's public HWND/Surface surface — **no JNI**.
 *
 * Field / method names mirror pin `WineActivity.java` and
 * `dlls/wineandroid.drv/device.c` ioctl structs. Amphora runs Wine via box64
 * exec in `:session`; [WineAndroidHostSocket] accepts the same messages over
 * `AMPHORA_WINEANDROID_SOCK` (see [WineAndroidProtocol]).
 *
 * ioctl ops (device.c `enum android_ioctl`):
 * - IOCTL_CREATE_WINDOW      -> createWindow(hwnd, opengl, parent, scale, pid)
 * - IOCTL_DESTROY_WINDOW     -> destroyWindow(hwnd)
 * - IOCTL_WINDOW_POS_CHANGED -> windowPosChanged(…)
 * - IOCTL_SET_WINDOW_PARENT  -> setParent(hwnd, parent, scale, pid)
 *
 * Surface feedback replaces JNI `wine_surface_changed(hwnd, surface, opengl)`
 * with [WineAndroidProtocol.HOST_SURFACE_CHANGED] (hwnd/opengl/ready only;
 * native handle / `register_native_window` waits for proton-wine after WCP).
 */
class WineAndroidHostBridge(
    private val activity: ComponentActivity,
    private val desktop: WineAndroidDesktop,
    private val onSurfaceChanged: (hwnd: Int, surface: Surface, opengl: Boolean) -> Unit,
) {
    private val windows = LinkedHashMap<Int, WineAndroidWindow>()
    private var hostSocket: WineAndroidHostSocket? = null

    fun createDesktopWindow(hwnd: Int) {
        Log.i(TAG, "createDesktopWindow hwnd=$hwnd")
    }

    fun createWindow(hwnd: Int, opengl: Boolean, parent: Int, scale: Float, pid: Int) {
        Log.i(TAG, "createWindow hwnd=$hwnd opengl=$opengl parent=$parent scale=$scale pid=$pid")
        activity.runOnUiThread {
            val window =
                WineAndroidWindow(
                    hwnd = hwnd,
                    parentHwnd = parent,
                    isClient = opengl,
                    scale = scale,
                )
            windows[hwnd] = window
            desktop.attachWindow(window) { attachedHwnd, surface ->
                notifySurface(attachedHwnd, surface, opengl, ready = true)
            }
        }
    }

    fun destroyWindow(hwnd: Int) {
        Log.i(TAG, "destroyWindow hwnd=$hwnd")
        activity.runOnUiThread {
            val existing = windows.remove(hwnd)
            desktop.detachWindow(hwnd)
            if (existing != null) {
                hostSocket?.sendSurfaceChanged(hwnd, existing.isClient, ready = false)
            }
        }
    }

    fun setParent(hwnd: Int, parent: Int, scale: Float, pid: Int) {
        Log.i(TAG, "setParent hwnd=$hwnd parent=$parent scale=$scale pid=$pid")
        activity.runOnUiThread {
            val existing = windows[hwnd] ?: return@runOnUiThread
            val updated = existing.copy(parentHwnd = parent, scale = scale)
            windows[hwnd] = updated
            desktop.updateWindow(updated)
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
            val existing = windows[hwnd] ?: return@runOnUiThread
            val updated =
                existing.copy().also {
                    it.windowRect = Rect(windowRect)
                    it.clientRect = Rect(clientRect)
                }
            windows[hwnd] = updated
            desktop.updateWindow(updated)
        }
    }

    /**
     * Bind/listen on [socketFile] (`AMPHORA_WINEANDROID_SOCK`) and decode
     * ioctl-shaped frames into the methods above.
     */
    fun startHostSocket(socketFile: File) {
        hostSocket?.close()
        val socket = WineAndroidHostSocket(this)
        hostSocket = socket
        socket.start(socketFile)
        Log.i(TAG, "host socket started at ${socketFile.absolutePath}")
    }

    @Deprecated("Use startHostSocket", ReplaceWith("startHostSocket(socketFile)"))
    fun startSocketStub(socketFile: File) = startHostSocket(socketFile)

    fun close() {
        hostSocket?.close()
        hostSocket = null
        activity.runOnUiThread {
            windows.keys.toList().forEach { desktop.detachWindow(it) }
            windows.clear()
        }
    }

    private fun notifySurface(hwnd: Int, surface: Surface, opengl: Boolean, ready: Boolean) {
        onSurfaceChanged(hwnd, surface, opengl)
        hostSocket?.sendSurfaceChanged(hwnd, opengl, ready)
    }

    private companion object {
        const val TAG = "WineAndroidHostBridge"
    }
}
