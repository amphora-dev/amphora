package app.amphora.gamesession.wineandroid

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

/**
 * Kotlin desktop: one [FrameLayout], per-HWND [SurfaceView] children.
 *
 * This is Amphora's host, not WineActivity and not Winlator's X server.
 * Wine will talk to it through a bridge we own (not org.winehq JNI class names).
 * Until that bridge exists, [attachWindow] is what P1 tests call directly.
 *
 * Surface buffer size must match the Win32 window rect (launcher /desktop=WxH),
 * not the Activity display (often 1920×1200 on redroid). createWindow arrives
 * before windowPosChanged with an empty rect; if we addView then, SurfaceView
 * allocates MATCH_PARENT (= display) and a later setFixedSize rebind races GDI
 * LOCK. Defer addView until the rect is known, setFixedSize before the surface
 * is created, and notify HostBridge once from surfaceChanged (not also
 * surfaceCreated) so HOST_SURFACE_CHANGED reports the guest size once.
 */
class WineAndroidDesktop(context: Context) : FrameLayout(context) {
    private val windows = LinkedHashMap<Int, WindowSurface>()

    fun attachWindow(window: WineAndroidWindow, onSurface: (hwnd: Int, surface: Surface?) -> Unit) {
        val existing = windows.remove(window.hwnd)
        existing?.let {
            if (it.view.parent === this) removeView(it.view)
        }
        val held = WindowSurface(context, window, onSurface)
        windows[window.hwnd] = held
        maybeAttachView(held, window)
    }

    fun detachWindow(hwnd: Int) {
        windows.remove(hwnd)?.let {
            if (it.view.parent === this) removeView(it.view)
        }
    }

    fun updateWindow(window: WineAndroidWindow) {
        val held = windows[window.hwnd] ?: return
        held.window = window
        maybeAttachView(held, window)
        if (held.view.parent === this) {
            held.view.layoutParams = childParams(window)
            applyFixedSize(held, window)
            held.view.requestLayout()
        }
    }

    private fun maybeAttachView(held: WindowSurface, window: WineAndroidWindow) {
        val r = window.windowRect
        if (r.width() <= 0 || r.height() <= 0) {
            Log.i(TAG, "defer SurfaceView hwnd=${window.hwnd} until windowPosChanged")
            return
        }
        applyFixedSize(held, window)
        if (held.view.parent !== this) {
            Log.i(TAG, "addView hwnd=${window.hwnd} ${r.width()}x${r.height()}")
            addView(held.view, childParams(window))
        }
    }

    private fun childParams(window: WineAndroidWindow): LayoutParams {
        val r = window.windowRect
        return LayoutParams(r.width(), r.height()).apply {
            leftMargin = r.left
            topMargin = r.top
        }
    }

    private fun applyFixedSize(held: WindowSurface, window: WineAndroidWindow) {
        val w = window.windowRect.width()
        val h = window.windowRect.height()
        if (w <= 0 || h <= 0) return
        if (held.fixedWidth == w && held.fixedHeight == h) return
        held.fixedWidth = w
        held.fixedHeight = h
        Log.i(TAG, "setFixedSize hwnd=${window.hwnd} ${w}x$h")
        held.view.holder.setFixedSize(w, h)
    }

    private class WindowSurface(
        context: Context,
        var window: WineAndroidWindow,
        onSurface: (hwnd: Int, surface: Surface?) -> Unit,
    ) {
        var fixedWidth: Int = 0
        var fixedHeight: Int = 0
        val view = SurfaceView(context).apply {
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        // surfaceChanged follows immediately with WxH; notify there
                        // so HostBridge acquires ANW once at the fixed size.
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        Log.i(TAG, "surfaceChanged hwnd=${window.hwnd} ${width}x$height")
                        val surface = holder.surface
                        window.surface = surface
                        onSurface(window.hwnd, surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        window.surface = null
                        onSurface(window.hwnd, null)
                    }
                },
            )
        }
    }

    private companion object {
        const val TAG = "WineAndroidDesktop"
    }
}
