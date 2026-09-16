package app.amphora.gamesession.wineandroid

/**
 * Named guest desktop size presets for wineandroid (/desktop=shell,WxH).
 *
 * Changing guest edge length is how we "make chrome bigger/smaller" without
 * touching Wine LogPixels (stay classic 96). Product UI can later expose these;
 * this object is the stable catalog + short-side heuristic.
 */
data class GuestResolution(
    val id: String,
    val width: Int,
    val height: Int,
    val label: String,
) {
    init {
        require(width > 0 && height > 0)
    }
}

object WineAndroidGuestResolution {
    val HD_1280x720 =
        GuestResolution("hd_720p", 1280, 720, "1280×720 (default)")
    val XGA_1024x768 =
        GuestResolution("xga", 1024, 768, "1024×768")
    val HD_PLUS_1600x900 =
        GuestResolution("hd_plus", 1600, 900, "1600×900")
    val FHD_1920x1080 =
        GuestResolution("fhd", 1920, 1080, "1920×1080")

    val DEFAULT: GuestResolution = HD_1280x720

    val ALL: List<GuestResolution> =
        listOf(HD_1280x720, XGA_1024x768, HD_PLUS_1600x900, FHD_1920x1080)

    fun byId(id: String): GuestResolution? = ALL.firstOrNull { it.id == id }

    /**
     * Pick a preset from the **short** side of the host Activity (px), not DPI.
     * Conservative: stay at 720p until the short side is large enough that a
     * bigger guest still letterboxes with scale ≥ ~1.5.
     */
    fun suggestForHostShortSide(shortSidePx: Int): GuestResolution =
        when {
            // Keep HA262 (~1904 short) on 720p; only step up on very large panels.
            shortSidePx >= 2560 -> FHD_1920x1080
            shortSidePx >= 2160 -> HD_PLUS_1600x900
            else -> HD_1280x720
        }
}
