package app.amphora.feature.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.ContentAssetInstaller
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.content.ProvisionProgressBus
import app.amphora.core.content.RuntimeAssetLocalOverride
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.engine.GraphicsDriverIds
import app.amphora.core.engine.TurnipDriverProvisioner
import app.amphora.core.rootfs.RootfsInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher state for the MVP "pick .exe -> choose resolution -> launch" flow
 * (RFC §3 v0.1 / §6). Surfaces remote content pins vs installed artifacts and
 * live download progress from [ProvisionProgressBus].
 */
@HiltViewModel
class LauncherViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val turnipProvisioner: TurnipDriverProvisioner,
    private val catalog: ContentCatalog,
    private val rootfsInstaller: RootfsInstaller,
    private val assetInstaller: ContentAssetInstaller,
    progressBus: ProvisionProgressBus,
) : ViewModel() {
    private val prefs =
        context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState =
        MutableStateFlow(
            LauncherUiState(
                appVersion = readAppVersion(),
                graphicsDriver =
                GraphicsDriverOption.fromDriverId(
                    prefs.getString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, null),
                ),
            ),
        )
    val uiState: StateFlow<LauncherUiState> =
        combine(
            _uiState,
            catalog.status,
            progressBus.progress,
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
        refreshContentInfo()
    }

    fun refreshContentInfo() {
        viewModelScope.launch {
            _uiState.update { it.copy(contentBusy = true, stageError = null) }
            try {
                val manifest = catalog.refresh()
                val components = withContext(dispatchers.io) { scanComponents(manifest) }
                val runtimeAssets = withContext(dispatchers.io) { scanRuntimeAssets(manifest) }
                val residue =
                    withContext(dispatchers.io) {
                        File(context.filesDir, "imagefs.olddead").exists()
                    }
                _uiState.update {
                    it.copy(
                        contentBusy = false,
                        components = components,
                        runtimeAssets = runtimeAssets,
                        imagefsResidue = residue,
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
                prefs
                    .edit()
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

    /** Compare each remote pin against what is actually usable on disk. */
    private suspend fun scanComponents(manifest: ContentManifest): List<ComponentInstallStatus> =
        ContentComponent.entries.map { component ->
            val entry = manifest.entry(component)
            val pin = entry?.pinLabel()
            val installed =
                when {
                    component == ContentComponent.ROOTFS -> rootfsInstaller.currentVersion()
                    else -> installedLabel(entry)
                }
            val matches =
                when {
                    entry == null || pin == null -> false
                    installed == null -> false
                    component == ContentComponent.ROOTFS -> installed == pin
                    else -> assetInstaller.isInstalled(entry)
                }
            ComponentInstallStatus(
                component = component,
                pinned = pin,
                installed = installed,
                matchesPin = matches,
                localOverride = false,
            )
        }

    /** SHA-256 pinned by a dev/test inject, or null when no override is armed. */
    private fun localOverrideSha(file: File): String? {
        if (!RuntimeAssetLocalOverride.isActive(file)) return null
        return RuntimeAssetLocalOverride.markerFile(file).readText().trim()
    }

    /**
     * `runtimeAssets[]` is the bulk of the manifest (wincomponents, ddrawrapper,
     * metadata, …) and is provisioned separately from [ContentComponent], so the
     * component list alone hides most of what a device actually holds.
     */
    private fun scanRuntimeAssets(manifest: ContentManifest): List<RuntimeAssetStatus> {
        val root = RuntimeAssetProvisioner.runtimeAssetsDir(context)
        return manifest.runtimeAssets().map { entry ->
            val file = File(root, entry.assetPath)
            val override = localOverrideSha(file)
            val onDisk =
                File(file.absolutePath + ".sha256")
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.trim()
                    ?.lowercase()
            val state =
                when {
                    override != null -> RuntimeAssetStatus.State.LOCAL_OVERRIDE
                    !file.isFile -> RuntimeAssetStatus.State.MISSING
                    onDisk == null -> RuntimeAssetStatus.State.UNVERIFIED
                    onDisk != entry.sha256.lowercase() -> RuntimeAssetStatus.State.MISMATCH
                    entry.size != null && file.length() != entry.size ->
                        RuntimeAssetStatus.State.MISMATCH
                    else -> RuntimeAssetStatus.State.OK
                }
            RuntimeAssetStatus(
                assetPath = entry.assetPath,
                pinnedSha = entry.sha256.lowercase(),
                installedSha = override ?: onDisk,
                sizeBytes = entry.size,
                state = state,
            )
        }
    }

    private fun installedLabel(entry: ManifestEntry?): String? {
        if (entry == null) return null
        if (assetInstaller.isInstalled(entry)) return entry.pinLabel()
        return when (entry.kind) {
            ManifestEntry.Kind.WCP -> {
                val type = entry.contentType ?: return null
                val dir = File(context.filesDir, "contents/$type")
                dir
                    .listFiles()
                    ?.filter { it.isDirectory }
                    ?.map { it.name }
                    ?.sorted()
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ")
            }
            // An ARCHIVE component that is not installed has nothing else to show;
            // isInstalled() above already covered the extracted case.
            ManifestEntry.Kind.ARCHIVE -> null
            ManifestEntry.Kind.ROOTFS -> null
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
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    private fun readAppVersion(): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "unknown"
    } catch (_: PackageManager.NameNotFoundException) {
        "unknown"
    }
}

private fun ManifestEntry.pinLabel(): String = verName ?: version

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
    val staging: Boolean = false,
    val stageError: String? = null,
    val resolution: Resolution = Resolution.DEFAULT,
    val graphicsDriver: GraphicsDriverOption = GraphicsDriverOption.WRAPPER,
    val driverBusy: Boolean = false,
    val contentBusy: Boolean = false,
    val catalogStatus: ContentCatalog.Status = ContentCatalog.Status.Idle,
    val components: List<ComponentInstallStatus> = emptyList(),
    val runtimeAssets: List<RuntimeAssetStatus> = emptyList(),
    val imagefsResidue: Boolean = false,
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
    R800x600(800, 600, "800×600"),
    ;

    companion object {
        val DEFAULT = R1280x720
    }
}
