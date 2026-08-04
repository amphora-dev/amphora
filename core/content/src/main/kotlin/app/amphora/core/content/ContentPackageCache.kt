package app.amphora.core.content

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Download staging area for SHA-pinned component archives
 * (`cacheDir/amphora-packages/<assetPath>`).
 *
 * Pin bumps that change [assetPath] would otherwise leave orphan `.wcp` files
 * forever. [pruneToPins] keeps only the current manifest filenames (+ their
 * `.sha256` / `.part` sidecars).
 */
object ContentPackageCache {
    const val DIRECTORY_NAME = "amphora-packages"
    private const val TAG = "ContentPackageCache"

    @JvmStatic
    fun root(context: Context): File = File(context.cacheDir, DIRECTORY_NAME)

    /**
     * Delete files under [root] that are not the current pin set.
     *
     * @param keepAssetPaths relative names from the manifest (`Proton-….wcp`, …)
     * @return number of files deleted
     */
    fun pruneToPins(root: File, keepAssetPaths: Set<String>): Int {
        if (!root.isDirectory || keepAssetPaths.isEmpty()) return 0
        val keepNames = linkedSetOf<String>()
        for (asset in keepAssetPaths) {
            keepNames += asset
            keepNames += "$asset.sha256"
            keepNames += "$asset.part"
        }
        var removed = 0
        val children = root.listFiles() ?: return 0
        for (file in children) {
            if (!file.isFile) continue
            if (file.name in keepNames) continue
            if (file.delete()) {
                removed++
                Log.i(TAG, "Removed stale package cache ${file.name}")
            } else {
                Log.w(TAG, "Failed to delete stale package cache ${file.name}")
            }
        }
        return removed
    }
}
