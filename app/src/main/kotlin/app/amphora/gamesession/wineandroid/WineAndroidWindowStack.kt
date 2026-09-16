package app.amphora.gamesession.wineandroid

/**
 * Pure Win32-ish sibling z-order + WS_VISIBLE helpers for nested WindowGroups.
 *
 * Mirrors upstream WineActivity.WineWindow set_zorder / sync_views_zorder math
 * without Android View types so unit tests can lock stacking order.
 *
 * Sibling lists are **top-first**: index 0 is HWND_TOP (front). That matches
 * Wine's children ArrayList after set_zorder(null) → add(0), then
 * sync_views_zorder bringToFront from back → front.
 */
object WineAndroidWindowStack {
    const val WS_VISIBLE = 0x10000000
    const val SWP_NOZORDER = 0x04

    /** Places the window at the top of the Z order. */
    const val HWND_TOP = 0

    /** Places the window at the bottom of the Z order. */
    const val HWND_BOTTOM = 1

    /** Places the window above all non-topmost windows. */
    const val HWND_TOPMOST = -1

    /** Places the window above all non-topmost windows (clears topmost). */
    const val HWND_NOTOPMOST = -2

    fun isStyleVisible(style: Int): Boolean = (style and WS_VISIBLE) != 0

    fun wantsZOrder(flags: Int): Boolean = (flags and SWP_NOZORDER) == 0

    /**
     * Reorder [siblings] (top-first) so [hwnd] sits relative to [insertAfter].
     * Unknown insertAfter hwnd → treat as HWND_TOP.
     */
    fun reorder(siblings: List<Int>, hwnd: Int, insertAfter: Int): List<Int> {
        val without = siblings.filter { it != hwnd }
        return when (insertAfter) {
            HWND_TOP, HWND_TOPMOST, HWND_NOTOPMOST -> listOf(hwnd) + without
            HWND_BOTTOM -> without + hwnd
            else -> {
                val idx = without.indexOf(insertAfter)
                if (idx < 0) {
                    listOf(hwnd) + without
                } else {
                    without.take(idx + 1) + hwnd + without.drop(idx + 1)
                }
            }
        }
    }

    /**
     * Ensure [hwnd] is tracked under [siblings] (append at bottom if new).
     */
    fun ensureTracked(siblings: List<Int>, hwnd: Int): List<Int> =
        if (siblings.contains(hwnd)) siblings else siblings + hwnd

    fun remove(siblings: List<Int>, hwnd: Int): List<Int> = siblings.filter { it != hwnd }

    /**
     * Upstream sync_views_zorder: bringToFront visible children from bottom → top
     * so the last bringToFront is the frontmost (siblings[0]).
     *
     * Returns hwnds in the order they should receive bringChildToFront.
     */
    fun syncBringToFrontOrder(siblingsTopFirst: List<Int>, visible: (Int) -> Boolean): List<Int> =
        siblingsTopFirst.asReversed().filter(visible)
}
