package app.amphora.core.content

import android.content.Context
import android.util.Log
import app.amphora.core.content.model.RuntimeAssetEntry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Provisions kernel-direct archives/metadata that legacy runtime code addresses
 * by asset path.
 *
 * Order per entry:
 * 1. Trust an already-verified file under `filesDir/runtime-assets/` that
 *    matches the **effective** catalog pin ([ContentCatalog] ⊕ [DevPinOverlay]).
 * 2. Copy from the APK asset of the same relative path when present (content
 *    staged into the APK by `stageBundledContent`).
 * 3. Fall back to HTTPS download via [VerifiedAssetDownloader].
 *
 * Afterwards, files that belong to no pinned asset path are deleted, so a pin
 * that moves to a new path does not leave the old bytes behind.
 */
class RuntimeAssetProvisioner(
    private val context: Context,
    private val catalog: ContentCatalog,
    private val downloader: VerifiedAssetDownloader,
    private val progressBus: ProvisionProgressBus? = null,
) {
    suspend fun ensureAvailable() {
        val manifest = catalog.require()
        val root = runtimeAssetsDir(context)
        val entries = manifest.runtimeAssets()
        for (entry in entries) {
            val destination = File(root, entry.assetPath)
            if (isVerified(destination, entry)) continue
            if (installFromApkAsset(entry, destination)) continue
            progressBus?.update(
                ProvisionProgress(
                    stage = "runtime",
                    detail = entry.assetPath,
                    bytesDownloaded = 0,
                    totalBytes = entry.size,
                ),
            )
            downloader.acquire(
                root = root,
                relativePath = entry.assetPath,
                remoteUrl = entry.remoteUrl,
                expectedSha256 = entry.sha256,
                expectedSize = entry.size,
                label = entry.assetPath,
            )
        }
        for (removed in pruneUnpinned(root, entries.mapTo(HashSet()) { it.assetPath })) {
            Log.i(TAG, "Removed unpinned runtime asset $removed")
        }
    }

    private fun isVerified(file: File, entry: RuntimeAssetEntry): Boolean {
        if (!file.isFile || (entry.size != null && file.length() != entry.size)) return false
        return AssetDigest.matchesPin(file, entry.sha256)
    }

    private fun installFromApkAsset(entry: RuntimeAssetEntry, destination: File): Boolean {
        val input =
            try {
                context.assets.open(entry.assetPath)
            } catch (_: IOException) {
                return false
            }
        return try {
            destination.parentFile?.mkdirs()
            val partial = File(destination.absolutePath + ".part")
            val digest = AssetDigest.newDigest()
            input.use { stream ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
            if (entry.size != null && partial.length() != entry.size) {
                partial.delete()
                Log.w(TAG, "APK asset size mismatch for ${entry.assetPath}")
                return false
            }
            val actual = with(AssetDigest) { digest.hex() }
            if (!actual.equals(entry.sha256, ignoreCase = true)) {
                partial.delete()
                Log.w(TAG, "APK asset SHA mismatch for ${entry.assetPath}")
                return false
            }
            AtomicFilePublisher.replace(partial, destination)
            AssetDigest.writePin(destination, entry.sha256)
            Log.i(TAG, "Installed ${entry.assetPath} from APK assets")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install ${entry.assetPath} from APK assets", e)
            false
        }
    }

    companion object {
        const val DIRECTORY_NAME = "runtime-assets"
        private const val TAG = "RuntimeAssetProvisioner"

        @JvmStatic
        fun runtimeAssetsDir(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

        /**
         * Suffixes of files that travel with an asset: its `.sha256` sidecar, the
         * sidecar's `.tmp` while it is being replaced, and a `.part` download or
         * APK copy in flight.
         */
        private val COMPANION_SUFFIXES = listOf(".tmp", AssetDigest.SHA_SUFFIX, ".part")

        /**
         * Deletes every file under [root] whose asset path (the relative path with
         * [COMPANION_SUFFIXES] stripped) is not in [pinnedPaths], then any
         * directories left empty. Returns the removed relative paths, sorted.
         */
        internal fun pruneUnpinned(root: File, pinnedPaths: Set<String>): List<String> {
            if (!root.isDirectory) return emptyList()
            val removed = mutableListOf<String>()
            root.walkBottomUp().forEach { file ->
                if (file == root) return@forEach
                if (file.isDirectory) {
                    if (file.list()?.isEmpty() == true) file.delete()
                    return@forEach
                }
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (assetPathOf(relative) in pinnedPaths) return@forEach
                if (file.delete()) removed += relative
            }
            return removed.sorted()
        }

        private fun assetPathOf(relativePath: String): String {
            var path = relativePath
            while (true) {
                val suffix = COMPANION_SUFFIXES.firstOrNull { path.endsWith(it) } ?: return path
                path = path.removeSuffix(suffix)
            }
        }
    }
}
