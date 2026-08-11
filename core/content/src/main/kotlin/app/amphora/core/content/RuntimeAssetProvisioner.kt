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
 * 1. Trust a `.local-override` inject (dev/test — skips remote pin).
 * 2. Trust an already-verified file under `filesDir/runtime-assets/`.
 * 3. Copy from the APK asset of the same relative path when present (offline
 *    fallback for patched AIO Graphics Test PEs).
 * 4. Fall back to HTTPS download via [VerifiedAssetDownloader].
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
        for (entry in manifest.runtimeAssets()) {
            val destination = File(root, entry.assetPath)
            if (RuntimeAssetLocalOverride.isActive(destination)) {
                Log.i(TAG, "Skipping remote pin for ${entry.assetPath} (local-override)")
                continue
            }
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
    }

    private fun isVerified(file: File, entry: RuntimeAssetEntry): Boolean {
        if (!file.isFile || (entry.size != null && file.length() != entry.size)) return false
        if (AssetDigest.matchesPin(file, entry.sha256)) return true
        val legacyPin = AssetDigest.pinnedSha(file)
        if (legacyPin.equals(entry.sha256, ignoreCase = true) && AssetDigest.pinnedSize(file) == null) {
            AssetDigest.writePin(file, entry.sha256)
            return true
        }
        return false
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
    }
}
