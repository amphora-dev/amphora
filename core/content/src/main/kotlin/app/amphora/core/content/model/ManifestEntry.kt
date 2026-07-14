package app.amphora.core.content.model

/**
 * One entry in [app.amphora.core.content.ContentManifest]: describes how a
 * single bundled content artifact is provisioned from APK `assets/`.
 *
 * @property component The logical [ContentComponent] this entry resolves.
 * @property assetPath Path inside the APK `assets/` dir (e.g.
 *   `graphics_driver/wrapper.tzst` or `Proton-10.0-4-x86_64.wcp`).
 * @property sha256 Pinned SHA-256 of the asset file, or `null` when the digest
 *   is not yet locked (verification is skipped with a log). All shipped entries
 *   are pinned as of 2026-07-14 (gap #1); see `docs/04-ASSET-MANIFEST.md` §4-5.
 * @property version Version string; encoded into the resolved path so a manifest
 *   bump naturally provisions a fresh copy (the stale dir is orphaned, not
 *   mutated). For [Kind.WCP] this is the ContentsManager entry name
 *   (`type-verName-verCode`).
 * @property kind How the asset is provisioned (see [Kind]).
 * @property compression Archive compression for [Kind.ARCHIVE] (default ZSTD).
 * @property contentType / verName / verCode [Kind.WCP]-only: reconstruct a
 *   minimal `ContentProfile` so `ContentsManager.getInstallDir` can be computed
 *   without a `syncContents()` round-trip (cache check only; the real profile is
 *   read from the `.wcp`'s `profile.json` during install).
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
) {
    /** How a [ManifestEntry] is provisioned from its bundled asset. */
    enum class Kind {
        /** tar archive extracted to `filesDir/amphora-content/<component>/<version>/`. */
        ARCHIVE,

        /** Winlator Component Package (`.wcp`) installed via `ContentsManager.extraContentFile`. */
        WCP,

        /** imagefs rootfs; owned by `RootfsInstaller`, NOT resolved by `BundledContentSource`. */
        ROOTFS,
    }

    /** Archive compression (ARCHIVE kind only). */
    enum class Compression { ZSTD, XZ }
}
