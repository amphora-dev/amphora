package app.amphora.feature.launcher

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.common.dispatcher.DispatcherProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Launcher state for the MVP "pick .exe -> choose resolution -> launch" flow
 * (RFC §3 v0.1 / §6). The picked SAF content URI is staged (copied) into the
 * app-private `filesDir/exe/` dir; the engine then copies it into the Wine
 * prefix's `drive_c` (C: drive) at launch so `wine explorer` can run it via a
 * Windows path. The resulting Unix path is forwarded to the game-session route.
 *
 * Containers are an internal detail for MVP (RFC §9: multi-prefix is v0.2): the
 * session uses a single shared container (id `"1"`), created on first launch by
 * [app.amphora.core.engine.WinlatorContainerManager].
 */
@HiltViewModel
class LauncherViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    fun onExePicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(staging = true, stageError = null) }
            try {
                val stagedPath = stageExe(uri)
                _uiState.update { it.copy(stagedExePath = stagedPath, staging = false) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update {
                    it.copy(staging = false, stageError = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun selectResolution(resolution: Resolution) {
        _uiState.update { it.copy(resolution = resolution) }
    }

    /** Copy the picked file into `filesDir/exe/<name>` (app-private, guest-readable). */
    private suspend fun stageExe(uri: Uri): String = withContext(dispatchers.io) {
        val fileName = queryDisplayName(uri) ?: "game.exe"
        val exeDir = File(context.filesDir, "exe").apply { mkdirs() }
        val dest = File(exeDir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: throw IOException("Cannot open picked file: $uri")
        dest.absolutePath
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }
}

data class LauncherUiState(
    val stagedExePath: String? = null,
    val staging: Boolean = false,
    val stageError: String? = null,
    val resolution: Resolution = Resolution.DEFAULT,
)

/** A offered render resolution (maps to the Wine `explorer /desktop=shell,WxH` size). */
enum class Resolution(val width: Int, val height: Int, val label: String) {
    R1280x720(1280, 720, "1280×720"),
    R1920x1080(1920, 1080, "1920×1080"),
    R1024x768(1024, 768, "1024×768"),
    R800x600(800, 600, "800×600");

    companion object {
        val DEFAULT = R1280x720
    }
}
