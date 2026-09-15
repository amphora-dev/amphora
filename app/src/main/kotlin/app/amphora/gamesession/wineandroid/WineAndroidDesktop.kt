package app.amphora.gamesession.wineandroid

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin desktop: one [FrameLayout], per-(HWND, client) [SurfaceView] children.
 *
 * GDI uses isClient=false (parent ANW). Vulkan smoke uses isClient=true so
 * vkCreateAndroidSurfaceKHR has its own Surface/fd, not the GDI parent.
 *
 * Host scale-to-fill: guest HWND rects stay in Wine desktop pixels (e.g. 1280x720);
 * views are laid out scaled to fill this FrameLayout while preserving aspect
 * (letterbox OK). [SurfaceHolder.setFixedSize] keeps the buffer at guest size so
 * ANativeWindow dimensions match Wine, and Android scales the buffer to the view.
 *
 * Never size an empty (not-yet-positioned) HWND to the full desktop — that
 * registered a full-screen ANW and stretched thin Wine paints (taskbar glitch).
 */
class WineAndroidDesktop(context: Context) : FrameLayout(context) {
    private data class Key(val hwnd: Int, val client: Boolean)

    private val windows = LinkedHashMap<Key, WindowSurface>()

    /** Guest / Wine desktop size (explorer /desktop=shell,WxH). */
    private var guestDesktopWidth: Int = 0
    private var guestDesktopHeight: Int = 0

    /** Uniform scale + letterbox offsets mapping guest px → this view's px. */
    private var hostScale: Float = 1f
    private var offsetX: Int = 0
    private var offsetY: Int = 0

    fun setGuestDesktopSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == guestDesktopWidth && height == guestDesktopHeight) return
        guestDesktopWidth = width
        guestDesktopHeight = height
        recalculateScale("setGuestDesktopSize")
        relayoutAll()
    }

    fun attachWindow(window: WineAndroidWindow, onSurface: (hwnd: Int, surface: Surface?) -> Unit) {
        val key = Key(window.hwnd, window.isClient)
        windows.remove(key)?.let { removeView(it.view) }
        val view = WindowSurface(context, window, onSurface)
        windows[key] = view
        addView(view.view, childParams(window))
        view.applyFixedBufferSize()
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
        held.applyFixedBufferSize()
        held.view.requestLayout()
    }

    fun updateHwndRects(hwnd: Int, windowRect: Rect, clientRect: Rect) {
        windows.filterKeys { it.hwnd == hwnd }.forEach { (_, held) ->
            held.window.windowRect = Rect(windowRect)
            held.window.clientRect = Rect(clientRect)
            held.view.layoutParams = childParams(held.window)
            held.applyFixedBufferSize()
            held.view.requestLayout()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        recalculateScale("onSizeChanged ${w}x${h}")
        relayoutAll()
    }

    private fun recalculateScale(reason: String) {
        val gw = guestDesktopWidth
        val gh = guestDesktopHeight
        if (gw <= 0 || gh <= 0 || width <= 0 || height <= 0) {
            hostScale = 1f
            offsetX = 0
            offsetY = 0
            return
        }
        val scale = min(width.toFloat() / gw, height.toFloat() / gh)
        hostScale = scale
        val scaledW = (gw * scale).toInt()
        val scaledH = (gh * scale).toInt()
        offsetX = (width - scaledW) / 2
        offsetY = (height - scaledH) / 2
        Log.i(
            TAG,
            "hostScale-to-fill $reason guest=${gw}x${gh} host=${width}x${height} " +
                "scale=$scale offset=${offsetX},${offsetY} content=${scaledW}x${scaledH}",
        )
    }

    private fun relayoutAll() {
        windows.values.forEach { held ->
            held.view.layoutParams = childParams(held.window)
            held.applyFixedBufferSize()
            held.view.requestLayout()
        }
    }

    private fun guestRect(window: WineAndroidWindow): Rect {
        return if (window.isClient && window.clientRect.width() > 0 && window.clientRect.height() > 0) {
            window.clientRect
        } else {
            window.windowRect
        }
    }

    private fun childParams(window: WineAndroidWindow): LayoutParams {
        val r = guestRect(window)
        // Empty rect must NOT fall back to full desktop size — that made child
        // HWNDs register a 3040x1710 ANW, then Wine painted a thin strip and
        // Android stretched it (taskbar/title distortion on HA262).
        val gw = if (r.width() > 0) r.width() else 1
        val gh = if (r.height() > 0) r.height() else 1
        val scale = hostScale
        val width = max(1, (gw * scale).toInt())
        val height = max(1, (gh * scale).toInt())
        val left = offsetX + (r.left * scale).toInt()
        val top = offsetY + (r.top * scale).toInt()
        return LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
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

        fun applyFixedBufferSize() {
            val r =
                if (window.isClient && window.clientRect.width() > 0 && window.clientRect.height() > 0) {
                    window.clientRect
                } else {
                    window.windowRect
                }
            // Always pin buffer to guest px (1x1 until first real WINDOW_POS).
            // Leaving unset lets SurfaceView use the view's host-scaled size as ANW,
            // which with the old desktop fallback became a full-screen buffer.
            val bw = max(1, r.width())
            val bh = max(1, r.height())
            view.holder.setFixedSize(bw, bh)
        }
    }

    private companion object {
        const val TAG = "WineAndroidDesktop"
    }
}
