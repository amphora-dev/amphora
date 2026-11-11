package app.amphora.sdk.settings

/**
 * Collects what settings turn into: environment variables, DLL overrides and
 * host-side configuration. Every entry remembers which setting produced it,
 * for the effective-configuration page.
 */
internal class ConfigBuilder {
    internal val env = LinkedHashMap<String, ConfigEntry>()
    internal val dlls = LinkedHashMap<String, ConfigEntry>()
    internal val args = ArrayList<String>()
    internal val userEnv = ArrayList<Pair<String, String>>()
    internal var resolution: String = "1280x720"
    internal var customResolution: String = ""
    internal var scale: String = "fit"
    internal var dpi: Int = 96
    internal var wineDebugCustom: String = ""
    internal var steamAppId: String = ""

    /** Set by [SettingsResolver] while applying a setting. */
    internal var origin: String = ""

    internal fun env(name: String, value: String) {
        env[name] = ConfigEntry(name, value, origin)
    }

    internal fun dll(name: String, mode: String) {
        dlls[name] = ConfigEntry(name, mode, origin)
    }
}

/** One generated value and the setting (or [ENGINE]) it came from. */
public data class ConfigEntry(
    val name: String,
    val value: String,
    /** A setting id, or [ENGINE] for variables the engine owns. */
    val origin: String,
) {
    val fromEngine: Boolean get() = origin == ENGINE

    public companion object {
        public const val ENGINE: String = "engine"
    }
}
