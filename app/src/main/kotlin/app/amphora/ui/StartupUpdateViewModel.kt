package app.amphora.ui

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.content.update.AppUpdateCheckResult
import app.amphora.core.content.update.AppUpdateManifest
import app.amphora.core.engine.ShizukuCleanupStatus
import app.amphora.core.engine.update.AppUpdateInstallResult
import app.amphora.core.engine.update.AppUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class StartupUpdateViewModel
@Inject
constructor(private val updateManager: AppUpdateManager) : ViewModel() {
    private val _state = MutableStateFlow(StartupUpdateState())
    val state: StateFlow<StartupUpdateState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            updateManager.installStatus.collect { status ->
                if (status == ShizukuCleanupStatus.READY &&
                    _state.value.waitingForShizukuPermission
                ) {
                    _state.update {
                        it.copy(
                            waitingForShizukuPermission = false,
                            message = "Shizuku authorized. Starting update…",
                        )
                    }
                    downloadAndInstall()
                }
            }
        }
        if (updateManager.shouldCheckAtStartup()) {
            Log.i(TAG, "Checking for app update at startup")
            checkAtStartup()
        } else {
            Log.i(TAG, "Skipping startup update check for local development build")
        }
    }

    fun dismiss() {
        if (!_state.value.busy) _state.value = StartupUpdateState(dismissed = true)
    }

    fun installUpdate() {
        if (_state.value.busy) return
        if (updateManager.installStatus.value == ShizukuCleanupStatus.PERMISSION_REQUIRED) {
            if (updateManager.requestInstallPermission()) {
                _state.update {
                    it.copy(
                        waitingForShizukuPermission = true,
                        message = "Grant Shizuku access to install automatically.",
                    )
                }
                viewModelScope.launch {
                    delay(SHIZUKU_PERMISSION_WAIT_MS)
                    if (_state.value.waitingForShizukuPermission) {
                        _state.update {
                            it.copy(
                                waitingForShizukuPermission = false,
                                message = "Shizuku authorization was not completed; using fallback.",
                            )
                        }
                        downloadAndInstall()
                    }
                }
            } else {
                downloadAndInstall()
            }
            return
        }
        downloadAndInstall()
    }

    private fun downloadAndInstall() {
        val update = _state.value.available ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    message = "Downloading and verifying ${update.versionName}…",
                )
            }
            try {
                when (val result = updateManager.downloadAndInstall(update)) {
                    AppUpdateInstallResult.Started ->
                        _state.update {
                            it.copy(message = "Install started. Amphora will reopen automatically.")
                        }
                    is AppUpdateInstallResult.SystemInstallerRequired ->
                        offerSystemInstaller(result.apk, result.reason)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun needsSystemInstallPermission(): Boolean = updateManager.needsSystemInstallPermission()

    fun installPermissionIntent(): Intent = updateManager.installPermissionSettingsIntent()

    fun launchSystemInstaller(activity: Activity) {
        val apk = _state.value.pendingSystemApk ?: return
        activity.startActivity(updateManager.systemInstallerIntent(apk))
    }

    private fun checkAtStartup() {
        viewModelScope.launch {
            when (val result = updateManager.check()) {
                is AppUpdateCheckResult.UpdateAvailable -> {
                    Log.i(TAG, "Startup update available: ${result.remote.versionName}")
                    _state.update { it.copy(available = result.remote) }
                }
                is AppUpdateCheckResult.UpToDate,
                is AppUpdateCheckResult.Unavailable,
                is AppUpdateCheckResult.Failed,
                -> Unit // Startup checks are silent unless an update exists.
            }
        }
    }

    private fun offerSystemInstaller(apk: File, message: String) {
        _state.update {
            it.copy(
                busy = false,
                pendingSystemApk = apk,
                message = message,
            )
        }
    }

    private companion object {
        const val TAG = "StartupUpdate"
        const val SHIZUKU_PERMISSION_WAIT_MS = 10_000L
    }
}

data class StartupUpdateState(
    val available: AppUpdateManifest? = null,
    val busy: Boolean = false,
    val pendingSystemApk: File? = null,
    val message: String? = null,
    val dismissed: Boolean = false,
    val waitingForShizukuPermission: Boolean = false,
)
