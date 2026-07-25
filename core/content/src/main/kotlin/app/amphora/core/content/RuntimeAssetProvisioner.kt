package app.amphora.core.content

import android.content.Context
import java.io.File

/**
 * Downloads the small kernel-direct archives and metadata that legacy runtime
 * code addresses by APK asset path. Verified files persist in `filesDir`, so
 * subsequent launches perform marker/size checks only and make no network call.
 */
class RuntimeAssetProvisioner(
    private val context: Context,
    private val manifest: ContentManifest,
    private val downloader: VerifiedAssetDownloader,
) {
    suspend fun ensureAvailable() {
        val root = runtimeAssetsDir(context)
        for (entry in manifest.runtimeAssets()) {
            downloader.acquire(
                root = root,
                relativePath = entry.assetPath,
                remoteUrl = entry.remoteUrl,
                expectedSha256 = entry.sha256,
                expectedSize = entry.size,
            )
        }
    }

    companion object {
        const val DIRECTORY_NAME = "runtime-assets"

        @JvmStatic
        fun runtimeAssetsDir(context: Context): File = File(context.filesDir, DIRECTORY_NAME)
    }
}
