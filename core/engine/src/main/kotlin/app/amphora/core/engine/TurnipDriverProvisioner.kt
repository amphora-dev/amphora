package app.amphora.core.engine

import android.content.Context
import android.util.Log
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.VerifiedAssetDownloader
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional WN-Turnip adrenotools package: download (SHA-pinned) + unzip into
 * `contents/adrenotools/[GraphicsDriverIds.TURNIP_BALANCED]/`.
 *
 * Default graphics path stays [GraphicsDriverIds.WRAPPER]; this only runs when
 * the user selects Turnip.
 */
@Singleton
class TurnipDriverProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: VerifiedAssetDownloader,
    private val dispatchers: DispatcherProvider,
) {
    private val adrenotools = AdrenotoolsManager(context)

    suspend fun ensureInstalled(): File = withContext(dispatchers.io) {
        val driverId = GraphicsDriverIds.TURNIP_BALANCED
        if (adrenotools.isInstalled(driverId)) {
            Log.i(TAG, "Turnip adrenotools driver already installed: $driverId")
            return@withContext adrenotools.getDriverDir(driverId)
        }

        val cacheRoot = File(context.cacheDir, "amphora-downloads")
        val zip = downloader.acquire(
            root = cacheRoot,
            relativePath = GraphicsDriverIds.TURNIP_ZIP_RELATIVE,
            remoteUrl = GraphicsDriverIds.TURNIP_ZIP_URL,
            expectedSha256 = GraphicsDriverIds.TURNIP_ZIP_SHA256,
            expectedSize = GraphicsDriverIds.TURNIP_ZIP_SIZE,
            label = "Turnip ${GraphicsDriverIds.TURNIP_BALANCED}",
        )
        Log.i(TAG, "Installing Turnip from ${zip.absolutePath}")
        adrenotools.installFromZip(zip, driverId)
        adrenotools.getDriverDir(driverId)
    }

    /** Unzip a local zip (tests / offline cache) without re-downloading. */
    fun installFromLocalZip(zip: File): File {
        require(zip.isFile) { "Turnip zip missing: $zip" }
        adrenotools.installFromZip(zip, GraphicsDriverIds.TURNIP_BALANCED)
        return adrenotools.getDriverDir(GraphicsDriverIds.TURNIP_BALANCED)
    }

    companion object {
        private const val TAG = "TurnipDriverProvisioner"
    }
}
