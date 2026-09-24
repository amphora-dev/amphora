package app.amphora.core.engine

/**
 * Named guest desktop size presets for wineandroid (`/desktop=shell,WxH`).
 *
 * Changing guest edge length is how we "make chrome bigger/smaller" without
 * touching Wine LogPixels (stay classic 96). Settings / Launcher persist via
 * [preferenceName] / [fromPreference] into [RuntimeSettingsStore].
 */
data class GuestResolution(val id: String, val width: Int, val height: Int, val label: String) {
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
     * SharedPreferences / [RuntimeSettingsStore] value for this preset.
     * House style uses `R{W}x{H}` (same as Settings/Launcher enum names).
     */
    fun preferenceName(preset: GuestResolution): String = when (preset.id) {
        HD_1280x720.id -> "R1280x720"
        XGA_1024x768.id -> "R1024x768"
        HD_PLUS_1600x900.id -> "R1600x900"
        FHD_1920x1080.id -> "R1920x1080"
        else -> preferenceName(DEFAULT)
    }

    /**
     * Resolve a stored preference (enum name or guest id). Unknown / legacy
     * values (e.g. old `R800x600`) fall back to [DEFAULT].
     */
    fun fromPreference(value: String?): GuestResolution = when (value) {
        null, "" -> DEFAULT
        HD_1280x720.id, "R1280x720" -> HD_1280x720
        XGA_1024x768.id, "R1024x768" -> XGA_1024x768
        HD_PLUS_1600x900.id, "R1600x900" -> HD_PLUS_1600x900
        FHD_1920x1080.id, "R1920x1080" -> FHD_1920x1080
        else -> DEFAULT
    }

    /**
     * Resolve optional debug launch overrides against the stored guest size.
     * A non-null override represents an explicit WIDTH / HEIGHT smoke extra.
     */
    fun resolveDebugDimensions(preferenceName: String?, widthOverride: Int?, heightOverride: Int?): Pair<Int, Int> {
        val configured = fromPreference(preferenceName)
        return (widthOverride ?: configured.width) to (heightOverride ?: configured.height)
    }

    /**
     * Label for debug launch logs: where [resolveDebugDimensions] took WxH from.
     * - `extras`: both WIDTH and HEIGHT present
     * - `mixed`: only one of WIDTH / HEIGHT present (other from pref/default)
     * - `pref`: no size extras; SharedPreferences name present
     * - `default`: no size extras and no stored preference
     */
    fun describeDebugDimensionSource(preferenceName: String?, widthOverride: Int?, heightOverride: Int?): String {
        val hasW = widthOverride != null
        val hasH = heightOverride != null
        return when {
            hasW && hasH -> "extras"
            hasW || hasH -> "mixed"
            preferenceName.isNullOrBlank() -> "default"
            else -> "pref"
        }
    }

    /**
     * Pick a preset from the **short** side of the host Activity (px), not DPI.
     * Conservative: stay at 720p until the short side is large enough that a
     * bigger guest still letterboxes with scale ≥ ~1.5.
     */
    fun suggestForHostShortSide(shortSidePx: Int): GuestResolution = when {
        // Keep HA262 (~1904 short) on 720p; only step up on very large panels.
        shortSidePx >= 2560 -> FHD_1920x1080
        shortSidePx >= 2160 -> HD_PLUS_1600x900
        else -> HD_1280x720
    }
}
