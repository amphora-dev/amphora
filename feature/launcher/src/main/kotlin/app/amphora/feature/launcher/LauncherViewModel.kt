package app.amphora.feature.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.content.ProvisionProgressBus
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.engine.GraphicsDriverIds
import app.amphora.core.engine.TurnipDriverProvisioner
import app.amphora.core.rootfs.RootfsInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Launcher state for the MVP "pick .exe -> choose resolution -> launch" flow
 * (RFC §3 v0.1 / §6). Also surfaces remote content / imagefs version info and
 * live download progress from [ProvisionProgressBus].
 */
@HiltViewModel
class LauncherViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val turnipProvisioner: TurnipDriverProvisioner,
    private val catalog: ContentCatalog,
    private val rootfsInstaller: RootfsInstaller,
    progressBus: ProvisionProgressBus,
) : ViewModel() {

    private val prefs =
        context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        LauncherUiState(
            appVersion = readAppVersion(),
            graphicsDriver = GraphicsDriverOption.fromDriverId(
                prefs.getString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, null),
            ),
        ),
    )
    val uiState: StateFlow<LauncherUiState> = combine(
        _uiState,
        catalog.status,
        progressBus.progress,
    ) { base, catalogStatus, progress ->
        base.copy(
            catalogStatus = catalogStatus,
            pinnedImagefsVersion = (catalogStatus as? ContentCatalog.Status.Ready)
                ?.manifest?.entry(ContentComponent.ROOTFS)?.version,
            provisionProgress = progress,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        _uiState.value,
    )

    init {
        refreshContentInfo()
    }

    fun refreshContentInfo() {
        viewModelScope.launch {
            _uiState.update { it.copy(contentBusy = true, stageError = null) }
            try {
                catalog.refresh()
                val installed = rootfsInstaller.currentVersion()
                _uiState.update {
                    it.copy(
                        contentBusy = false,
                        installedImagefsVersion = installed,
                    )
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update {
                    it.copy(
                        contentBusy = false,
                        stageError = "Manifest: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

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

    /** Persist adrenotools driver selection; downloads Turnip zip on first pick. */
    fun selectGraphicsDriver(option: GraphicsDriverOption) {
        viewModelScope.launch {
            _uiState.update { it.copy(driverBusy = true, stageError = null) }
            try {
                if (option == GraphicsDriverOption.TURNIP_BALANCED) {
                    turnipProvisioner.ensureInstalled()
                }
                prefs.edit()
                    .putString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, option.driverId)
                    .apply()
                _uiState.update { it.copy(graphicsDriver = option, driverBusy = false) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update {
                    it.copy(
                        driverBusy = false,
                        stageError = "Turnip install failed: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
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

    private fun readAppVersion(): String =
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }
}

data class LauncherUiState(
    val appVersion: String = "",
    val stagedExePath: String? = null,
    val staging: Boolean = false,
    val stageError: String? = null,
    val resolution: Resolution = Resolution.DEFAULT,
    val graphicsDriver: GraphicsDriverOption = GraphicsDriverOption.WRAPPER,
    val driverBusy: Boolean = false,
    val contentBusy: Boolean = false,
    val catalogStatus: ContentCatalog.Status = ContentCatalog.Status.Idle,
    val installedImagefsVersion: String? = null,
    val pinnedImagefsVersion: String? = null,
    val provisionProgress: ProvisionProgress? = null,
)

/** Adrenotools backend selectable from the launcher (persisted). */
enum class GraphicsDriverOption(val driverId: String, val label: String) {
    WRAPPER(GraphicsDriverIds.WRAPPER, "Wrapper"),
    TURNIP_BALANCED(GraphicsDriverIds.TURNIP_BALANCED, "Turnip 1.06-b"),
    ;

    companion object {
        fun fromDriverId(id: String?): GraphicsDriverOption =
            entries.firstOrNull { it.driverId == GraphicsDriverIds.normalize(id) } ?: WRAPPER
    }
}

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
