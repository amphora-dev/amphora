package app.amphora.feature.settings

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.AssetDigest
import app.amphora.core.content.ContentAssetInstaller
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.ContentReconciler
import app.amphora.core.content.RuntimeAssetLocalOverride
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.update.AppUpdateCheckResult
import app.amphora.core.content.update.AppUpdateManifest
import app.amphora.core.content.update.AppUpdater
import app.amphora.core.engine.AdvancedRuntimePreferences
import app.amphora.core.engine.DirectDrawWrapperIds
import app.amphora.core.engine.GraphicsDiag
import app.amphora.core.engine.GraphicsDriverIds
import app.amphora.core.engine.ShizukuCleanupStatus
import app.amphora.core.engine.ShizukuEmergencyStopper
import app.amphora.core.engine.TurnipDriverProvisioner
import app.amphora.core.engine.WindowsComponentPreferences
import app.amphora.core.engine.WineLocaleOption
import app.amphora.core.engine.WineLocalePreferences
import app.amphora.core.rootfs.RootfsInstaller
import com.winlator.cmod.runtime.compat.box64.Box64Preset
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val catalog: ContentCatalog,
    private val rootfsInstaller: RootfsInstaller,
    private val assetInstaller: ContentAssetInstaller,
    private val contentReconciler: ContentReconciler,
    private val turnipProvisioner: TurnipDriverProvisioner,
    private val shizukuEmergencyStopper: ShizukuEmergencyStopper,
    private val appUpdater: AppUpdater,
) : ViewModel() {
    private val prefs =
        context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
    private val initialCustomEnv =
        prefs.getString(AdvancedRuntimePreferences.KEY_CUSTOM_ENV, "").orEmpty()
    private val initialWindowsComponents = WindowsComponentPreferences.selections(context)

    private val _uiState =
        MutableStateFlow(
            SettingsUiState(
                installedVersionName = appUpdater.installedVersionName(),
                installedVersionCode = appUpdater.installedVersionCode(),
                resolution = DisplayResolution.fromPreference(prefs.getString(PREF_RESOLUTION, null)),
                graphicsDriver =
                GraphicsDriverSetting.fromId(
                    prefs.getString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, null),
                ),
                directDrawWrapper =
                DirectDrawSetting.fromId(
                    prefs.getString(DirectDrawWrapperIds.PREFS_KEY_WRAPPER_ID, null),
                ),
                wineLocale = WineLocalePreferences.selected(context),
                box64Mode =
                Box64Mode.fromId(
                    prefs.getString(AdvancedRuntimePreferences.KEY_BOX64_PRESET, null),
                ),
                dxvkAsync = prefs.getBoolean(AdvancedRuntimePreferences.KEY_DXVK_ASYNC, false),
                frameLimit =
                FrameLimit.fromValue(
                    prefs.getString(AdvancedRuntimePreferences.KEY_FRAME_RATE, null),
                ),
                presentMode =
                PresentMode.fromValue(
                    prefs.getString(AdvancedRuntimePreferences.KEY_PRESENT_MODE, null),
                ),
                bcnMode =
                BcnMode.fromValue(
                    prefs.getString(AdvancedRuntimePreferences.KEY_BCN_MODE, null),
                ),
                wineLog =
                WineLogMode.fromValue(
                    prefs.getString(AdvancedRuntimePreferences.KEY_WINE_DEBUG, null),
                ),
                hostPerformanceHud =
                prefs.getBoolean(AdvancedRuntimePreferences.KEY_HOST_PERF_HUD, false),
                dxvkHud = prefs.getBoolean(AdvancedRuntimePreferences.KEY_DXVK_HUD, false),
                shaderCache = prefs.getBoolean(AdvancedRuntimePreferences.KEY_SHADER_CACHE, true),
                shaderCacheSize =
                ShaderCacheSize.fromValue(
                    prefs.getString(AdvancedRuntimePreferences.KEY_SHADER_CACHE_SIZE, null),
                ),
                vkd3dFeatureLevel =
                Vkd3dFeatureLevel.fromValue(
                    prefs.getString(AdvancedRuntimePreferences.KEY_VKD3D_FEATURE_LEVEL, null),
                ),
                vkd3dShaderModel =
                Vkd3dShaderModel.fromValue(
                    prefs.getString(AdvancedRuntimePreferences.KEY_VKD3D_SHADER_MODEL, null),
                ),
                vkd3dDxr =
                Vkd3dDxrMode.fromValue(
                    prefs.getString(AdvancedRuntimePreferences.KEY_VKD3D_DXR, null),
                ),
                windowsComponents =
                WindowsComponentSetting.entries.associateWith { component ->
                    initialWindowsComponents[component.id] ?: true
                },
                customEnv = initialCustomEnv,
                rejectedEnvNames = AdvancedRuntimePreferences.rejectedCustomEnvNames(initialCustomEnv),
                shizukuCleanupStatus = shizukuEmergencyStopper.status.value,
            ),
        )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshComponents()
        viewModelScope.launch {
            shizukuEmergencyStopper.status.collect { status ->
                _uiState.update { it.copy(shizukuCleanupStatus = status) }
            }
        }
    }

    fun selectResolution(value: DisplayResolution) {
        prefs.edit { putString(PREF_RESOLUTION, value.name) }
        _uiState.update { it.copy(resolution = value) }
    }

    fun requestShizukuPermission() {
        if (!shizukuEmergencyStopper.requestPermission()) {
            _uiState.update {
                it.copy(error = "Shizuku is not running. Start it before requesting access.")
            }
        }
    }

    fun emergencyForceStop() {
        if (!shizukuEmergencyStopper.forceStopSelf()) {
            _uiState.update {
                it.copy(error = "Shizuku permission is required for emergency force-stop.")
            }
        }
    }

    fun selectGraphicsDriver(value: GraphicsDriverSetting) {
        viewModelScope.launch {
            _uiState.update { it.copy(applyingDriver = true, error = null) }
            try {
                if (value == GraphicsDriverSetting.TURNIP) turnipProvisioner.ensureInstalled()
                prefs.edit { putString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, value.id) }
                _uiState.update { it.copy(graphicsDriver = value, applyingDriver = false) }
                refreshComponents()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        applyingDriver = false,
                        error = "Could not install Turnip: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun selectDirectDraw(value: DirectDrawSetting) {
        prefs.edit { putString(DirectDrawWrapperIds.PREFS_KEY_WRAPPER_ID, value.id) }
        _uiState.update { it.copy(directDrawWrapper = value) }
    }

    fun selectWineLocale(value: WineLocaleOption) {
        WineLocalePreferences.set(context, value)
        _uiState.update { it.copy(wineLocale = value) }
    }

    fun selectBox64Mode(value: Box64Mode) {
        prefs.edit { putString(AdvancedRuntimePreferences.KEY_BOX64_PRESET, value.id) }
        _uiState.update { it.copy(box64Mode = value) }
    }

    fun setDxvkAsync(enabled: Boolean) {
        prefs.edit { putBoolean(AdvancedRuntimePreferences.KEY_DXVK_ASYNC, enabled) }
        _uiState.update { it.copy(dxvkAsync = enabled) }
    }

    fun selectFrameLimit(value: FrameLimit) {
        prefs.edit { putString(AdvancedRuntimePreferences.KEY_FRAME_RATE, value.value) }
        _uiState.update { it.copy(frameLimit = value) }
    }

    fun selectPresentMode(value: PresentMode) {
        prefs.edit { putString(AdvancedRuntimePreferences.KEY_PRESENT_MODE, value.value) }
        _uiState.update { it.copy(presentMode = value) }
    }

    fun selectBcnMode(value: BcnMode) {
        prefs.edit { putString(AdvancedRuntimePreferences.KEY_BCN_MODE, value.value) }
        _uiState.update { it.copy(bcnMode = value) }
    }

    fun selectWineLog(value: WineLogMode) {
        prefs.edit { putString(AdvancedRuntimePreferences.KEY_WINE_DEBUG, value.value) }
        _uiState.update { it.copy(wineLog = value) }
    }

    fun setDxvkHud(enabled: Boolean) {
        prefs.edit { putBoolean(AdvancedRuntimePreferences.KEY_DXVK_HUD, enabled) }
        _uiState.update { it.copy(dxvkHud = enabled) }
    }

    fun setHostPerformanceHud(enabled: Boolean) {
        prefs.edit { putBoolean(AdvancedRuntimePreferences.KEY_HOST_PERF_HUD, enabled) }
        _uiState.update { it.copy(hostPerformanceHud = enabled) }
    }

    fun setShaderCache(enabled: Boolean) {
        prefs.edit { putBoolean(AdvancedRuntimePreferences.KEY_SHADER_CACHE, enabled) }
        _uiState.update { it.copy(shaderCache = enabled) }
    }

    fun selectShaderCacheSize(value: ShaderCacheSize) {
        prefs.edit { putString(AdvancedRuntimePreferences.KEY_SHADER_CACHE_SIZE, value.value) }
        _uiState.update { it.copy(shaderCacheSize = value) }
    }

    fun selectVkd3dFeatureLevel(value: Vkd3dFeatureLevel) {
        prefs.edit { putString(AdvancedRuntimePreferences.KEY_VKD3D_FEATURE_LEVEL, value.value) }
        _uiState.update { it.copy(vkd3dFeatureLevel = value) }
    }

    fun selectVkd3dShaderModel(value: Vkd3dShaderModel) {
        prefs.edit { putString(AdvancedRuntimePreferences.KEY_VKD3D_SHADER_MODEL, value.value) }
        _uiState.update { it.copy(vkd3dShaderModel = value) }
    }

    fun selectVkd3dDxr(value: Vkd3dDxrMode) {
        prefs.edit { putString(AdvancedRuntimePreferences.KEY_VKD3D_DXR, value.value) }
        _uiState.update { it.copy(vkd3dDxr = value) }
    }

    fun setWindowsComponentNative(component: WindowsComponentSetting, useNative: Boolean) {
        WindowsComponentPreferences.setNative(context, component.id, useNative)
        _uiState.update {
            it.copy(windowsComponents = it.windowsComponents + (component to useNative))
        }
    }

    fun clearShaderCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(clearingShaderCache = true, cacheActionMessage = null) }
            try {
                withContext(dispatchers.io) { GraphicsDiag.clearStateCache(context) }
                _uiState.update {
                    it.copy(
                        clearingShaderCache = false,
                        cacheActionMessage = "Shader and DXVK state caches cleared.",
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        clearingShaderCache = false,
                        cacheActionMessage = "Could not clear caches: ${error.message}",
                    )
                }
            }
        }
    }

    fun setCustomEnv(value: String) {
        prefs.edit { putString(AdvancedRuntimePreferences.KEY_CUSTOM_ENV, value) }
        _uiState.update {
            it.copy(
                customEnv = value,
                rejectedEnvNames = AdvancedRuntimePreferences.rejectedCustomEnvNames(value),
            )
        }
    }

    fun refreshComponents() {
        if (_uiState.value.refreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, error = null) }
            try {
                val manifest = catalog.refresh()
                val snapshot =
                    withContext(dispatchers.io) {
                        contentReconciler.reconcile(manifest)
                        ComponentSnapshot(
                            components = scanComponents(manifest),
                            runtimeAssets = scanRuntimeAssets(manifest),
                            imagefsResidue = File(context.filesDir, "imagefs.olddead").exists(),
                        )
                    }
                _uiState.update {
                    it.copy(
                        refreshing = false,
                        components = snapshot.components,
                        runtimeAssets = snapshot.runtimeAssets,
                        imagefsResidue = snapshot.imagefsResidue,
                        manifestReady = true,
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        refreshing = false,
                        manifestReady = false,
                        error = "Component status unavailable: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private suspend fun scanComponents(manifest: ContentManifest): List<ComponentStatus> =
        ContentComponent.entries.map { component ->
            val entry = manifest.entry(component)
            val pin = entry?.pinLabel()
            val installed =
                if (component == ContentComponent.ROOTFS) {
                    rootfsInstaller.currentVersion()
                } else {
                    installedLabel(entry)
                }
            val state =
                when {
                    entry == null -> ComponentHealth.NO_PIN
                    installed == null -> ComponentHealth.MISSING
                    component == ContentComponent.ROOTFS && installed != pin -> ComponentHealth.UPDATE
                    component != ContentComponent.ROOTFS && !assetInstaller.isInstalled(entry) ->
                        ComponentHealth.UPDATE
                    else -> ComponentHealth.READY
                }
            ComponentStatus(
                component = component,
                installed = installed,
                pinned = pin,
                health = state,
            )
        }

    private fun scanRuntimeAssets(manifest: ContentManifest): List<RuntimeAssetHealth> {
        val root = RuntimeAssetProvisioner.runtimeAssetsDir(context)
        return manifest.runtimeAssets().map { entry ->
            val file = File(root, entry.assetPath)
            val override =
                RuntimeAssetLocalOverride
                    .takeIf { RuntimeAssetLocalOverride.isActive(file) }
                    ?.let { RuntimeAssetLocalOverride.markerFile(file).readText().trim() }
            val installedSha = AssetDigest.pinnedSha(file)
            val health =
                when {
                    override != null -> AssetHealth.LOCAL
                    !file.isFile -> AssetHealth.MISSING
                    installedSha == null -> AssetHealth.UNVERIFIED
                    installedSha != entry.sha256.lowercase() -> AssetHealth.MISMATCH
                    entry.size != null && file.length() != entry.size -> AssetHealth.MISMATCH
                    else -> AssetHealth.READY
                }
            RuntimeAssetHealth(entry.assetPath, health)
        }
    }

    private fun installedLabel(entry: ManifestEntry?): String? {
        if (entry == null) return null
        if (assetInstaller.isInstalled(entry)) return entry.pinLabel()
        if (entry.kind != ManifestEntry.Kind.WCP) return null
        val type =
            ContentProfile.ContentType.getTypeByName(entry.contentType ?: return null) ?: return null
        return ContentsManager
            .getContentTypeDir(context, type)
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(updateBusy = true, updateMessage = null, pendingApk = null, installReady = false)
            }
            when (val result = appUpdater.check()) {
                is AppUpdateCheckResult.UpToDate ->
                    _uiState.update {
                        it.copy(
                            updateBusy = false,
                            availableUpdate = null,
                            updateMessage = "Up to date (${result.remote.versionName})",
                        )
                    }
                is AppUpdateCheckResult.UpdateAvailable ->
                    _uiState.update {
                        it.copy(
                            updateBusy = false,
                            availableUpdate = result.remote,
                            updateMessage =
                            "Update available: ${result.remote.versionName} " +
                                "(${result.installedVersionCode} → ${result.remote.versionCode})",
                        )
                    }
                is AppUpdateCheckResult.Unavailable ->
                    _uiState.update {
                        it.copy(updateBusy = false, availableUpdate = null, updateMessage = result.reason)
                    }
                is AppUpdateCheckResult.Failed ->
                    _uiState.update {
                        it.copy(
                            updateBusy = false,
                            availableUpdate = null,
                            updateMessage = result.error.message ?: result.error.toString(),
                        )
                    }
            }
        }
    }

    fun downloadAndPrepareInstall() {
        val remote = _uiState.value.availableUpdate ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    updateBusy = true,
                    updateMessage = "Downloading ${remote.versionName}…",
                    installReady = false,
                    pendingApk = null,
                )
            }
            try {
                val apk = appUpdater.download(remote)
                _uiState.update {
                    it.copy(
                        updateBusy = false,
                        pendingApk = apk,
                        installReady = true,
                        updateMessage = "Download ready. Tap Install to continue.",
                    )
                }
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(
                        updateBusy = false,
                        pendingApk = null,
                        installReady = false,
                        updateMessage = failure.message ?: failure.toString(),
                    )
                }
            }
        }
    }

    fun needsInstallPermission(): Boolean = appUpdater.needsInstallPermission()

    fun installPermissionSettingsIntent() = appUpdater.installPermissionSettingsIntent()

    fun installPendingUpdate(activity: Activity) {
        val apk = _uiState.value.pendingApk ?: return
        if (appUpdater.needsInstallPermission()) {
            activity.startActivity(appUpdater.installPermissionSettingsIntent())
            return
        }
        activity.startActivity(appUpdater.installIntent(apk))
    }

    private data class ComponentSnapshot(
        val components: List<ComponentStatus>,
        val runtimeAssets: List<RuntimeAssetHealth>,
        val imagefsResidue: Boolean,
    )

    companion object {
        const val PREF_RESOLUTION = "display_resolution"
    }
}

private fun ManifestEntry.pinLabel(): String = verName ?: version

data class SettingsUiState(
    val installedVersionName: String = "unknown",
    val installedVersionCode: Long = 0,
    val updateBusy: Boolean = false,
    val availableUpdate: AppUpdateManifest? = null,
    val pendingApk: File? = null,
    val installReady: Boolean = false,
    val updateMessage: String? = null,
    val resolution: DisplayResolution = DisplayResolution.DEFAULT,
    val graphicsDriver: GraphicsDriverSetting = GraphicsDriverSetting.WRAPPER,
    val directDrawWrapper: DirectDrawSetting = DirectDrawSetting.DXWRAPPER,
    val wineLocale: WineLocaleOption = WineLocaleOption.AUTO,
    val box64Mode: Box64Mode = Box64Mode.PERFORMANCE,
    val dxvkAsync: Boolean = false,
    val frameLimit: FrameLimit = FrameLimit.OFF,
    val presentMode: PresentMode = PresentMode.AUTO,
    val bcnMode: BcnMode = BcnMode.DEFAULT,
    val wineLog: WineLogMode = WineLogMode.OFF,
    val hostPerformanceHud: Boolean = false,
    val dxvkHud: Boolean = false,
    val shaderCache: Boolean = true,
    val shaderCacheSize: ShaderCacheSize = ShaderCacheSize.MB512,
    val vkd3dFeatureLevel: Vkd3dFeatureLevel = Vkd3dFeatureLevel.AUTO,
    val vkd3dShaderModel: Vkd3dShaderModel = Vkd3dShaderModel.AUTO,
    val vkd3dDxr: Vkd3dDxrMode = Vkd3dDxrMode.AUTO,
    val windowsComponents: Map<WindowsComponentSetting, Boolean> =
        WindowsComponentSetting.entries.associateWith { true },
    val clearingShaderCache: Boolean = false,
    val cacheActionMessage: String? = null,
    val customEnv: String = "",
    val rejectedEnvNames: List<String> = emptyList(),
    val shizukuCleanupStatus: ShizukuCleanupStatus = ShizukuCleanupStatus.UNAVAILABLE,
    val applyingDriver: Boolean = false,
    val refreshing: Boolean = false,
    val manifestReady: Boolean = false,
    val components: List<ComponentStatus> = emptyList(),
    val runtimeAssets: List<RuntimeAssetHealth> = emptyList(),
    val imagefsResidue: Boolean = false,
    val error: String? = null,
) {
    val unhealthyComponents: Int get() = components.count { it.health != ComponentHealth.READY }
    val unhealthyAssets: Int
        get() = runtimeAssets.count { it.health !in setOf(AssetHealth.READY, AssetHealth.LOCAL) }
    val localAssets: Int get() = runtimeAssets.count { it.health == AssetHealth.LOCAL }
}

enum class DisplayResolution(val width: Int, val height: Int, val label: String) {
    R1280x720(1280, 720, "1280 × 720"),
    R1280x800(1280, 800, "1280 × 800 · 16:10"),
    R1920x1080(1920, 1080, "1920 × 1080"),
    R1920x1200(1920, 1200, "1920 × 1200 · 16:10"),
    R2400x1080(2400, 1080, "2400 × 1080 · 20:9"),
    R1024x768(1024, 768, "1024 × 768"),
    R800x600(800, 600, "800 × 600"),
    ;

    companion object {
        val DEFAULT = R1280x720
        fun fromPreference(value: String?): DisplayResolution = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

enum class GraphicsDriverSetting(val id: String, val label: String) {
    WRAPPER(GraphicsDriverIds.WRAPPER, "Adreno wrapper"),
    SYSTEM(GraphicsDriverIds.SYSTEM, "Android system Vulkan · 2D/test"),
    TURNIP(GraphicsDriverIds.TURNIP_BALANCED, "Turnip 1.06-b"),
    ;

    companion object {
        fun fromId(value: String?): GraphicsDriverSetting =
            entries.firstOrNull { it.id == GraphicsDriverIds.normalize(value) } ?: WRAPPER
    }
}

enum class DirectDrawSetting(val id: String, val label: String) {
    DXWRAPPER(DirectDrawWrapperIds.DXWRAPPER_DD7TO9, "DxWrapper"),
    D7VK(DirectDrawWrapperIds.D7VK, "d7vk"),
    CNC_DDRAW(DirectDrawWrapperIds.CNC_DDRAW, "cnc-ddraw"),
    ;

    companion object {
        fun fromId(value: String?): DirectDrawSetting =
            entries.firstOrNull { it.id == DirectDrawWrapperIds.normalize(value) } ?: DXWRAPPER
    }
}

data class ComponentStatus(
    val component: ContentComponent,
    val installed: String?,
    val pinned: String?,
    val health: ComponentHealth,
)

enum class ComponentHealth { READY, MISSING, UPDATE, NO_PIN }

data class RuntimeAssetHealth(val path: String, val health: AssetHealth)

enum class AssetHealth { READY, MISSING, MISMATCH, UNVERIFIED, LOCAL }

enum class Box64Mode(val id: String, val label: String) {
    PERFORMANCE(Box64Preset.PERFORMANCE, "Performance"),
    BALANCED(Box64Preset.INTERMEDIATE, "Balanced"),
    COMPATIBILITY(Box64Preset.COMPATIBILITY, "Compatibility"),
    STABILITY(Box64Preset.STABILITY, "Stability"),
    ;

    companion object {
        fun fromId(value: String?): Box64Mode = entries.firstOrNull { it.id == value } ?: PERFORMANCE
    }
}

enum class FrameLimit(val value: String, val label: String) {
    OFF("off", "Off"),
    FPS30("30", "30"),
    FPS45("45", "45"),
    FPS60("60", "60"),
    FPS90("90", "90"),
    FPS120("120", "120"),
    ;

    companion object {
        fun fromValue(value: String?): FrameLimit = entries.firstOrNull { it.value == value } ?: OFF
    }
}

enum class PresentMode(val value: String, val label: String) {
    AUTO("auto", "Automatic"),
    MAILBOX("mailbox", "Mailbox"),
    FIFO("fifo", "VSync"),
    IMMEDIATE("immediate", "Immediate"),
    ;

    companion object {
        fun fromValue(value: String?): PresentMode = entries.firstOrNull { it.value == value } ?: AUTO
    }
}

enum class BcnMode(val value: String, val label: String) {
    DEFAULT("default", "Driver default"),
    AUTO("auto", "Automatic"),
    FULL("full", "Full emulation"),
    NONE("none", "Disabled"),
    ;

    companion object {
        fun fromValue(value: String?): BcnMode = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

enum class WineLogMode(val value: String, val label: String) {
    OFF("off", "Off"),
    ERRORS("errors", "Errors"),
    WARNINGS("warnings", "Errors + warnings"),
    ;

    companion object {
        fun fromValue(value: String?): WineLogMode = entries.firstOrNull { it.value == value } ?: OFF
    }
}

enum class ShaderCacheSize(val value: String, val label: String) {
    MB256("256MB", "256 MB"),
    MB512("512MB", "512 MB"),
    GB1("1GB", "1 GB"),
    GB2("2GB", "2 GB"),
    ;

    companion object {
        fun fromValue(value: String?): ShaderCacheSize = entries.firstOrNull { it.value == value } ?: MB512
    }
}

enum class Vkd3dFeatureLevel(val value: String, val label: String) {
    AUTO("auto", "Automatic"),
    LEVEL_11_0("11_0", "11.0"),
    LEVEL_12_0("12_0", "12.0"),
    LEVEL_12_1("12_1", "12.1"),
    LEVEL_12_2("12_2", "12.2"),
    ;

    companion object {
        fun fromValue(value: String?): Vkd3dFeatureLevel = entries.firstOrNull { it.value == value } ?: AUTO
    }
}

enum class Vkd3dShaderModel(val value: String, val label: String) {
    AUTO("auto", "Automatic"),
    MODEL_6_0("6_0", "6.0"),
    MODEL_6_3("6_3", "6.3"),
    MODEL_6_5("6_5", "6.5"),
    MODEL_6_6("6_6", "6.6"),
    MODEL_6_7("6_7", "6.7"),
    MODEL_6_8("6_8", "6.8"),
    MODEL_6_9("6_9", "6.9"),
    ;

    companion object {
        fun fromValue(value: String?): Vkd3dShaderModel = entries.firstOrNull { it.value == value } ?: AUTO
    }
}

enum class Vkd3dDxrMode(val value: String, val label: String) {
    AUTO("auto", "Automatic"),
    DISABLED("disabled", "Disabled"),
    FORCE("force", "Force DXR"),
    EXPERIMENTAL_1_2("experimental_1_2", "DXR 1.2"),
    ;

    companion object {
        fun fromValue(value: String?): Vkd3dDxrMode = entries.firstOrNull { it.value == value } ?: AUTO
    }
}

enum class WindowsComponentSetting(val id: String, val label: String, val description: String) {
    DIRECT3D("direct3d", "Direct3D helpers", "D3DX and shader compiler DLLs used by many games"),
    DIRECT_SOUND("directsound", "DirectSound", "Legacy hardware-accelerated game audio"),
    DIRECT_MUSIC("directmusic", "DirectMusic", "Music playback used by older DirectX games"),
    DIRECT_SHOW("directshow", "DirectShow", "Video playback and media filters"),
    DIRECT_PLAY("directplay", "DirectPlay", "Legacy multiplayer networking"),
    XAUDIO("xaudio", "XAudio", "XAudio2 and XACT audio engines"),
    DINPUT8("dinput8", "DirectInput 8", "Legacy keyboard, mouse and controller input"),
    VCRUN2010("vcrun2010", "Visual C++ 2010", "Microsoft C/C++ runtime libraries"),
}
