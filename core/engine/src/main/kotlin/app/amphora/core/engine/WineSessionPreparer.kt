package app.amphora.core.engine

import app.amphora.core.container.model.Container
import app.amphora.core.engine.model.LaunchSpec

/**
 * Wine session preparation (RFC §7 / D9): the ~800-1000 line "core launch"
 * extracted out of WinNative's 10,995-line `XServerDisplayActivity` into
 * `:core:engine`. Steam / recording / shortcut branches are stripped (RFC §7 /
 * D9); Amphora passes only exe + env and never rewrites `getWineStartCommand`
 * (D9).
 *
 * Method names mirror the XSDA private methods verbatim (with the XSDA line
 * numbers in each KDoc) so the P2 body extraction is a mechanical copy with
 * branch removal, not a redesign. Each method operates on a [Container]
 * (`rootPath` + `winePrefixPath`) whose rootfs must already be installed
 * (`RootfsInstaller`, P2). The real concretion is
 * [XServerWineSessionPreparer] (P2 body extraction; the
 * [StubWineSessionPreparer] scaffold was removed, mirroring the P2
 * [app.amphora.core.engine.ImageFsRootfsInstaller] graduation).
 *
 * MVP prep chain (XSDA `setupWineSystemFiles`, L6127):
 * ```
 * setupWineSystemFiles            // orchestrator
 *   -> ensureWinePrefixReady      // L7127 - create / validate WINEPREFIX
 *   -> ensureLaunchRuntimeFilesReady  // L6280 - Box64Runtime.ensureApplied + ALSA layout
 *   -> ensureWinePrefixEssentialFiles // L7164 - system.reg / user.reg / dosdevices / ...
 *   -> extractDXWrapperFiles      // L7970 - DXVK / d8vk into prefix (extractD8VKIfNeeded L8098)
 * extractGraphicsDriverFiles      // L7537 - Turnip (D8: single pinned driver for MVP) + env vars
 * ```
 *
 * [envVars] exposes the wrapper/GPU environment variables accumulated during
 * prep (`GALLIUM_DRIVER` / `VK_ICD_FILENAMES` / `WRAPPER_*` / `DXVK_*` ...);
 * `WineEngineImpl` merges them with the caller's [LaunchSpec.env] for the
 * `box64 wine explorer` launch (P3). It mirrors XSDA's mutable `envVars` field.
 *
 * Status: **P2 body extracted (compile-only)** in [XServerWineSessionPreparer].
 * End-to-end verification waits on rootfs/driver assets (P2 asset acquisition).
 */
interface WineSessionPreparer {
    /**
     * The wrapper/GPU env vars computed during prep (XSDA `envVars` field).
     * Read by `WineEngineImpl` after [setupWineSystemFiles] +
     * [extractGraphicsDriverFiles] to build the guest-program environment.
     * Additive output accessor (introduced with the P2 body extraction).
     */
    fun envVars(): Map<String, String>

    /** Top-level orchestrator (XSDA `setupWineSystemFiles`, L6127). P2 body extracted (compile-only). */
    suspend fun setupWineSystemFiles(spec: LaunchSpec, container: Container)

    /** Create / validate the WINEPREFIX for [container] (XSDA `ensureWinePrefixReady`, L7127). */
    suspend fun ensureWinePrefixReady(container: Container)

    /** Ensure box64 + runtime dlls exist (XSDA `ensureLaunchRuntimeFilesReady`, L6280). */
    suspend fun ensureLaunchRuntimeFilesReady(container: Container)

    /** Write system.reg / user.reg / dosdevices etc. (XSDA `ensureWinePrefixEssentialFiles`, L7164). */
    suspend fun ensureWinePrefixEssentialFiles(container: Container)

    /** Extract DXVK / d8vk into the prefix (XSDA `extractDXWrapperFiles(String)`, L7970). */
    suspend fun extractDXWrapperFiles(container: Container, dxwrapper: String)

    /** Extract the graphics driver (Turnip) into the prefix (XSDA `extractGraphicsDriverFiles`, L7537). */
    suspend fun extractGraphicsDriverFiles(container: Container)
}
