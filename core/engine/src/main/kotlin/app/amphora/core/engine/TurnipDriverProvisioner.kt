package app.amphora.core.engine

import android.content.Context
import android.util.Log
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.AssetDigest
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.InstalledContentPin
import app.amphora.core.content.VerifiedAssetDownloader
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import com.winlator.cmod.shared.io.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Optional WN-Turnip adrenotools package: download (SHA-pinned) + unzip into
 * `contents/adrenotools/[GraphicsDriverIds.TURNIP_BALANCED]/`.
 *
 * The pin comes from the remote manifest's `runtimeAssets[]` entry for
 * [GraphicsDriverIds.TURNIP_ZIP_RELATIVE], the same place `RuntimeAssetProvisioner`
 * reads, so there is one URL/SHA per asset.
 *
 * Default graphics path stays [GraphicsDriverIds.WRAPPER]; this only runs when
 * the user selects Turnip.
 */
@Singleton
class TurnipDriverProvisioner
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val catalog: ContentCatalog,
    private val downloader: VerifiedAssetDownloader,
    private val dispatchers: DispatcherProvider,
) {
    private val adrenotools = AdrenotoolsManager(context)

    suspend fun ensureInstalled(): File = withContext(dispatchers.io) {
        val driverId = GraphicsDriverIds.TURNIP_BALANCED
        val pin =
            catalog
                .require()
                .runtimeAssets()
                .firstOrNull { it.assetPath == GraphicsDriverIds.TURNIP_ZIP_RELATIVE }
                ?: error(
                    "content manifest has no runtimeAssets entry for " +
                        GraphicsDriverIds.TURNIP_ZIP_RELATIVE,
                )
        val driverDir = adrenotools.getDriverDir(driverId)
        if (adrenotools.isInstalled(driverId) && InstalledContentPin.matches(driverDir, pin.sha256)) {
            Log.i(TAG, "Turnip adrenotools driver already installed: $driverId (${pin.sha256})")
            return@withContext driverDir
        }
        val cacheRoot = File(context.cacheDir, "amphora-downloads")
        val zip =
            downloader.acquire(
                root = cacheRoot,
                relativePath = pin.assetPath,
                remoteUrl = pin.remoteUrl,
                expectedSha256 = pin.sha256,
                expectedSize = pin.size,
                label = "Turnip ${GraphicsDriverIds.TURNIP_BALANCED}",
            )
        Log.i(TAG, "Installing Turnip from ${zip.absolutePath}")
        FileUtils.delete(driverDir)
        adrenotools.installFromZip(zip, driverId)
        check(adrenotools.isInstalled(driverId)) { "Turnip install is incomplete: $driverDir" }
        InstalledContentPin.write(driverDir, pin.sha256)
        driverDir
    }

    /** Unzip a local zip (tests / offline cache) without re-downloading. */
    fun installFromLocalZip(zip: File): File {
        require(zip.isFile) { "Turnip zip missing: $zip" }
        val driverDir = adrenotools.getDriverDir(GraphicsDriverIds.TURNIP_BALANCED)
        FileUtils.delete(driverDir)
        adrenotools.installFromZip(zip, GraphicsDriverIds.TURNIP_BALANCED)
        check(adrenotools.isInstalled(GraphicsDriverIds.TURNIP_BALANCED)) {
            "Turnip install is incomplete: $driverDir"
        }
        InstalledContentPin.write(driverDir, AssetDigest.of(zip))
        return driverDir
    }

    companion object {
        private const val TAG = "TurnipDriverProvisioner"
    }
}
