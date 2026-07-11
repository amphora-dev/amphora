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
 * numbers in each KDoc) so the P2/P3 body extraction is a mechanical copy with
 * branch removal, not a redesign. Each method operates on a [Container]
 * (`rootPath` + `winePrefixPath`) whose rootfs must already be installed
 * (`RootfsInstaller`, P2).
 *
 * MVP prep chain (XSDA `setupWineSystemFiles`, L6127):
 * ```
 * setupWineSystemFiles            // orchestrator
 *   -> ensureWinePrefixReady      // L7127 - create / validate WINEPREFIX
 *   -> ensureLaunchRuntimeFilesReady  // L6280 - box64 + runtime dlls (ensureBox64RuntimeReady L6290)
 *   -> ensureWinePrefixEssentialFiles // L7164 - system.reg / user.reg / dosdevices / ...
 *   -> extractDXWrapperFiles      // L7970 - DXVK / d8vk into prefix (extractD8VKIfNeeded L8098)
 *   -> extractGraphicsDriverFiles // L7537 - Turnip (D8: single pinned driver for MVP)
 * ```
 *
 * Status: interface only (P1). Body extraction deferred to P2/P3 - see
 * [StubWineSessionPreparer] and `docs/03-TRACKING.md`.
 */
interface WineSessionPreparer {
    /** Top-level orchestrator (XSDA `setupWineSystemFiles`, L6127). P2/P3 body pending. */
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
