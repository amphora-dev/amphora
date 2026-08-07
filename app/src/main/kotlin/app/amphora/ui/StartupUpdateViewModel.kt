package app.amphora.ui

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.content.update.AppUpdateCheckResult
import app.amphora.core.content.update.AppUpdateManifest
import app.amphora.core.content.update.AppUpdater
import app.amphora.core.engine.ShizukuEmergencyStopper
import app.amphora.core.engine.ShizukuInstallResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class StartupUpdateViewModel
@Inject
constructor(
    private val appUpdater: AppUpdater,
    private val shizuku: ShizukuEmergencyStopper,
) : ViewModel() {
    private val _state = MutableStateFlow(StartupUpdateState())
    val state: StateFlow<StartupUpdateState> = _state.asStateFlow()

    init {
        if (appUpdater.shouldCheckAtStartup()) checkAtStartup()
    }

    fun dismiss() {
        if (!_state.value.busy) _state.value = StartupUpdateState(dismissed = true)
    }

    fun installUpdate() {
        val update = _state.value.available ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    message = "Downloading and verifying ${update.versionName}…",
                )
            }
            try {
                val apk = appUpdater.download(update)
                appUpdater.validateDownloadedApk(apk, update)
                _state.update { it.copy(message = "Installing ${update.versionName}…") }
                when (val result = shizuku.installPackage(apk)) {
                    ShizukuInstallResult.Started ->
                        _state.update {
                            it.copy(message = "Install started. Amphora will reopen automatically.")
                        }
                    ShizukuInstallResult.Unavailable ->
                        offerSystemInstaller(apk, "Shizuku is unavailable; use the system installer.")
                    is ShizukuInstallResult.Failed ->
                        offerSystemInstaller(
                            apk,
                            "Automatic install failed: ${result.reason}",
                        )
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

    fun needsSystemInstallPermission(): Boolean = appUpdater.needsInstallPermission()

    fun installPermissionIntent(): Intent = appUpdater.installPermissionSettingsIntent()

    fun launchSystemInstaller(activity: Activity) {
        val apk = _state.value.pendingSystemApk ?: return
        activity.startActivity(appUpdater.installIntent(apk))
    }

    private fun checkAtStartup() {
        viewModelScope.launch {
            when (val result = appUpdater.check()) {
                is AppUpdateCheckResult.UpdateAvailable ->
                    _state.update { it.copy(available = result.remote) }
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
}

data class StartupUpdateState(
    val available: AppUpdateManifest? = null,
    val busy: Boolean = false,
    val pendingSystemApk: File? = null,
    val message: String? = null,
    val dismissed: Boolean = false,
)
