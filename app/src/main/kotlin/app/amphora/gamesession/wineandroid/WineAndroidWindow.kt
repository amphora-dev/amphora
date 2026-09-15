package app.amphora.gamesession.wineandroid

import android.graphics.Rect
import android.view.Surface

/**
 * One Win32 HWND as Amphora sees it. Not a Winlator/X11 window.
 * System freeform is the [WineAndroidSessionActivity], not each HWND.
 *
 * Rects match upstream wineandroid: [windowRect]/[clientRect]/[visibleRect]
 * (visible is parent-relative client-area coords from win32u window.c).
 */
data class WineAndroidWindow(
    val hwnd: Int,
    var parentHwnd: Int,
    val isClient: Boolean,
    val scale: Float,
    var windowRect: Rect = Rect(),
    var clientRect: Rect = Rect(),
    var visibleRect: Rect = Rect(),
    var style: Int = 0,
    var visible: Boolean = true,
    var surface: Surface? = null,
)
