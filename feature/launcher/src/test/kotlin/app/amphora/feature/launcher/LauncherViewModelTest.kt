package app.amphora.feature.launcher

import android.net.Uri
import app.amphora.core.common.testing.MainDispatcherRule
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.engine.GraphicsDriverIds
import app.amphora.core.engine.LaunchRuntimeSettings
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {
    @get:Rule
    val dispatchers = MainDispatcherRule()

    @Test
    fun `startup publishes cached health then refreshes file catalog in background`() =
        runTest(dispatchers.testDispatcher) {
            val cached = snapshot(componentInstalled = "cached")
            val refreshed = snapshot(componentInstalled = "refreshed")
            val operations =
                FakeLauncherOperations(
                    contentLoads =
                    ArrayDeque(
                        listOf(
                            LoadedLauncherContent(cached, "file:/cached/content_manifest.json"),
                            LoadedLauncherContent(refreshed, "https://example.test/content_manifest.json"),
                        ),
                    ),
                )

            val viewModel = LauncherViewModel(operations)
            val collection = collectState(viewModel)
            advanceUntilIdle()

            assertEquals(listOf(false, true), operations.contentRefreshes)
            assertEquals("refreshed", viewModel.uiState.value.components.single().installed)
            assertFalse(viewModel.uiState.value.contentBusy)
            collection.cancel()
        }

    @Test
    fun `program actions stage list select and mark through the library boundary`() =
        runTest(dispatchers.testDispatcher) {
            val operations = FakeLauncherOperations()
            val viewModel = LauncherViewModel(operations)
            val collection = collectState(viewModel)
            advanceUntilIdle()
            val pickedUri = mockk<Uri>(relaxed = true)
            val staged = RecentProgram("/programs/game.exe", "game.exe", 10L)
            operations.stagedPath = staged.path
            operations.programs = listOf(staged)

            viewModel.onExePicked(pickedUri)
            advanceUntilIdle()
            viewModel.selectProgram(staged.path)
            viewModel.markProgramLaunched()
            advanceUntilIdle()

            assertEquals(listOf(pickedUri), operations.stagedUris)
            assertEquals(listOf(staged.path), operations.markedPaths)
            assertEquals(staged.path, viewModel.uiState.value.stagedExePath)
            assertEquals(listOf(staged), viewModel.uiState.value.recentPrograms)
            assertFalse(viewModel.uiState.value.staging)
            assertNull(viewModel.uiState.value.stageError)
            collection.cancel()
        }

    @Test
    fun `runtime settings stay synchronized and turnip persists only after installation`() =
        runTest(dispatchers.testDispatcher) {
            val operations =
                FakeLauncherOperations(
                    initialSettings =
                    LaunchRuntimeSettings(
                        resolutionName = Resolution.R1920x1080.name,
                        graphicsDriverId = GraphicsDriverIds.SYSTEM,
                    ),
                )
            val viewModel = LauncherViewModel(operations)
            val collection = collectState(viewModel)
            advanceUntilIdle()

            assertEquals(Resolution.R1920x1080, viewModel.uiState.value.resolution)
            assertEquals(GraphicsDriverOption.SYSTEM, viewModel.uiState.value.graphicsDriver)

            operations.mutableRuntimeSettings.value =
                LaunchRuntimeSettings(
                    resolutionName = Resolution.R800x600.name,
                    graphicsDriverId = GraphicsDriverIds.WRAPPER,
                )
            advanceUntilIdle()
            viewModel.selectResolution(Resolution.R1280x800)
            viewModel.selectDirectDrawWrapper(DirectDrawWrapperOption.D7VK)
            viewModel.selectGraphicsDriver(GraphicsDriverOption.TURNIP_BALANCED)
            advanceUntilIdle()

            assertEquals(Resolution.R1280x800.name, operations.resolutionWrites.single())
            assertEquals(DirectDrawWrapperOption.D7VK.id, operations.wrapperWrites.single())
            assertEquals(1, operations.turnipInstallations)
            assertEquals(
                GraphicsDriverOption.TURNIP_BALANCED.driverId,
                operations.graphicsWrites.single(),
            )
            assertEquals(GraphicsDriverOption.TURNIP_BALANCED, viewModel.uiState.value.graphicsDriver)
            assertFalse(viewModel.uiState.value.driverBusy)
            collection.cancel()
        }

    @Test
    fun `turnip failure preserves the selected driver and reports the existing error`() =
        runTest(dispatchers.testDispatcher) {
            val operations =
                FakeLauncherOperations(
                    initialSettings =
                    LaunchRuntimeSettings(graphicsDriverId = GraphicsDriverIds.SYSTEM),
                )
            operations.turnipFailure = IllegalStateException("offline")
            val viewModel = LauncherViewModel(operations)
            val collection = collectState(viewModel)
            advanceUntilIdle()

            viewModel.selectGraphicsDriver(GraphicsDriverOption.TURNIP_BALANCED)
            advanceUntilIdle()

            assertTrue(operations.graphicsWrites.isEmpty())
            assertEquals(GraphicsDriverOption.SYSTEM, viewModel.uiState.value.graphicsDriver)
            assertEquals("Turnip install failed: offline", viewModel.uiState.value.stageError)
            assertFalse(viewModel.uiState.value.driverBusy)
            collection.cancel()
        }

    private fun TestScope.collectState(viewModel: LauncherViewModel): Job =
        backgroundScope.launch(dispatchers.testDispatcher) { viewModel.uiState.collect {} }

    private fun snapshot(componentInstalled: String = "ready") = ContentSnapshot(
        components =
        listOf(
            ComponentInstallStatus(
                component = ContentComponent.ROOTFS,
                pinned = "pin",
                installed = componentInstalled,
                matchesPin = true,
            ),
        ),
        runtimeAssets =
        listOf(
            RuntimeAssetStatus(
                assetPath = "asset",
                pinnedSha = "sha",
                installedSha = "sha",
                sizeBytes = 1,
                state = RuntimeAssetStatus.State.OK,
            ),
        ),
        imagefsResidue = false,
    )
}

private class FakeLauncherOperations(
    initialSettings: LaunchRuntimeSettings = LaunchRuntimeSettings(),
    private val contentLoads: ArrayDeque<LoadedLauncherContent> =
        ArrayDeque(listOf(LoadedLauncherContent(ContentSnapshot(emptyList(), emptyList(), false), null))),
) : LauncherOperations {
    override val appVersion: String = "test-version"
    override val catalogStatus: StateFlow<ContentCatalog.Status> =
        MutableStateFlow(ContentCatalog.Status.Idle)
    override val provisionProgress: StateFlow<ProvisionProgress?> = MutableStateFlow(null)
    val mutableRuntimeSettings = MutableStateFlow(initialSettings)
    override val runtimeSettings: StateFlow<LaunchRuntimeSettings> = mutableRuntimeSettings

    val contentRefreshes = mutableListOf<Boolean>()
    val stagedUris = mutableListOf<Uri>()
    val markedPaths = mutableListOf<String>()
    val resolutionWrites = mutableListOf<String>()
    val graphicsWrites = mutableListOf<String>()
    val wrapperWrites = mutableListOf<String>()
    var programs: List<RecentProgram> = emptyList()
    var stagedPath: String = "/programs/staged.exe"
    var turnipInstallations: Int = 0
    var turnipFailure: Throwable? = null

    override suspend fun loadContent(forceRefresh: Boolean): LoadedLauncherContent {
        contentRefreshes += forceRefresh
        return contentLoads.removeFirst()
    }

    override suspend fun listPrograms(): List<RecentProgram> = programs

    override suspend fun stageProgram(uri: Uri): String {
        stagedUris += uri
        return stagedPath
    }

    override suspend fun markProgramLaunched(path: String): List<RecentProgram> {
        markedPaths += path
        return programs
    }

    override suspend fun ensureTurnipInstalled() {
        turnipInstallations += 1
        turnipFailure?.let { throw it }
    }

    override fun setResolutionName(value: String) {
        resolutionWrites += value
    }

    override fun setGraphicsDriverId(value: String) {
        graphicsWrites += value
    }

    override fun setDirectDrawWrapperId(value: String) {
        wrapperWrites += value
    }
}
