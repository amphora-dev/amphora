package app.amphora.feature.settings

import android.content.Intent
import app.amphora.core.content.update.AppUpdateCheckResult
import app.amphora.core.content.update.AppUpdateManifest
import app.amphora.core.engine.ShizukuCleanupStatus
import app.amphora.core.engine.update.AppUpdateInstallResult
import app.amphora.core.engine.update.AppUpdateManager
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Executes update effects emitted by [SettingsUpdateCoordinator].
 *
 * Shizuku status remains externally observed so the owner can dispatch [SettingsUpdateEvent.PermissionReady]
 * only when its own state machine is waiting for that event.
 */
internal class SettingsUpdateController
@Inject
constructor(private val updateManager: AppUpdateManager) {
    val installStatus: StateFlow<ShizukuCleanupStatus>
        get() = updateManager.installStatus

    fun installedVersionCode(): Long = updateManager.installedVersionCode()

    fun installedVersionName(): String = updateManager.installedVersionName()

    fun needsSystemInstallPermission(): Boolean = updateManager.needsSystemInstallPermission()

    fun installPermissionSettingsIntent(): Intent = updateManager.installPermissionSettingsIntent()

    fun systemInstallerIntent(apk: File): Intent = updateManager.systemInstallerIntent(apk)

    suspend fun execute(
        effect: SettingsUpdateEffect<AppUpdateManifest>,
    ): SettingsUpdateEvent<AppUpdateManifest, File> = when (effect) {
        SettingsUpdateEffect.CheckForUpdate ->
            SettingsUpdateEvent.CheckCompleted(updateManager.check().toOutcome())
        SettingsUpdateEffect.RequestPermission ->
            SettingsUpdateEvent.PermissionRequestCompleted(
                requestStarted = updateManager.requestInstallPermission(),
                permissionReady = installStatus.value == ShizukuCleanupStatus.READY,
            )
        SettingsUpdateEffect.SchedulePermissionTimeout -> {
            delay(SHIZUKU_PERMISSION_WAIT_MS)
            SettingsUpdateEvent.PermissionWaitExpired
        }
        is SettingsUpdateEffect.DownloadAndInstall -> install(effect.update)
    }

    private suspend fun install(
        update: AppUpdateManifest,
    ): SettingsUpdateEvent<Nothing, File> =
        try {
            when (val result = updateManager.downloadAndInstall(update)) {
                AppUpdateInstallResult.Started -> SettingsUpdateEvent.InstallStarted
                is AppUpdateInstallResult.SystemInstallerRequired ->
                    SettingsUpdateEvent.SystemInstallerRequired(
                        artifact = result.apk,
                        reason = result.reason,
                    )
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            SettingsUpdateEvent.InstallFailed(failure.message ?: failure.toString())
        }

    private fun AppUpdateCheckResult.toOutcome(): UpdateCheckOutcome<AppUpdateManifest> = when (this) {
        is AppUpdateCheckResult.UpToDate -> UpdateCheckOutcome.UpToDate(remote.versionName)
        is AppUpdateCheckResult.UpdateAvailable ->
            UpdateCheckOutcome.UpdateAvailable(
                update = remote,
                installedVersionCode = installedVersionCode,
                remoteVersionCode = remote.versionCode.toLong(),
                remoteVersionName = remote.versionName,
            )
        is AppUpdateCheckResult.Unavailable -> UpdateCheckOutcome.Unavailable(reason)
        is AppUpdateCheckResult.Failed -> UpdateCheckOutcome.Failed(error.message ?: error.toString())
    }

    private companion object {
        const val SHIZUKU_PERMISSION_WAIT_MS = 10_000L
    }
}
