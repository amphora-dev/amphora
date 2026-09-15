package app.amphora.gamesession.wineandroid

/**
 * Wine LogPixels for wineandroid virtual desktops.
 *
 * Host scale-to-fill already enlarges the 1280x720 canvas onto the tablet
 * (~2.375× on HA262). Putting Winlator ScreenInfo 254 into Wine *and* scaling
 * double-counts (~254×2.375≈600 on-screen). Classic Wine **96** keeps chrome
 * small in guest pixels; host scale brings on-screen effective ≈96×2.375≈228,
 * near Winlator's advertised 254 without stacking.
 *
 * Never pass Android densityDpi (440) into a 720p virtual desktop.
 */
object WineAndroidDpi {
    const val CLASSIC_WINE_DPI = 96
    const val WINLATOR_SCREENINFO_DPI = 254

    fun forVirtualDesktopWithHostScale(): Int = CLASSIC_WINE_DPI
}
