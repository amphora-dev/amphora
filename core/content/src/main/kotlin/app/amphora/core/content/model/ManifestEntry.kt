package app.amphora.core.content.model

/**
 * One entry in [app.amphora.core.content.ContentManifest]: describes how a
 * single bundled content artifact is provisioned from APK `assets/`.
 *
 * @property component The logical [ContentComponent] this entry resolves.
 * @property assetPath Path inside the APK `assets/` dir (e.g.
 *   `graphics_driver/wrapper.tzst` or `Proton-10.0-4-x86_64.wcp`).
 * @property sha256 SHA-256 content identity. It verifies the downloaded bytes
 *   and is recorded in every installed directory, so changing it replaces a
 *   same-version install and refreshes derived runtime state.
 * @property version Display/compatibility version encoded into the resolved
 *   path. For [Kind.WCP] this is the ContentsManager entry name
 *   (`type-verName-verCode`) and **must** match the embedded `profile.json`.
 *   WCP sibling versions remain available for container rollback.
 * @property kind How the asset is provisioned (see [Kind]).
 * @property compression Archive compression for [Kind.ARCHIVE] (default ZSTD).
 * @property contentType / verName / verCode [Kind.WCP]-only: reconstruct a
 *   minimal `ContentProfile` so `ContentsManager.getInstallDir` can be computed
 *   without a `syncContents()` round-trip (cache check only; the real profile is
 *   read from the `.wcp`'s `profile.json` during install).
 * @property remoteUrl Optional pinned download URL. WCP entries may omit this
 *   when their URL is discoverable from the manifest's stable upstream catalog.
 * @property size Expected compressed size in bytes, used to reject truncated
 *   downloads before installation.
 */
data class ManifestEntry(
    val component: ContentComponent,
    val assetPath: String,
    val sha256: String?,
    val version: String,
    val kind: Kind,
    val compression: Compression = Compression.ZSTD,
    val contentType: String? = null,
    val verName: String? = null,
    val verCode: Int? = null,
    val remoteUrl: String? = null,
    val size: Long? = null,
) {
    /** How a [ManifestEntry] is provisioned from its bundled asset. */
    enum class Kind {
        /** tar archive extracted to `filesDir/amphora-content/<component>/<version>/`. */
        ARCHIVE,

        /** Winlator Component Package (`.wcp`) installed via `ContentsManager.extraContentFile`. */
        WCP,

        /** imagefs rootfs; owned by `RootfsInstaller`, NOT resolved by a `ContentSource`. */
        ROOTFS,
    }

    /** Archive compression (ARCHIVE kind only). */
    enum class Compression { ZSTD, XZ }
}
