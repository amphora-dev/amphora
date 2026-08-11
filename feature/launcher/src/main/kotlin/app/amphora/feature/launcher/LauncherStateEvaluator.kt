package app.amphora.feature.launcher

import app.amphora.core.content.ContentCatalog

/** Pure launcher decisions shared by the ViewModel and Compose UI. */
internal object LauncherStateEvaluator {
    fun contentHealth(state: LauncherUiState): ContentHealth = ContentHealth(
        unhealthyComponents =
        state.components.count {
            it.pinned == null || it.installed == null || !it.matchesPin
        },
        unhealthyRuntimeAssets = state.runtimeAssets.count { !it.healthy },
        hasImagefsResidue = state.imagefsResidue,
    )

    fun runtimeReady(state: LauncherUiState): Boolean = state.catalogStatus is ContentCatalog.Status.Ready &&
        !state.contentBusy &&
        !state.staging &&
        !state.driverBusy &&
        contentHealth(state).healthy

    fun canPrepareRuntime(state: LauncherUiState): Boolean = state.catalogStatus is ContentCatalog.Status.Ready &&
        !state.contentBusy &&
        !state.staging &&
        !state.driverBusy

    fun selectedProgram(state: LauncherUiState, desktopSelected: Boolean): RecentProgram? =
        state.recentPrograms.firstOrNull {
            !desktopSelected && it.path == state.stagedExePath
        }

    fun configuration(state: LauncherUiState): List<ConfigurationValue> = listOf(
        ConfigurationValue("Display", state.resolution.label),
        ConfigurationValue("Graphics", state.graphicsDriver.label),
        ConfigurationValue("DirectDraw", state.directDrawWrapper.label),
    )

    fun shouldRefreshInBackground(forceRefresh: Boolean, sourceUrl: String?): Boolean =
        !forceRefresh && sourceUrl?.startsWith("file:") == true
}

internal data class ContentHealth(
    val unhealthyComponents: Int,
    val unhealthyRuntimeAssets: Int,
    val hasImagefsResidue: Boolean,
) {
    val healthy: Boolean
        get() =
            unhealthyComponents == 0 &&
                unhealthyRuntimeAssets == 0 &&
                !hasImagefsResidue
}

internal data class ConfigurationValue(val label: String, val value: String)

internal enum class ContentRefreshMode {
    BLOCKING,
    BACKGROUND,
}

internal data class ContentSnapshot(
    val components: List<ComponentInstallStatus>,
    val runtimeAssets: List<RuntimeAssetStatus>,
    val imagefsResidue: Boolean,
)

/** Pure state transitions for actions whose Android work is performed by [LauncherViewModel]. */
internal object LauncherStateReducer {
    fun contentRefreshStarted(state: LauncherUiState, mode: ContentRefreshMode): LauncherUiState = when (mode) {
        ContentRefreshMode.BLOCKING -> state.copy(contentBusy = true, stageError = null)
        ContentRefreshMode.BACKGROUND -> state
    }

    fun contentRefreshSucceeded(
        state: LauncherUiState,
        mode: ContentRefreshMode,
        snapshot: ContentSnapshot,
    ): LauncherUiState = state.copy(
        contentBusy =
        when (mode) {
            ContentRefreshMode.BLOCKING -> false
            ContentRefreshMode.BACKGROUND -> state.contentBusy
        },
        components = snapshot.components,
        runtimeAssets = snapshot.runtimeAssets,
        imagefsResidue = snapshot.imagefsResidue,
    )

    fun contentRefreshFailed(state: LauncherUiState, mode: ContentRefreshMode, error: Throwable): LauncherUiState =
        when (mode) {
            ContentRefreshMode.BLOCKING ->
                state.copy(
                    contentBusy = false,
                    stageError = "Manifest: ${error.message ?: error.javaClass.simpleName}",
                )
            ContentRefreshMode.BACKGROUND -> state
        }

    fun selectProgram(state: LauncherUiState, path: String): LauncherUiState =
        if (state.recentPrograms.any { it.path == path }) {
            state.copy(stagedExePath = path)
        } else {
            state
        }
}
