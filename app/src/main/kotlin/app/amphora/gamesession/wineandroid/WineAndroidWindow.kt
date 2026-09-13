package app.amphora.gamesession.wineandroid

import android.graphics.Rect
import android.view.Surface

/**
 * One Win32 HWND as Amphora sees it. Not a Winlator/X11 window.
 * System freeform is the [WineAndroidSessionActivity], not each HWND.
 */
data class WineAndroidWindow(
    val hwnd: Int,
    val parentHwnd: Int,
    val isClient: Boolean,
    val scale: Float,
    var windowRect: Rect = Rect(),
    var clientRect: Rect = Rect(),
    var surface: Surface? = null,
)
