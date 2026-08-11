package app.amphora.core.engine.update

import android.content.Intent
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.update.AppUpdateCheckResult
import app.amphora.core.content.update.AppUpdateManifest
import app.amphora.core.content.update.AppUpdater
import app.amphora.core.engine.ShizukuCleanupStatus
import app.amphora.core.engine.ShizukuEmergencyStopper
import app.amphora.core.engine.ShizukuInstallResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val dispatchers: DispatcherProvider,
) {
    private val installMutex = Mutex()
    private var inFlightInstall: Pair<String, CompletableDeferred<AppUpdateInstallResult>>? = null

    val installStatus: StateFlow<ShizukuCleanupStatus> = shizuku.status

    fun shouldCheckAtStartup(): Boolean = appUpdater.shouldCheckAtStartup()

    suspend fun check(): AppUpdateCheckResult = appUpdater.check()

    fun installedVersionCode(): Long = appUpdater.installedVersionCode()

    fun installedVersionName(): String = appUpdater.installedVersionName()

    fun requestInstallPermission(): Boolean = shizuku.requestPermission()

    suspend fun downloadAndInstall(manifest: AppUpdateManifest): AppUpdateInstallResult {
        val installKey = "${manifest.versionCode}:${manifest.sha256.lowercase()}"
        val (result, ownsInstall) =
            installMutex.withLock {
                val existing = inFlightInstall?.takeIf { it.first == installKey }?.second
                if (existing != null) {
                    existing to false
                } else {
                    CompletableDeferred<AppUpdateInstallResult>()
                        .also { inFlightInstall = installKey to it } to true
                }
            }
        if (!ownsInstall) return result.await()

        try {
            val installResult = performDownloadAndInstall(manifest)
            result.complete(installResult)
            return installResult
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
            throw failure
        } finally {
            withContext(NonCancellable) {
                installMutex.withLock {
                    if (inFlightInstall?.second === result) inFlightInstall = null
                }
            }
        }
    }

    private suspend fun performDownloadAndInstall(manifest: AppUpdateManifest): AppUpdateInstallResult {
        val apk = appUpdater.download(manifest)
        withContext(dispatchers.io) {
            appUpdater.validateDownloadedApk(apk, manifest)
        }
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
