package app.amphora.gamesession.wineandroid

import android.content.Context
import android.graphics.PixelFormat
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
 */
class WineAndroidDesktop(context: Context) : FrameLayout(context) {
    private val windows = LinkedHashMap<Int, WindowSurface>()

    fun attachWindow(window: WineAndroidWindow, onSurface: (hwnd: Int, surface: Surface) -> Unit) {
        val existing = windows.remove(window.hwnd)
        existing?.let { removeView(it.view) }
        val view = WindowSurface(context, window, onSurface)
        windows[window.hwnd] = view
        addView(view.view, childParams(window))
    }

    fun detachWindow(hwnd: Int) {
        windows.remove(hwnd)?.let { removeView(it.view) }
    }

    fun updateWindow(window: WineAndroidWindow) {
        val held = windows[window.hwnd] ?: return
        held.window = window
        held.view.layoutParams = childParams(window)
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

    private class WindowSurface(
        context: Context,
        var window: WineAndroidWindow,
        onSurface: (hwnd: Int, surface: Surface) -> Unit,
    ) {
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
                    ) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        window.surface = null
                    }
                },
            )
        }
    }
}
