package app.amphora.feature.launcher

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.content.ProvisionProgressBus
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.engine.DirectDrawWrapperIds
import app.amphora.core.engine.GraphicsDriverIds
import app.amphora.core.engine.LaunchRuntimeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Launcher state for the MVP "pick .exe -> choose resolution -> launch" flow
 * (RFC §3 v0.1 / §6). Surfaces remote content pins vs installed artifacts and
 * live download progress from [ProvisionProgressBus].
 */
@HiltViewModel
class LauncherViewModel internal constructor(private val operations: LauncherOperations) : ViewModel() {
    @Inject
    internal constructor(operations: DefaultLauncherOperations) : this(operations as LauncherOperations)

    private val _uiState =
        MutableStateFlow(
            LauncherUiState(
                appVersion = operations.appVersion,
            ).withRuntimeSettings(operations.runtimeSettings.value),
        )
    val uiState: StateFlow<LauncherUiState> =
        combine(
            _uiState,
            operations.catalogStatus,
            operations.provisionProgress,
        ) { base, catalogStatus, progress ->
            base.copy(
                catalogStatus = catalogStatus,
                provisionProgress = progress,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            _uiState.value,
        )

    init {
        loadPrograms()
        loadContentInfo(forceRefresh = false, showBusy = true)
        viewModelScope.launch {
            operations.runtimeSettings.collect { settings ->
                _uiState.update { it.withRuntimeSettings(settings) }
            }
        }
    }

    fun refreshContentInfo() {
        loadContentInfo(forceRefresh = true, showBusy = true)
    }

    private fun loadContentInfo(forceRefresh: Boolean, showBusy: Boolean) {
        viewModelScope.launch {
            val refreshMode =
                if (showBusy) {
                    ContentRefreshMode.BLOCKING
                } else {
                    ContentRefreshMode.BACKGROUND
                }
            _uiState.update { LauncherStateReducer.contentRefreshStarted(it, refreshMode) }
            try {
                val loaded = operations.loadContent(forceRefresh)
                val refreshInBackground =
                    LauncherStateEvaluator.shouldRefreshInBackground(forceRefresh, loaded.sourceUrl)
                _uiState.update {
                    LauncherStateReducer.contentRefreshSucceeded(
                        state = it,
                        mode = refreshMode,
                        snapshot = loaded.snapshot,
                    )
                }
                // Make the cached state launchable immediately, then refresh pins
                // without returning the launcher to a blocking Loading state.
                if (refreshInBackground) {
                    loadContentInfo(forceRefresh = true, showBusy = false)
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update {
                    LauncherStateReducer.contentRefreshFailed(it, refreshMode, e)
                }
            }
        }
    }

    fun onExePicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(staging = true, stageError = null) }
            try {
                val stagedPath = operations.stageProgram(uri)
                val programs = operations.listPrograms()
                _uiState.update {
                    it.copy(
                        stagedExePath = stagedPath,
                        recentPrograms = programs,
                        staging = false,
                    )
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update {
                    it.copy(staging = false, stageError = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun selectProgram(path: String) {
        _uiState.update { LauncherStateReducer.selectProgram(it, path) }
    }

    fun markProgramLaunched() {
        val path = _uiState.value.stagedExePath ?: return
        viewModelScope.launch {
            val programs = operations.markProgramLaunched(path)
            _uiState.update { it.copy(recentPrograms = programs) }
        }
    }

    fun selectResolution(resolution: Resolution) {
        operations.setResolutionName(resolution.name)
        _uiState.update { it.copy(resolution = resolution) }
    }

    /** Persist adrenotools driver selection; downloads Turnip zip on first pick. */
    fun selectGraphicsDriver(option: GraphicsDriverOption) {
        viewModelScope.launch {
            _uiState.update { it.copy(driverBusy = true, stageError = null) }
            try {
                if (option == GraphicsDriverOption.TURNIP_BALANCED) {
                    operations.ensureTurnipInstalled()
                }
                operations.setGraphicsDriverId(option.driverId)
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

    /** Persist the mutually-exclusive DirectDraw compatibility layer. */
    fun selectDirectDrawWrapper(option: DirectDrawWrapperOption) {
        operations.setDirectDrawWrapperId(option.id)
        _uiState.update { it.copy(directDrawWrapper = option) }
    }

    private fun loadPrograms() {
        viewModelScope.launch {
            val programs = operations.listPrograms()
            _uiState.update { it.copy(recentPrograms = programs) }
        }
    }
}

data class ComponentInstallStatus(
    val component: ContentComponent,
    val pinned: String?,
    val installed: String?,
    val matchesPin: Boolean,
    /** Dev/test inject armed via `<asset>.local-override`; remote pin is ignored. */
    val localOverride: Boolean = false,
) {
    val label: String get() = component.name.lowercase(Locale.ROOT)
}

/** One `runtimeAssets[]` entry as it exists under `filesDir/runtime-assets/`. */
data class RuntimeAssetStatus(
    val assetPath: String,
    val pinnedSha: String,
    val installedSha: String?,
    val sizeBytes: Long?,
    val state: State,
) {
    enum class State { OK, MISSING, MISMATCH, UNVERIFIED, LOCAL_OVERRIDE }

    val healthy: Boolean get() = state == State.OK || state == State.LOCAL_OVERRIDE
}

data class LauncherUiState(
    val appVersion: String = "",
    val stagedExePath: String? = null,
    val recentPrograms: List<RecentProgram> = emptyList(),
    val staging: Boolean = false,
    val stageError: String? = null,
    val resolution: Resolution = Resolution.DEFAULT,
    val graphicsDriver: GraphicsDriverOption = GraphicsDriverOption.WRAPPER,
    val directDrawWrapper: DirectDrawWrapperOption = DirectDrawWrapperOption.DXWRAPPER,
    val driverBusy: Boolean = false,
    val contentBusy: Boolean = false,
    val catalogStatus: ContentCatalog.Status = ContentCatalog.Status.Idle,
    val components: List<ComponentInstallStatus> = emptyList(),
    val runtimeAssets: List<RuntimeAssetStatus> = emptyList(),
    val imagefsResidue: Boolean = false,
    val provisionProgress: ProvisionProgress? = null,
)

internal fun LauncherUiState.withRuntimeSettings(settings: LaunchRuntimeSettings): LauncherUiState = copy(
    resolution = Resolution.fromPreference(settings.resolutionName),
    graphicsDriver = GraphicsDriverOption.fromDriverId(settings.graphicsDriverId),
    directDrawWrapper = DirectDrawWrapperOption.fromId(settings.directDrawWrapperId),
)

data class RecentProgram(val path: String, val name: String, val lastUsedAt: Long)

/** Adrenotools backend selectable from the launcher (persisted). */
enum class GraphicsDriverOption(val driverId: String, val label: String) {
    WRAPPER(GraphicsDriverIds.WRAPPER, "Adreno wrapper"),
    SYSTEM(GraphicsDriverIds.SYSTEM, "Android system Vulkan · 2D/test"),
    TURNIP_BALANCED(GraphicsDriverIds.TURNIP_BALANCED, "Turnip 1.06-b"),
    ;

    companion object {
        fun fromDriverId(id: String?): GraphicsDriverOption =
            entries.firstOrNull { it.driverId == GraphicsDriverIds.normalize(id) } ?: WRAPPER
    }
}

/** DirectDraw wrappers; all bypass WineD3D for 32-bit games. */
enum class DirectDrawWrapperOption(val id: String, val label: String) {
    DXWRAPPER(DirectDrawWrapperIds.DXWRAPPER_DD7TO9, "DxWrapper (Dd7to9)"),
    D7VK(DirectDrawWrapperIds.D7VK, "d7vk (D3D3–7)"),
    CNC_DDRAW(DirectDrawWrapperIds.CNC_DDRAW, "cnc-ddraw (2D)"),
    ;

    companion object {
        fun fromId(id: String?): DirectDrawWrapperOption =
            entries.firstOrNull { it.id == DirectDrawWrapperIds.normalize(id) } ?: DXWRAPPER
    }
}

/** A offered render resolution (maps to the Wine `explorer /desktop=shell,WxH` size). */
enum class Resolution(val width: Int, val height: Int, val label: String) {
    R1280x720(1280, 720, "1280×720"),
    R1280x800(1280, 800, "1280×800 · 16:10"),
    R1920x1080(1920, 1080, "1920×1080"),
    R1920x1200(1920, 1200, "1920×1200 · 16:10"),
    R2400x1080(2400, 1080, "2400×1080 · 20:9"),
    R1024x768(1024, 768, "1024×768"),
    R800x600(800, 600, "800×600"),
    ;

    companion object {
        val DEFAULT = R1280x720
        fun fromPreference(value: String?): Resolution = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
