package app.amphora.feature.settings

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.AssetDigest
import app.amphora.core.content.ContentAssetInstaller
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.RuntimeAssetLocalOverride
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.engine.DirectDrawWrapperIds
import app.amphora.core.engine.GraphicsDriverIds
import app.amphora.core.engine.TurnipDriverProvisioner
import app.amphora.core.rootfs.RootfsInstaller
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
    private val turnipProvisioner: TurnipDriverProvisioner,
) : ViewModel() {
    private val prefs =
        context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState =
        MutableStateFlow(
            SettingsUiState(
                resolution = DisplayResolution.fromPreference(prefs.getString(PREF_RESOLUTION, null)),
                graphicsDriver =
                GraphicsDriverSetting.fromId(
                    prefs.getString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, null),
                ),
                directDrawWrapper =
                DirectDrawSetting.fromId(
                    prefs.getString(DirectDrawWrapperIds.PREFS_KEY_WRAPPER_ID, null),
                ),
            ),
        )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshComponents()
    }

    fun selectResolution(value: DisplayResolution) {
        prefs.edit { putString(PREF_RESOLUTION, value.name) }
        _uiState.update { it.copy(resolution = value) }
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

    fun refreshComponents() {
        if (_uiState.value.refreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, error = null) }
            try {
                val manifest = catalog.refresh()
                val snapshot =
                    withContext(dispatchers.io) {
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
    val resolution: DisplayResolution = DisplayResolution.DEFAULT,
    val graphicsDriver: GraphicsDriverSetting = GraphicsDriverSetting.WRAPPER,
    val directDrawWrapper: DirectDrawSetting = DirectDrawSetting.DXWRAPPER,
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
    R1920x1080(1920, 1080, "1920 × 1080"),
    R1024x768(1024, 768, "1024 × 768"),
    R800x600(800, 600, "800 × 600"),
    ;

    companion object {
        val DEFAULT = R1280x720
        fun fromPreference(value: String?): DisplayResolution =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

enum class GraphicsDriverSetting(val id: String, val label: String) {
    WRAPPER(GraphicsDriverIds.WRAPPER, "System driver"),
    TURNIP(GraphicsDriverIds.TURNIP_BALANCED, "Turnip 1.06-b"),
    ;

    companion object {
        fun fromId(value: String?): GraphicsDriverSetting =
            entries.firstOrNull { it.id == GraphicsDriverIds.normalize(value) } ?: WRAPPER
    }
}

enum class DirectDrawSetting(val id: String, val label: String) {
    DXWRAPPER(DirectDrawWrapperIds.DXWRAPPER_DD7TO9, "DxWrapper"),
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
