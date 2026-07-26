package app.amphora.core.content

import android.content.Context
import android.util.Log
import app.amphora.core.content.model.RuntimeAssetEntry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Provisions kernel-direct archives/metadata that legacy runtime code addresses
 * by asset path.
 *
 * Order per entry:
 * 1. Trust an already-verified file under `filesDir/runtime-assets/`.
 * 2. Copy from the APK asset of the same relative path when present (used for
 *    patched AIO Graphics Test PEs that are not yet on an upstream Release).
 * 3. Fall back to HTTPS download via [VerifiedAssetDownloader].
 */
class RuntimeAssetProvisioner(
    private val context: Context,
    private val manifest: ContentManifest,
    private val downloader: VerifiedAssetDownloader,
) {
    suspend fun ensureAvailable() {
        val root = runtimeAssetsDir(context)
        for (entry in manifest.runtimeAssets()) {
            val destination = File(root, entry.assetPath)
            if (isVerified(destination, entry)) continue
            if (installFromApkAsset(entry, destination)) continue
            downloader.acquire(
                root = root,
                relativePath = entry.assetPath,
                remoteUrl = entry.remoteUrl,
                expectedSha256 = entry.sha256,
                expectedSize = entry.size,
            )
        }
    }

    private fun isVerified(file: File, entry: RuntimeAssetEntry): Boolean {
        if (!file.isFile || file.length() != entry.size) return false
        val marker = File(file.absolutePath + SHA_SUFFIX)
        return marker.isFile && marker.readText().trim().equals(entry.sha256, ignoreCase = true)
    }

    private fun installFromApkAsset(entry: RuntimeAssetEntry, destination: File): Boolean {
        val input = try {
            context.assets.open(entry.assetPath)
        } catch (_: IOException) {
            return false
        }
        return try {
            destination.parentFile?.mkdirs()
            val partial = File(destination.absolutePath + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
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
            if (partial.length() != entry.size) {
                partial.delete()
                Log.w(TAG, "APK asset size mismatch for ${entry.assetPath}")
                return false
            }
            val actual = digest.digest().joinToString("") { b -> "%02x".format(b) }
            if (!actual.equals(entry.sha256, ignoreCase = true)) {
                partial.delete()
                Log.w(TAG, "APK asset SHA mismatch for ${entry.assetPath}")
                return false
            }
            if (destination.exists() && !destination.delete()) {
                partial.delete()
                return false
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            File(destination.absolutePath + SHA_SUFFIX).writeText(entry.sha256.lowercase())
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
        private const val SHA_SUFFIX = ".sha256"

        @JvmStatic
        fun runtimeAssetsDir(context: Context): File = File(context.filesDir, DIRECTORY_NAME)
    }
}
