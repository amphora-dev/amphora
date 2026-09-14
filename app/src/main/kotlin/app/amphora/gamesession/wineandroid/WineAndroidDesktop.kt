package app.amphora.gamesession.wineandroid

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

/**
 * Kotlin desktop: one [FrameLayout], per-(HWND, client) [SurfaceView] children.
 *
 * GDI uses isClient=false (parent ANW). Vulkan smoke uses isClient=true so
 * vkCreateAndroidSurfaceKHR has its own Surface/fd, not the GDI parent.
 */
class WineAndroidDesktop(context: Context) : FrameLayout(context) {
    private data class Key(val hwnd: Int, val client: Boolean)

    private val windows = LinkedHashMap<Key, WindowSurface>()

    fun attachWindow(window: WineAndroidWindow, onSurface: (hwnd: Int, surface: Surface?) -> Unit) {
        val key = Key(window.hwnd, window.isClient)
        windows.remove(key)?.let { removeView(it.view) }
        val view = WindowSurface(context, window, onSurface)
        windows[key] = view
        addView(view.view, childParams(window))
    }

    fun detachWindow(hwnd: Int) {
        listOf(false, true).forEach { client ->
            windows.remove(Key(hwnd, client))?.let { removeView(it.view) }
        }
    }

    fun updateWindow(window: WineAndroidWindow) {
        val held = windows[Key(window.hwnd, window.isClient)] ?: return
        held.window = window
        held.view.layoutParams = childParams(window)
        held.view.requestLayout()
    }

    fun updateHwndRects(hwnd: Int, windowRect: Rect, clientRect: Rect) {
        windows.filterKeys { it.hwnd == hwnd }.forEach { (_, held) ->
            held.window.windowRect = Rect(windowRect)
            held.window.clientRect = Rect(clientRect)
            held.view.layoutParams = childParams(held.window)
            held.view.requestLayout()
        }
    }

    private fun childParams(window: WineAndroidWindow): LayoutParams {
        val r =
            if (window.isClient && window.clientRect.width() > 0 && window.clientRect.height() > 0) {
                window.clientRect
            } else {
                window.windowRect
            }
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
        onSurface: (hwnd: Int, surface: Surface?) -> Unit,
    ) {
        val view = SurfaceView(context).apply {
            holder.setFormat(PixelFormat.RGBA_8888)
            if (window.isClient) {
                setZOrderMediaOverlay(true)
            }
            holder.addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val surface = holder.surface
                        window.surface = surface
                        onSurface(window.hwnd, surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        window.surface = null
                        onSurface(window.hwnd, null)
                    }
                },
            )
        }
    }
}
