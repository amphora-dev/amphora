package app.amphora.core.engine.update

import android.content.Intent
import app.amphora.core.content.update.AppUpdateCheckResult
import app.amphora.core.content.update.AppUpdateManifest
import app.amphora.core.content.update.AppUpdater
import app.amphora.core.engine.ShizukuCleanupStatus
import app.amphora.core.engine.ShizukuEmergencyStopper
import app.amphora.core.engine.ShizukuInstallResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared app-update flow used by both startup and settings.
 *
 * The low-level updater owns manifest checks, verified downloads, APK validation,
 * and system-installer intents. This manager adds the preferred Shizuku install
 * path and exposes a single fallback result for both user interfaces.
 */
@Singleton
class AppUpdateManager
@Inject
constructor(
    private val appUpdater: AppUpdater,
    private val shizuku: ShizukuEmergencyStopper,
) {
    val installStatus: StateFlow<ShizukuCleanupStatus> = shizuku.status

    fun shouldCheckAtStartup(): Boolean = appUpdater.shouldCheckAtStartup()

    suspend fun check(): AppUpdateCheckResult = appUpdater.check()

    fun installedVersionCode(): Long = appUpdater.installedVersionCode()

    fun installedVersionName(): String = appUpdater.installedVersionName()

    fun requestInstallPermission(): Boolean = shizuku.requestPermission()

    suspend fun downloadAndInstall(manifest: AppUpdateManifest): AppUpdateInstallResult {
        val apk = appUpdater.download(manifest)
        appUpdater.validateDownloadedApk(apk, manifest)
        return when (val result = shizuku.installPackage(apk)) {
            ShizukuInstallResult.Started -> AppUpdateInstallResult.Started
            ShizukuInstallResult.Unavailable ->
                AppUpdateInstallResult.SystemInstallerRequired(
                    apk = apk,
                    reason = "Shizuku is unavailable; use the system installer.",
                )
            is ShizukuInstallResult.Failed ->
                AppUpdateInstallResult.SystemInstallerRequired(
                    apk = apk,
                    reason = "Automatic install failed: ${result.reason}",
                )
        }
    }

    fun needsSystemInstallPermission(): Boolean = appUpdater.needsInstallPermission()

    fun installPermissionSettingsIntent(): Intent = appUpdater.installPermissionSettingsIntent()

    fun systemInstallerIntent(apk: File): Intent = appUpdater.installIntent(apk)
}

sealed interface AppUpdateInstallResult {
    data object Started : AppUpdateInstallResult

    data class SystemInstallerRequired(val apk: File, val reason: String) : AppUpdateInstallResult
}
