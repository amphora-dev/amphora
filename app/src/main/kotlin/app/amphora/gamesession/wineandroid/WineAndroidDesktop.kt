package app.amphora.gamesession.wineandroid

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin desktop shaped like upstream [WineActivity] window groups:
 *
 * - Root [FrameLayout] letterboxes an inner [contentHost] sized guest×hostScale.
 * - Each HWND is a [WindowGroup] (FrameLayout) with a match_parent GDI/OpenGL
 *   [SurfaceView] plus nested child WindowGroups.
 * - Layout uses **visibleRect** (parent-relative), not windowRect alone.
 * - [SurfaceHolder.setFixedSize] keeps the buffer at guest px (min 2×2); on
 *   [surfaceChanged] we re-invoke onSurface so native re-registers and sends
 *   SURFACE_CHANGED with the new w/h (upstream TextureView size-changed path).
 * - **Defer first** nativeRegisterSurface until guest rects have a real
 *   positive w×h from WINDOW_POS / create (not the artificial MIN 2×2 alone).
 *   After the first successful register, keep re-binding on size changes.
 */
class WineAndroidDesktop(context: Context) : FrameLayout(context) {
    private data class Key(val hwnd: Int, val client: Boolean)

    private val contentHost =
        FrameLayout(context).also {
            addView(it, LayoutParams(0, 0))
        }

    private val groups = LinkedHashMap<Key, WindowGroup>()

    /** Guest / Wine desktop size (explorer /desktop=shell,WxH). */
    private var guestDesktopWidth: Int = 0
    private var guestDesktopHeight: Int = 0

    /** Uniform scale + letterbox offsets mapping guest px → this view's px. */
    private var hostScale: Float = 1f
    private var offsetX: Int = 0
    private var offsetY: Int = 0

    /** Desktop HWND from createWindow(isDesktop=true); children of it are top-level. */
    private var desktopHwnd: Int = 0

    fun setGuestDesktopSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == guestDesktopWidth && height == guestDesktopHeight) return
        guestDesktopWidth = width
        guestDesktopHeight = height
        recalculateScale("setGuestDesktopSize")
        relayoutAll()
    }

    fun setDesktopHwnd(hwnd: Int) {
        desktopHwnd = hwnd
    }

    fun attachWindow(window: WineAndroidWindow, onSurface: (hwnd: Int, surface: Surface?) -> Unit) {
        val key = Key(window.hwnd, window.isClient)
        groups.remove(key)?.let { detachGroupView(it) }

        if (window.visibleRect.width() <= 0 && window.windowRect.width() > 0) {
            window.visibleRect = Rect(window.windowRect)
        }
        // Non-desktop windows may start at 0×0 until WINDOW_POS; setFixedSize uses min 2×2
        // but first nativeRegisterSurface is deferred until rects have real w×h.

        val group = WindowGroup(context, window, onSurface)
        groups[key] = group
        addGroupToParent(group)
        layoutGroup(group)
        group.applyFixedBufferSize(forceReregister = false)
        applyVisibility(group)
    }

    fun detachWindow(hwnd: Int) {
        listOf(false, true).forEach { client ->
            groups.remove(Key(hwnd, client))?.let { detachGroupView(it) }
        }
    }

    fun updateWindow(window: WineAndroidWindow) {
        val held = groups[Key(window.hwnd, window.isClient)] ?: return
        held.window = window
        // Parent may have changed via copy — reparent if needed.
        ensureParent(held)
        layoutGroup(held)
        held.applyFixedBufferSize(forceReregister = true)
        applyVisibility(held)
        held.requestLayout()
    }

    fun updateHwndRects(
        hwnd: Int,
        windowRect: Rect,
        clientRect: Rect,
        visibleRect: Rect,
        style: Int,
        flags: Int = SWP_NOZORDER,
        insertAfter: Int = 0,
    ) {
        groups.filterKeys { it.hwnd == hwnd }.forEach { (_, held) ->
            held.window.windowRect = Rect(windowRect)
            held.window.clientRect = Rect(clientRect)
            held.window.visibleRect = Rect(visibleRect)
            held.window.style = style
            held.window.visible = (style and WS_VISIBLE) != 0
            layoutGroup(held)
            held.applyFixedBufferSize(forceReregister = true)
            applyVisibility(held)
            if ((flags and SWP_NOZORDER) == 0) {
                applyZOrder(held, insertAfter)
            }
            held.requestLayout()
        }
    }

    fun reparent(hwnd: Int, newParentHwnd: Int) {
        groups.filterKeys { it.hwnd == hwnd }.forEach { (_, held) ->
            held.window.parentHwnd = newParentHwnd
            ensureParent(held)
            layoutGroup(held)
            held.applyFixedBufferSize(forceReregister = false)
            applyVisibility(held)
            held.requestLayout()
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
            contentHost.layoutParams = LayoutParams(0, 0)
            return
        }
        val scale = min(width.toFloat() / gw, height.toFloat() / gh)
        hostScale = scale
        val scaledW = (gw * scale).toInt()
        val scaledH = (gh * scale).toInt()
        offsetX = (width - scaledW) / 2
        offsetY = (height - scaledH) / 2
        contentHost.layoutParams =
            LayoutParams(scaledW, scaledH).apply {
                leftMargin = offsetX
                topMargin = offsetY
            }
        contentHost.requestLayout()
        Log.i(
            TAG,
            "hostScale-to-fill $reason guest=${gw}x${gh} host=${width}x${height} " +
                "scale=$scale offset=${offsetX},${offsetY} content=${scaledW}x${scaledH}",
        )
    }

    private fun relayoutAll() {
        // Top-level first so nested parents have correct size before children.
        val ordered = groups.values.sortedBy { nestingDepth(it.window.parentHwnd) }
        ordered.forEach { held ->
            ensureParent(held)
            layoutGroup(held)
            held.applyFixedBufferSize(forceReregister = false)
            applyVisibility(held)
            held.requestLayout()
        }
    }

    private fun nestingDepth(parentHwnd: Int): Int {
        if (isTopLevel(parentHwnd)) return 0
        var depth = 0
        var p = parentHwnd
        val seen = HashSet<Int>()
        while (!isTopLevel(p) && seen.add(p)) {
            depth++
            val parentGroup = findParentGroup(p) ?: break
            p = parentGroup.window.parentHwnd
        }
        return depth
    }

    private fun isTopLevel(parentHwnd: Int): Boolean {
        return parentHwnd == 0 || parentHwnd == desktopHwnd
    }

    private fun findParentGroup(parentHwnd: Int): WindowGroup? {
        return groups[Key(parentHwnd, false)] ?: groups[Key(parentHwnd, true)]
    }

    private fun addGroupToParent(group: WindowGroup) {
        val parentHwnd = group.window.parentHwnd
        val parentGroup =
            if (isTopLevel(parentHwnd)) {
                null
            } else {
                findParentGroup(parentHwnd)
            }
        val host: FrameLayout = parentGroup ?: contentHost
        if (group.parent !== host) {
            (group.parent as? FrameLayout)?.removeView(group)
            host.addView(group)
        }
    }

    private fun ensureParent(group: WindowGroup) {
        addGroupToParent(group)
    }

    private fun detachGroupView(group: WindowGroup) {
        (group.parent as? FrameLayout)?.removeView(group)
    }

    private fun layoutGroup(group: WindowGroup) {
        val window = group.window
        val isDesktop = window.hwnd == desktopHwnd && desktopHwnd != 0
        if (isDesktop || (isTopLevel(window.parentHwnd) && guestDesktopWidth > 0 &&
                window.visibleRect.width() >= guestDesktopWidth &&
                window.visibleRect.height() >= guestDesktopHeight)
        ) {
            // Desktop hwnd fills contentHost.
            group.layoutParams =
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                    leftMargin = 0
                    topMargin = 0
                }
            return
        }

        val r = layoutRect(window)
        val scale = hostScale
        // Upstream set_layout: never leave empty views (min ~2 guest px).
        val guestW = max(MIN_GUEST_PX, r.width())
        val guestH = max(MIN_GUEST_PX, r.height())
        val width = max(1, (guestW * scale).toInt())
        val height = max(1, (guestH * scale).toInt())
        val left = (r.left * scale).toInt()
        val top = (r.top * scale).toInt()
        group.layoutParams =
            LayoutParams(width, height).apply {
                leftMargin = left
                topMargin = top
            }
    }

    private fun layoutRect(window: WineAndroidWindow): Rect {
        val v = window.visibleRect
        if (v.width() > 0 || v.height() > 0) return v
        return if (window.isClient && window.clientRect.width() > 0 && window.clientRect.height() > 0) {
            window.clientRect
        } else {
            window.windowRect
        }
    }

    /**
     * Upstream WineWindow.pos_changed: when !(flags & SWP_NOZORDER), reorder
     * sibling WindowGroups under the same parent (bringToFront / insert after).
     */
    private fun applyZOrder(group: WindowGroup, insertAfter: Int) {
        if (!group.window.visible) return
        val parent = group.parent as? FrameLayout ?: return
        // HWND_TOP (0) / HWND_TOPMOST (-1) / HWND_NOTOPMOST (-2) → front
        if (insertAfter == 0 || insertAfter == -1 || insertAfter == -2) {
            parent.bringChildToFront(group)
            parent.requestLayout()
            return
        }
        // HWND_BOTTOM (1) → back (index 0, after content surface if any)
        if (insertAfter == 1) {
            parent.removeView(group)
            parent.addView(group, 0)
            parent.requestLayout()
            return
        }
        val after =
            groups.values.firstOrNull {
                it.window.hwnd == insertAfter && it.parent === parent
            }
        if (after == null) {
            parent.bringChildToFront(group)
        } else {
            val idx = parent.indexOfChild(after)
            parent.removeView(group)
            val insertAt = (if (idx >= 0) idx + 1 else parent.childCount).coerceAtMost(parent.childCount)
            parent.addView(group, insertAt)
        }
        parent.requestLayout()
    }

    private fun applyVisibility(group: WindowGroup) {
        group.visibility = if (group.window.visible) View.VISIBLE else View.GONE
    }

    private inner class WindowGroup(
        context: Context,
        var window: WineAndroidWindow,
        private val onSurface: (hwnd: Int, surface: Surface?) -> Unit,
    ) : FrameLayout(context) {
        private var lastBufferW: Int = -1
        private var lastBufferH: Int = -1
        private var surfaceValid: Boolean = false
        /** True after the first non-deferred nativeRegisterSurface for this surface. */
        private var firstRegisterDone: Boolean = false

        val surfaceView =
            SurfaceView(context).apply {
                holder.setFormat(PixelFormat.RGBA_8888)
                if (window.isClient) {
                    setZOrderMediaOverlay(true)
                }
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val surface = holder.surface
                            window.surface = surface
                            surfaceValid = true
                            // Defer first register if guest size is still placeholder.
                            tryEmitSurface(surface, "surfaceCreated")
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            // Upstream WineView.onSurfaceTextureSizeChanged re-binds;
                            // Amphora must re-nativeRegisterSurface so native sends
                            // SURFACE_CHANGED with the new buffer size.
                            val surface = holder.surface
                            if (surface == null || !surface.isValid) return
                            window.surface = surface
                            surfaceValid = true
                            Log.i(
                                TAG,
                                "surfaceChanged hwnd=${window.hwnd} buffer=${width}x$height " +
                                    "client=${window.isClient} firstDone=$firstRegisterDone",
                            )
                            tryEmitSurface(surface, "surfaceChanged")
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surfaceValid = false
                            firstRegisterDone = false
                            window.surface = null
                            onSurface(window.hwnd, null)
                        }
                    },
                )
            }

        init {
            addView(
                surfaceView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }

        fun applyFixedBufferSize(forceReregister: Boolean) {
            val r = bufferRect(window)
            val realW = r.width()
            val realH = r.height()
            val bw = max(MIN_GUEST_PX, if (realW > 0) realW else MIN_GUEST_PX)
            val bh = max(MIN_GUEST_PX, if (realH > 0) realH else MIN_GUEST_PX)
            val changed = bw != lastBufferW || bh != lastBufferH
            if (changed || lastBufferW < 0) {
                // Keep ANativeWindow / Wine buffer at guest px; view layout is host-scaled.
                surfaceView.holder.setFixedSize(bw, bh)
                Log.i(
                    TAG,
                    "setFixedSize hwnd=${window.hwnd} ${bw}x$bh " +
                        "(was ${lastBufferW}x$lastBufferH) real=${realW}x$realH " +
                        "visible=${window.visibleRect} window=${window.windowRect}",
                )
                lastBufferW = bw
                lastBufferH = bh
            }
            if (!surfaceValid) return
            val surface = window.surface
            if (surface == null || !surface.isValid) return
            // First register once real guest dims are known (even if forceReregister
            // is false — e.g. attach after sibling copy / desktop create size).
            // After that, keep the existing re-bind on setFixedSize changes.
            if (!firstRegisterDone) {
                tryEmitSurface(surface, "applyFixedBufferSize")
            } else if (changed && forceReregister) {
                // setFixedSize may not always deliver surfaceChanged immediately;
                // ensure native gets SURFACE_CHANGED with the new size.
                surfaceView.requestLayout()
                tryEmitSurface(surface, "applyFixedBufferSize-reregister")
            }
        }

        /**
         * Guest buffer size from WINDOW_POS / create rects — **before** MIN 2×2 clamp.
         * Placeholder 0/0 (or one zero edge) must not count as real.
         */
        private fun hasRealGuestSize(): Boolean {
            val r = bufferRect(window)
            return r.width() > 0 && r.height() > 0
        }

        /**
         * Gate [onSurface] → nativeRegisterSurface:
         * - first call waits for [hasRealGuestSize]; artificial MIN 2×2 alone is not enough.
         * - after first success, always re-bind (surfaceChanged / setFixedSize).
         */
        private fun tryEmitSurface(surface: Surface, reason: String) {
            if (!surface.isValid) return
            if (!firstRegisterDone) {
                if (!hasRealGuestSize()) {
                    val r = bufferRect(window)
                    Log.i(
                        TAG,
                        "defer first register hwnd=${window.hwnd} reason=$reason " +
                            "rect=${r.width()}x${r.height()} (placeholder / awaiting WINDOW_POS)",
                    )
                    return
                }
                firstRegisterDone = true
                val r = bufferRect(window)
                Log.i(
                    TAG,
                    "first register hwnd=${window.hwnd} reason=$reason " +
                        "guest=${r.width()}x${r.height()}",
                )
                onSurface(window.hwnd, surface)
                return
            }
            onSurface(window.hwnd, surface)
        }

        private fun bufferRect(window: WineAndroidWindow): Rect {
            val v = window.visibleRect
            if (v.width() > 0 && v.height() > 0) return v
            return if (window.isClient && window.clientRect.width() > 0 && window.clientRect.height() > 0) {
                window.clientRect
            } else {
                window.windowRect
            }
        }
    }

    private companion object {
        const val TAG = "WineAndroidDesktop"
        const val WS_VISIBLE = 0x10000000
        const val SWP_NOZORDER = 0x04
        const val MIN_GUEST_PX = 2
    }
}
