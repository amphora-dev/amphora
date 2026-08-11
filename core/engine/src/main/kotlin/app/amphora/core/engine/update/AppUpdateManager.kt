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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val installs = SingleFlightCoordinator<AppUpdateInstallResult>()

    val installStatus: StateFlow<ShizukuCleanupStatus> = shizuku.status

    fun shouldCheckAtStartup(): Boolean = appUpdater.shouldCheckAtStartup()

    suspend fun check(): AppUpdateCheckResult = appUpdater.check()

    fun installedVersionCode(): Long = appUpdater.installedVersionCode()

    fun installedVersionName(): String = appUpdater.installedVersionName()

    fun requestInstallPermission(): Boolean = shizuku.requestPermission()

    suspend fun downloadAndInstall(manifest: AppUpdateManifest): AppUpdateInstallResult =
        installs.run("${manifest.versionCode}:${manifest.sha256.lowercase()}") {
            performDownloadAndInstall(manifest)
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

internal class SingleFlightCoordinator<T> {
    private val mutex = Mutex()
    private var inFlight: Pair<String, CompletableDeferred<T>>? = null

    suspend fun run(key: String, operation: suspend () -> T): T {
        while (true) {
            val claim =
                mutex.withLock {
                    val existing = inFlight
                    if (existing != null) {
                        InstallClaim(existing.first, existing.second, ownsInstall = false)
                    } else {
                        val created = CompletableDeferred<T>()
                        inFlight = key to created
                        InstallClaim(key, created, ownsInstall = true)
                    }
                }
            if (!claim.ownsInstall) {
                if (claim.key == key) return claim.result.await()
                try {
                    claim.result.await()
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (_: Throwable) {
                    // A failed older update must not prevent this newer request.
                }
                continue
            }

            try {
                val result = operation()
                clearInFlight(claim.result)
                claim.result.complete(result)
                return result
            } catch (failure: Throwable) {
                clearInFlight(claim.result)
                claim.result.completeExceptionally(failure)
                throw failure
            }
        }
    }

    private suspend fun clearInFlight(result: CompletableDeferred<T>) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (inFlight?.second === result) {
                    inFlight = null
                }
            }
        }
    }

    private data class InstallClaim<T>(val key: String, val result: CompletableDeferred<T>, val ownsInstall: Boolean)
}

sealed interface AppUpdateInstallResult {
    data object Started : AppUpdateInstallResult

    data class SystemInstallerRequired(val apk: File, val reason: String) : AppUpdateInstallResult
}
