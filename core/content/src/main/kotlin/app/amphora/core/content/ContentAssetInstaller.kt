package app.amphora.core.content

import app.amphora.core.content.model.ManifestEntry
import java.io.File

/**
 * Kernel-dependent half of [ContentSource]: given a SHA-verified archive file
 * (already staged by [RemoteContentSource]), extract/install it to the canonical
 * content location and return the resolved path.
 *
 * **Why this is an abstraction in `:core:content` (not a concrete class):** the
 * real impl (`WinlatorContentAssetInstaller` in `:core:engine`) needs the ported
 * `com.winlator.cmod` kernel -- `TarCompressorUtils` (ARCHIVE) and
 * `ContentsManager` (WCP) -- which `:core:content` cannot depend on (RFC §6 dep
 * graph: `engine -> content`). This is the same DIP pattern used by
 * [app.amphora.core.rootfs.RootfsInstaller] / [app.amphora.core.engine.WineSessionPreparer]:
 * contract in the low module, concretion in `:core:engine` next to the kernel.
 */
interface ContentAssetInstaller {
    /**
     * Where [entry] resolves to on the filesystem, *without* installing. Used for
     * the cache-hit check.
     */
    fun resolvedPath(entry: ManifestEntry): File

    /** True if [entry] is already installed at [resolvedPath] (cache hit). */
    fun isInstalled(entry: ManifestEntry): Boolean

    /**
     * Extract/install the already-verified [archiveFile] for [entry]; return the
     * resolved path. Idempotent: if already installed, return [resolvedPath].
     */
    suspend fun install(entry: ManifestEntry, archiveFile: File): File
}
