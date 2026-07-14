package app.amphora.core.content

import android.content.Context
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.model.ComponentId
import app.amphora.core.content.model.ContentArtifact
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.model.id
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * MVP [ContentSource]: serves version-locked artifacts bundled in the APK
 * `assets/`. `resolve(component)` looks up [ContentManifest], SHA-256-verifies
 * the asset (when a digest is pinned), and delegates extraction/install to
 * [BundledAssetInstaller] -- [ManifestEntry.Kind.ARCHIVE] (tar -> dir) or
 * [ManifestEntry.Kind.WCP] (`.wcp` -> `ContentsManager.extraContentFile`,
 * bypassing the D4 `nativeDownloadFile` stub).
 *
 * [ManifestEntry.Kind.ROOTFS] is deliberately NOT resolved here --
 * [app.amphora.core.rootfs.RootfsInstaller] owns imagefs extraction (shard
 * support, `home/` preservation, version marker); `resolve(ROOTFS)` throws with
 * a pointer to it.
 *
 * The manifest (`content_manifest.json`) is generated from
 * `docs/04-ASSET-MANIFEST.md`; all component SHAs are pinned (locked
 * 2026-07-14, gap #1; a `null` digest still skips verification with a warning). The version string is
 * encoded into the resolved path, so a manifest bump provisions a fresh copy
 * (the stale dir is orphaned, not mutated).
 *
 * **Cache:** a cache hit is `[BundledAssetInstaller.isInstalled]` at
 * `[BundledAssetInstaller.resolvedPath]`. On miss, the asset is streamed to a
 * temp file (tee'd through SHA-256), verified, handed to the installer, and the
 * temp file is deleted.
 *
 * **Consumer wiring:** the WCP path (WINE / BOX64) replaces the test's host
 * `curl` + `adb push` + `extraContentFile` workaround for production -- when the
 * `.wcp` files are bundled in APK assets, `resolve(WINE)` / `resolve(BOX64)`
 * installs them locally with no remote download. The ARCHIVE path
 * (TURNIP / DXVK / AUDIO_PLUGIN) is a SHA-verified provisioning capability; the
 * ported kernel (`WineSessionPreparer.extractGraphicsDriverFiles`,
 * `ImageFsRootfsInstaller`) still reads those assets from `context.assets`
 * directly today, and will be migrated to consume `ContentSource` in P3.
 */
class BundledContentSource(
    private val context: Context,
    private val manifest: ContentManifest,
    private val installer: BundledAssetInstaller,
    private val dispatchers: DispatcherProvider,
) : ContentSource {

    override suspend fun resolve(component: ComponentId): ContentArtifact =
        withContext(dispatchers.io) {
            val entry = manifest.entry(component)
                ?: throw NoSuchElementException(
                    "No manifest entry for '${component.value}' " +
                        "(known: ${manifest.all().joinToString { it.component.id.value }})",
                )

            if (entry.kind == ManifestEntry.Kind.ROOTFS) {
                throw UnsupportedOperationException(
                    "ROOTFS is managed by RootfsInstaller; " +
                        "use RootfsInstaller.ensureInstalled instead of ContentSource.",
                )
            }

            // Cache hit: already installed at the version-encoded path.
            if (installer.isInstalled(entry)) {
                return@withContext ContentArtifact.Resolved(
                    entry.component, installer.resolvedPath(entry), entry.version,
                )
            }

            // Cache miss: stage the asset to a temp file (streaming SHA-256), verify, install.
            val archive = stageAndVerify(entry)
            try {
                val installed = installer.install(entry, archive)
                ContentArtifact.Resolved(entry.component, installed, entry.version)
            } finally {
                archive.delete()
            }
        }

    /**
     * Copy `entry.assetPath` from assets to a temp file, tee-ing the byte stream
     * through SHA-256. Verifies against [ManifestEntry.sha256] when pinned;
     * throws on mismatch. Returns the temp file (caller deletes).
     */
    private fun stageAndVerify(entry: ManifestEntry): File {
        val tmp = File.createTempFile(
            "amphora-${entry.component.id.value}-",
            ".pkg",
            context.cacheDir,
        )
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(entry.assetPath).use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                    output.write(buf, 0, n)
                }
            }
        }
        val actual = digest.digest().toHexString()
        val expected = entry.sha256
        if (expected != null && !expected.equals(actual, ignoreCase = true)) {
            tmp.delete()
            throw IllegalStateException(
                "SHA-256 mismatch for ${entry.component.id.value} (${entry.assetPath}): " +
                    "expected=$expected actual=$actual",
            )
        }
        if (expected == null) {
            android.util.Log.w(
                "BundledContentSource",
                "SHA-256 not pinned for ${entry.component.id.value} (${entry.assetPath}); " +
                    "skipping verification (actual=$actual).",
            )
        }
        return tmp
    }

    private companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }
