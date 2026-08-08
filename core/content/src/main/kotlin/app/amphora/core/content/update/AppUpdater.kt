package app.amphora.core.content.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.VerifiedAssetDownloader
import java.io.File
import kotlinx.coroutines.withContext

/**
 * Check / download / hand off an Amphora APK update.
 *
 * Installation always goes through the system package installer UI — ordinary
 * apps cannot silently replace themselves. Download + SHA pin reuse
 * [VerifiedAssetDownloader].
 */
class AppUpdater(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val downloader: VerifiedAssetDownloader,
    private val fileProviderAuthority: String = "${context.packageName}.fileprovider",
) {
    suspend fun check(): AppUpdateCheckResult = withContext(dispatchers.io) {
        val url =
            AppUpdateLoader.resolveRemoteUrl(context)
                ?: return@withContext AppUpdateCheckResult.Unavailable("update URL not configured")
        val remote =
            try {
                AppUpdateLoader.load(context, url)
            } catch (failure: Throwable) {
                return@withContext AppUpdateCheckResult.Failed(failure)
            }
        val installed = installedVersionCode()
        if (!remote.isNewerThan(installed)) {
            return@withContext AppUpdateCheckResult.UpToDate(installed, remote)
        }
        AppUpdateCheckResult.UpdateAvailable(installed, remote)
    }

    suspend fun download(manifest: AppUpdateManifest): File {
        val root = File(context.cacheDir, UPDATE_CACHE_DIR).apply { mkdirs() }
        return downloader.acquire(
            root = root,
            relativePath = APK_RELATIVE_PATH,
            remoteUrl = manifest.apkUrl,
            expectedSha256 = manifest.sha256,
            expectedSize = manifest.size,
            label = "amphora-${manifest.versionName}.apk",
        )
    }

    fun needsInstallPermission(): Boolean = !context.packageManager.canRequestPackageInstalls()

    fun installPermissionSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${context.packageName}".toUri(),
    )

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority, apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun installedVersionCode(): Long {
        val info =
            context.packageManager.getPackageInfo(context.packageName, 0)
        return info.longVersionCode
    }

    fun installedVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: PackageManager.NameNotFoundException) {
        "unknown"
    }

    private companion object {
        const val UPDATE_CACHE_DIR = "apk-updates"
        const val APK_RELATIVE_PATH = "amphora-update.apk"
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

sealed class AppUpdateCheckResult {
    data class UpToDate(val installedVersionCode: Long, val remote: AppUpdateManifest) : AppUpdateCheckResult()

    data class UpdateAvailable(val installedVersionCode: Long, val remote: AppUpdateManifest) : AppUpdateCheckResult()

    data class Unavailable(val reason: String) : AppUpdateCheckResult()

    data class Failed(val error: Throwable) : AppUpdateCheckResult()
}
