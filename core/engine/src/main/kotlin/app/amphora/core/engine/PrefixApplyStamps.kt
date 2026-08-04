package app.amphora.core.engine

import com.winlator.cmod.runtime.container.Container

/**
 * Apply-stamp keys stored in `.container` `extraData`.
 *
 * Three layers (do not confuse them):
 * - **Desired** — first-class container fields (`audioDriver`, `dxwrapper`, `wincomponents`, …).
 * - **Applied** — what Wine/prefix actually has (`user.reg` / `system.reg` / extracted DLLs /
 *   `usr/bin/box64`).
 * - **Stamp** (`extra.*`) — optional cache of “last successful heavy apply”, used only to skip
 *   expensive work (DLL extract, box64 copy). Never a substitute for reading a cheap applied sink.
 *
 * Registry-backed policies whose sink is a few keys (e.g. Wine `Drivers\Audio`) must **ensure
 * against the registry**, not against a stamp. Heavy extract policies keep stamps and must clear
 * them when the prefix is repaired so the next launch re-applies.
 */
object PrefixApplyStamps {

    /**
     * Legacy WinNative/Amphora stamp that used to gate [com.winlator.cmod.runtime.wine.WineUtils.changeWineAudioDriver].
     * Desired policy is top-level `audioDriver`; applied sink is `user.reg`. Drop on sight.
     */
    const val OBSOLETE_AUDIO_DRIVER = "audioDriver"

    /** Stamps that become meaningless after the wine prefix is recreated/repaired. */
    val prefixRepairClearKeys: List<String> =
        listOf(
            "wineprefixNeedsUpdate",
            "appVersion",
            "imgVersion",
            "dxwrapper",
            "wincomponents",
            "desktopTheme",
            "startupSelection",
            "mono_installed",
            "mono_version",
            OBSOLETE_AUDIO_DRIVER,
        )

    /** Remove obsolete stamps; returns true if any key was present. */
    fun stripObsolete(container: Container): Boolean {
        if (!container.hasExtra(OBSOLETE_AUDIO_DRIVER)) return false
        container.putExtra(OBSOLETE_AUDIO_DRIVER, null)
        return true
    }

    /**
     * Clear prefix-local apply stamps after
     * [com.winlator.cmod.runtime.container.ContainerManager.repairContainerWinePrefix].
     * Does not touch content pins (`box64Version`) — those live outside the wine prefix.
     */
    fun clearForPrefixRepair(container: Container) {
        for (key in prefixRepairClearKeys) {
            container.putExtra(key, null)
        }
    }
}
