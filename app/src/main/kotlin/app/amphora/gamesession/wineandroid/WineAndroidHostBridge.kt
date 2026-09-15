package app.amphora.gamesession.wineandroid

import android.graphics.Rect
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity

/**
 * Kotlin stand-in for WineActivity's HWND/Surface surface — **no WineActivity.java**.
 *
 * Implements [WineAndroidIpcCallbacks] for the native SEQPACKET server. Surface
 * ready → [WineAndroidNative.nativeRegisterSurface] (local ANativeWindow + event
 * pipe SURFACE_CHANGED). No private HOST_* frames.
 */
class WineAndroidHostBridge(private val activity: ComponentActivity, private val desktop: WineAndroidDesktop) :
    WineAndroidIpcCallbacks {
    private val windows = LinkedHashMap<Pair<Int, Boolean>, WineAndroidWindow>()

    @Volatile private var desktopWidth: Int = 0

    @Volatile private var desktopHeight: Int = 0

    @Volatile private var desktopDpi: Int = 0

    @Volatile private var serverStarted: Boolean = false

    fun startServer() {
        if (serverStarted) return
        val ok =
            try {
                WineAndroidNative.nativeStartServer(this)
            } catch (t: Throwable) {
                Log.e(TAG, "nativeStartServer failed", t)
                false
            }
        serverStarted = ok
        if (ok) {
            Log.i(TAG, "IPC server listening on abstract \\0\\Device\\WineAndroid")
            maybeNotifyDesktop()
        } else {
            Log.e(TAG, "IPC server failed to start")
        }
    }

    fun updateDesktopMetrics(width: Int, height: Int, densityDpi: Int = 0) {
        if (width > 0 && height > 0) {
            desktopWidth = width
            desktopHeight = height
        }
        if (densityDpi > 0) desktopDpi = densityDpi
        maybeNotifyDesktop()
    }

    fun close() {
        windows.keys.toList().forEach { (hwnd, opengl) ->
            WineAndroidNative.nativeUnregisterSurface(hwnd, opengl)
        }
        if (serverStarted) {
            try {
                WineAndroidNative.nativeStopServer()
            } catch (t: Throwable) {
                Log.w(TAG, "nativeStopServer", t)
            }
            serverStarted = false
        }
        activity.runOnUiThread {
            windows.keys.map { it.first }.distinct().forEach { desktop.detachWindow(it) }
            windows.clear()
        }
    }

    override fun createDesktopView() {
        Log.i(TAG, "createDesktopView")
        activity.runOnUiThread { maybeNotifyDesktop() }
    }

    override fun createWindow(hwnd: Int, isDesktop: Boolean, opengl: Boolean, parent: Int) {
        Log.i(TAG, "createWindow hwnd=$hwnd desktop=$isDesktop opengl=$opengl parent=$parent")
        activity.runOnUiThread {
            // Desktop is also a GDI HWND: attach SurfaceView + registerSurface.
            // Skipping left LOCK at -11 (no ANW). Force GDI for desktop only —
            // do not force CPU/GDI on real OpenGL client windows.
            val useOpengl = if (isDesktop) false else opengl
            if (isDesktop) {
                Log.i(TAG, "desktop hwnd=$hwnd attaching GDI SurfaceView")
            }
            val sibling = windows[hwnd to !useOpengl]
            val window =
                WineAndroidWindow(
                    hwnd = hwnd,
                    parentHwnd = parent,
                    isClient = useOpengl,
                    scale = 1f,
                )
            if (sibling != null) {
                window.windowRect = Rect(sibling.windowRect)
                window.clientRect = Rect(sibling.clientRect)
            } else if (isDesktop && desktopWidth > 0 && desktopHeight > 0) {
                window.windowRect = Rect(0, 0, desktopWidth, desktopHeight)
                window.clientRect = Rect(0, 0, desktopWidth, desktopHeight)
            }
            windows[hwnd to useOpengl] = window
            desktop.attachWindow(window) { attachedHwnd, surface ->
                onSurface(attachedHwnd, surface, useOpengl)
            }
        }
    }

    override fun destroyWindow(hwnd: Int) {
        Log.i(TAG, "destroyWindow hwnd=$hwnd")
        activity.runOnUiThread {
            val gdi = windows.remove(hwnd to false)
            val client = windows.remove(hwnd to true)
            desktop.detachWindow(hwnd)
            if (gdi != null) WineAndroidNative.nativeUnregisterSurface(hwnd, false)
            if (client != null) WineAndroidNative.nativeUnregisterSurface(hwnd, true)
        }
    }

    override fun setParent(hwnd: Int, parent: Int) {
        Log.i(TAG, "setParent hwnd=$hwnd parent=$parent")
        activity.runOnUiThread {
            listOf(false, true).forEach { isClient ->
                val existing = windows[hwnd to isClient] ?: return@forEach
                val updated = existing.copy(parentHwnd = parent)
                windows[hwnd to isClient] = updated
                desktop.updateWindow(updated)
            }
        }
    }

    override fun windowPosChanged(
        hwnd: Int,
        flags: Int,
        insertAfter: Int,
        owner: Int,
        style: Int,
        windowLeft: Int,
        windowTop: Int,
        windowRight: Int,
        windowBottom: Int,
        clientLeft: Int,
        clientTop: Int,
        clientRight: Int,
        clientBottom: Int,
        visibleLeft: Int,
        visibleTop: Int,
        visibleRight: Int,
        visibleBottom: Int,
    ) {
        val windowRect = Rect(windowLeft, windowTop, windowRight, windowBottom)
        val clientRect = Rect(clientLeft, clientTop, clientRight, clientBottom)
        Log.i(
            TAG,
            "windowPosChanged hwnd=$hwnd flags=$flags after=$insertAfter owner=$owner " +
                "style=$style window=$windowRect client=$clientRect",
        )
        activity.runOnUiThread {
            desktop.updateHwndRects(hwnd, windowRect, clientRect)
        }
    }

    override fun setCapture(hwnd: Int) {
        Log.i(TAG, "setCapture hwnd=$hwnd")
    }

    override fun setCursor(id: Int, width: Int, height: Int, hotspotX: Int, hotspotY: Int, bits: IntArray?) {
        Log.i(TAG, "setCursor id=$id ${width}x$height hotspot=$hotspotX,$hotspotY bits=${bits?.size ?: 0}")
    }

    private fun onSurface(hwnd: Int, surface: Surface?, opengl: Boolean) {
        if (surface == null) {
            WineAndroidNative.nativeUnregisterSurface(hwnd, opengl)
            return
        }
        val ok = WineAndroidNative.nativeRegisterSurface(hwnd, surface, opengl)
        Log.i(TAG, "registerSurface hwnd=$hwnd opengl=$opengl ok=$ok")
    }

    private fun maybeNotifyDesktop() {
        val w = desktopWidth
        val h = desktopHeight
        if (w <= 0 || h <= 0 || !serverStarted) return
        try {
            WineAndroidNative.nativeNotifyDesktopChanged(w, h)
            if (desktopDpi > 0) WineAndroidNative.nativeNotifyConfigChanged(desktopDpi)
        } catch (t: Throwable) {
            Log.w(TAG, "notifyDesktop failed", t)
        }
    }

    private companion object {
        const val TAG = "WineAndroidHostBridge"
    }
}
