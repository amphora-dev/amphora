package app.amphora.feature.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ContentReconciler
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.content.ProvisionProgressBus
import app.amphora.core.engine.ContentHealthScanner
import app.amphora.core.engine.LaunchRuntimeSettings
import app.amphora.core.engine.RuntimeSettingsStore
import app.amphora.core.engine.TurnipDriverProvisioner
import app.amphora.core.engine.model.ContentComponentHealth
import app.amphora.core.engine.model.ContentHealthSnapshot
import app.amphora.core.engine.model.RuntimeAssetHealth
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Blocking and persisted launcher operations.
 *
 * The ViewModel owns coroutine lifetime and UI transitions; this boundary owns component
 * coordination and keeps Android/file/preference details out of those transitions.
 */
internal interface LauncherOperations {
    val appVersion: String
    val catalogStatus: StateFlow<ContentCatalog.Status>
    val provisionProgress: StateFlow<ProvisionProgress?>
    val runtimeSettings: StateFlow<LaunchRuntimeSettings>

    suspend fun loadContent(forceRefresh: Boolean): LoadedLauncherContent

    suspend fun listPrograms(): List<RecentProgram>

    suspend fun stageProgram(uri: Uri): String

    suspend fun markProgramLaunched(path: String): List<RecentProgram>

    suspend fun ensureTurnipInstalled()

    fun setResolutionName(value: String)

    fun setGraphicsDriverId(value: String)

    fun setDirectDrawWrapperId(value: String)
}

internal data class LoadedLauncherContent(val snapshot: ContentSnapshot, val sourceUrl: String?)

/** Production wiring for the focused launcher boundaries. */
internal class DefaultLauncherOperations
@Inject
constructor(
    @ApplicationContext context: Context,
    private val dispatchers: DispatcherProvider,
    private val turnipProvisioner: TurnipDriverProvisioner,
    private val catalog: ContentCatalog,
    private val contentReconciler: ContentReconciler,
    private val contentHealthScanner: ContentHealthScanner,
    private val programLibrary: LauncherProgramLibrary,
    private val settingsStore: RuntimeSettingsStore,
    progressBus: ProvisionProgressBus,
) : LauncherOperations {
    override val appVersion: String = readAppVersion(context)
    override val catalogStatus: StateFlow<ContentCatalog.Status> = catalog.status
    override val provisionProgress: StateFlow<ProvisionProgress?> = progressBus.progress
    override val runtimeSettings: StateFlow<LaunchRuntimeSettings> = settingsStore.settings

    override suspend fun loadContent(forceRefresh: Boolean): LoadedLauncherContent {
        val manifest = if (forceRefresh) catalog.refresh() else catalog.require()
        val sourceUrl = (catalog.status.value as? ContentCatalog.Status.Ready)?.sourceUrl
        val health =
            withContext(dispatchers.io) {
                contentReconciler.reconcile(manifest)
                contentHealthScanner.scan(manifest)
            }
        return LoadedLauncherContent(health.toLauncherSnapshot(), sourceUrl)
    }

    override suspend fun listPrograms(): List<RecentProgram> =
        withContext(dispatchers.io) { programLibrary.listRecent() }

    override suspend fun stageProgram(uri: Uri): String = withContext(dispatchers.io) { programLibrary.stage(uri) }

    override suspend fun markProgramLaunched(path: String): List<RecentProgram> = withContext(dispatchers.io) {
        programLibrary.markLaunched(path)
        programLibrary.listRecent()
    }

    override suspend fun ensureTurnipInstalled() {
        turnipProvisioner.ensureInstalled()
    }

    override fun setResolutionName(value: String) {
        settingsStore.setResolutionName(value)
    }

    override fun setGraphicsDriverId(value: String) {
        settingsStore.setGraphicsDriverId(value)
    }

    override fun setDirectDrawWrapperId(value: String) {
        settingsStore.setDirectDrawWrapperId(value)
    }
}

private fun ContentHealthSnapshot.toLauncherSnapshot(): ContentSnapshot = ContentSnapshot(
    components =
    components.map { health ->
        ComponentInstallStatus(
            component = health.component,
            pinned = health.pinned,
            installed = health.installed,
            matchesPin = health.state == ContentComponentHealth.State.READY,
        )
    },
    runtimeAssets =
    runtimeAssets.map { health ->
        RuntimeAssetStatus(
            assetPath = health.assetPath,
            pinnedSha = health.pinnedSha,
            installedSha = health.installedSha,
            sizeBytes = health.sizeBytes,
            state =
            when (health.state) {
                RuntimeAssetHealth.State.READY -> RuntimeAssetStatus.State.OK
                RuntimeAssetHealth.State.MISSING -> RuntimeAssetStatus.State.MISSING
                RuntimeAssetHealth.State.MISMATCH -> RuntimeAssetStatus.State.MISMATCH
                RuntimeAssetHealth.State.UNVERIFIED -> RuntimeAssetStatus.State.UNVERIFIED
                RuntimeAssetHealth.State.LOCAL_OVERRIDE -> RuntimeAssetStatus.State.LOCAL_OVERRIDE
            },
        )
    },
    imagefsResidue = imageFsResidue,
)

private fun readAppVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
} catch (_: PackageManager.NameNotFoundException) {
    "unknown"
}
