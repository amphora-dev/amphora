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
 * not the Activity display (often 1920×1200 on redroid). Without
 * [SurfaceHolder.setFixedSize], a SurfaceView created before windowPosChanged
 * (empty rect → MATCH_PARENT) allocates an ANW at display size and never
 * rebinds — GDI then paints a letterboxed desktop into a larger buffer.
 */
class WineAndroidDesktop(context: Context) : FrameLayout(context) {
    private val windows = LinkedHashMap<Int, WindowSurface>()

    fun attachWindow(window: WineAndroidWindow, onSurface: (hwnd: Int, surface: Surface?) -> Unit) {
        val existing = windows.remove(window.hwnd)
        existing?.let { removeView(it.view) }
        val view = WindowSurface(context, window, onSurface)
        windows[window.hwnd] = view
        addView(view.view, childParams(window))
        applyFixedSize(view, window)
    }

    fun detachWindow(hwnd: Int) {
        windows.remove(hwnd)?.let { removeView(it.view) }
    }

    fun updateWindow(window: WineAndroidWindow) {
        val held = windows[window.hwnd] ?: return
        held.window = window
        held.view.layoutParams = childParams(window)
        applyFixedSize(held, window)
        held.view.requestLayout()
    }

    private fun childParams(window: WineAndroidWindow): LayoutParams {
        val r = window.windowRect
        val width = if (r.width() > 0) r.width() else LayoutParams.MATCH_PARENT
        val height = if (r.height() > 0) r.height() else LayoutParams.MATCH_PARENT
        return LayoutParams(width, height).apply {
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
                        val surface = holder.surface
                        window.surface = surface
                        onSurface(window.hwnd, surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        // setFixedSize / layout resize: re-notify so HostBridge
                        // re-acquires ANativeWindow and HOST_SURFACE_CHANGED
                        // reports the buffer size Wine actually LOCKs.
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
