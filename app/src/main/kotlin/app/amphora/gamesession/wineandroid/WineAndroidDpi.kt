package app.amphora.gamesession.wineandroid

/**
 * Winlator / Amphora X11 [com.winlator.cmod.runtime.display.xserver.ScreenInfo]
 * reports physical size as guest_pixels / 10 millimeters. That yields a constant
 * ~254 DPI for any virtual desktop size:
 *
 *   dpi = width_px * 25.4 / (width_px / 10) = 254
 *
 * Use this for wineandroid LogPixels when the guest desktop is a *virtual*
 * resolution (e.g. 1280x720) that the host then letterbox-scales. Do **not**
 * pass Android [android.util.DisplayMetrics.densityDpi] in that pairing — on
 * HA262 that was 440 on a 720p canvas and made chrome huge.
 */
object WineAndroidDpi {
    /** Same as ScreenInfo mm = px/10 → ~254 for any positive size. */
    fun fromGuestDesktop(widthPx: Int, heightPx: Int = widthPx): Int {
        require(widthPx > 0) { "guest width must be positive" }
        val widthMm = widthPx / 10.0
        return (widthPx * 25.4 / widthMm).toInt()
    }
}
