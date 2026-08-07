package app.amphora.core.content.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.VerifiedAssetDownloader
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.withContext

/**
 * Check / download / hand off an Amphora APK update.
 *
 * Download + SHA pin reuse [VerifiedAssetDownloader]. Callers may pass the
 * validated APK to the system installer or to Amphora's explicitly-authorized
 * Shizuku installer.
 */
class AppUpdater(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val downloader: VerifiedAssetDownloader,
    private val fileProviderAuthority: String = "${context.packageName}.fileprovider",
) {
    fun shouldCheckAtStartup(): Boolean = !isDevelopmentVersionCode(installedVersionCode())

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

    fun validateDownloadedApk(apk: File, manifest: AppUpdateManifest) {
        require(apk.isFile && apk.length() > 0L) { "Downloaded APK is missing" }
        val archive =
            requireNotNull(packageInfo(apk.absolutePath)) {
                "Downloaded file is not a valid APK"
            }
        require(archive.packageName == context.packageName) {
            "Update package mismatch: ${archive.packageName}"
        }
        require(versionCode(archive) == manifest.versionCode.toLong()) {
            "Update version mismatch: APK=${versionCode(archive)}, manifest=${manifest.versionCode}"
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, SIGNATURE_FLAGS)
        require(signingDigests(installed) == signingDigests(archive)) {
            "Update signature does not match the installed app"
        }
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun installedVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: PackageManager.NameNotFoundException) {
        "unknown"
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(path: String): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(path, SIGNATURE_FLAGS)

    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun signingDigests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                info.signatures ?: emptyArray()
            }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest
                .getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private companion object {
        const val UPDATE_CACHE_DIR = "apk-updates"
        const val APK_RELATIVE_PATH = "amphora-update.apk"
        const val APK_MIME = "application/vnd.android.package-archive"
        val SIGNATURE_FLAGS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
    }
}

internal fun isDevelopmentVersionCode(versionCode: Long): Boolean =
    versionCode < DISTRIBUTION_VERSION_CODE_BASE

private const val DISTRIBUTION_VERSION_CODE_BASE = 20_000_000L

sealed class AppUpdateCheckResult {
    data class UpToDate(val installedVersionCode: Long, val remote: AppUpdateManifest) : AppUpdateCheckResult()

    data class UpdateAvailable(val installedVersionCode: Long, val remote: AppUpdateManifest) : AppUpdateCheckResult()

    data class Unavailable(val reason: String) : AppUpdateCheckResult()

    data class Failed(val error: Throwable) : AppUpdateCheckResult()
}
