package app.amphora.core.content

import android.util.Log
import app.amphora.core.content.model.ManifestEntry
import java.io.File

/**
 * Global content reconcile after a manifest refresh or successful install.
 *
 * 1. [ContentAssetInstaller.reconcileToPin] per non-ROOTFS component, removing
 *    superseded installs only after the manifest-pinned replacement is present.
 * 2. [ContentPackageCache.pruneToPins] for superseded download filenames
 */
class ContentReconciler(private val packageRoot: File, private val installer: ContentAssetInstaller) {
    data class Report(val siblingDirsRemoved: Int, val packageFilesRemoved: Int) {
        val changed: Boolean get() = siblingDirsRemoved > 0 || packageFilesRemoved > 0
    }

    /**
     * Reconcile every installed component pin and the shared package cache.
     * Safe to call on every launcher / settings refresh: no-op when already clean.
     */
    fun reconcile(manifest: ContentManifest): Report {
        val pinned = manifest.all().filter { it.kind != ManifestEntry.Kind.ROOTFS }
        var siblings = 0
        for (entry in pinned) {
            siblings += installer.reconcileToPin(entry, pinned)
        }
        val keep =
            buildSet {
                for (entry in manifest.all()) {
                    if (entry.kind == ManifestEntry.Kind.ROOTFS) continue
                    add(entry.assetPath)
                }
                for (asset in manifest.runtimeAssets()) {
                    // Basename only — package cache is flat.
                    add(asset.assetPath.substringAfterLast('/'))
                }
            }
        val packages = ContentPackageCache.pruneToPins(packageRoot, keep)
        if (siblings > 0 || packages > 0) {
            Log.i(TAG, "Reconciled content: removed $siblings sibling dir(s), $packages cache file(s)")
        }
        return Report(siblings, packages)
    }

    companion object {
        private const val TAG = "ContentReconciler"
    }
}
