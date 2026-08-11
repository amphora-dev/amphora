package app.amphora.feature.launcher

import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.model.ContentComponent
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherStateEvaluatorTest {
    @Test
    fun matchingComponentsAndHealthyRuntimeAssetsAreReady() {
        val state =
            readyState(
                components = listOf(component(matchesPin = true)),
                runtimeAssets = listOf(runtimeAsset(RuntimeAssetStatus.State.OK)),
            )

        val health = LauncherStateEvaluator.contentHealth(state)

        assertTrue(health.healthy)
        assertEquals(0, health.unhealthyComponents)
        assertEquals(0, health.unhealthyRuntimeAssets)
        assertTrue(LauncherStateEvaluator.runtimeReady(state))
        assertTrue(LauncherStateEvaluator.canPrepareRuntime(state))
    }

    @Test
    fun pinMismatchMakesComponentAndRuntimeUnhealthy() {
        val state =
            readyState(
                components = listOf(component(matchesPin = false)),
                runtimeAssets = listOf(runtimeAsset(RuntimeAssetStatus.State.MISMATCH)),
            )

        val health = LauncherStateEvaluator.contentHealth(state)

        assertFalse(health.healthy)
        assertEquals(1, health.unhealthyComponents)
        assertEquals(1, health.unhealthyRuntimeAssets)
        assertFalse(LauncherStateEvaluator.runtimeReady(state))
        assertTrue(LauncherStateEvaluator.canPrepareRuntime(state))
    }

    @Test
    fun missingPinsAndInstalledComponentsAreUnhealthyEvenWhenMatchFlagIsSet() {
        val states =
            listOf(
                readyState(components = listOf(component(pinned = null, matchesPin = true))),
                readyState(components = listOf(component(installed = null, matchesPin = true))),
            )

        states.forEach { state ->
            assertEquals(1, LauncherStateEvaluator.contentHealth(state).unhealthyComponents)
            assertFalse(LauncherStateEvaluator.runtimeReady(state))
        }
    }

    @Test
    fun localRuntimeOverrideIsHealthyButMissingAndUnverifiedAssetsAreNot() {
        val local =
            readyState(
                components = listOf(component()),
                runtimeAssets = listOf(runtimeAsset(RuntimeAssetStatus.State.LOCAL_OVERRIDE)),
            )
        val unhealthy =
            readyState(
                components = listOf(component()),
                runtimeAssets =
                listOf(
                    runtimeAsset(RuntimeAssetStatus.State.MISSING),
                    runtimeAsset(RuntimeAssetStatus.State.UNVERIFIED),
                ),
            )

        assertTrue(LauncherStateEvaluator.runtimeReady(local))
        assertEquals(2, LauncherStateEvaluator.contentHealth(unhealthy).unhealthyRuntimeAssets)
        assertFalse(LauncherStateEvaluator.runtimeReady(unhealthy))
    }

    @Test
    fun imagefsResiduePreventsReadiness() {
        val state =
            readyState(
                components = listOf(component()),
                runtimeAssets = listOf(runtimeAsset()),
            ).copy(imagefsResidue = true)

        assertTrue(LauncherStateEvaluator.contentHealth(state).hasImagefsResidue)
        assertFalse(LauncherStateEvaluator.runtimeReady(state))
    }

    @Test
    fun everyBusyStatePreventsReadinessAndRuntimePreparation() {
        val base =
            readyState(
                components = listOf(component()),
                runtimeAssets = listOf(runtimeAsset()),
            )

        listOf(
            base.copy(contentBusy = true),
            base.copy(staging = true),
            base.copy(driverBusy = true),
        ).forEach { state ->
            assertFalse(LauncherStateEvaluator.runtimeReady(state))
            assertFalse(LauncherStateEvaluator.canPrepareRuntime(state))
        }
    }

    @Test
    fun catalogMustBeReadyEvenWhenContentIsHealthy() {
        val state =
            LauncherUiState(
                components = listOf(component()),
                runtimeAssets = listOf(runtimeAsset()),
                catalogStatus = ContentCatalog.Status.Idle,
            )

        assertTrue(LauncherStateEvaluator.contentHealth(state).healthy)
        assertFalse(LauncherStateEvaluator.runtimeReady(state))
        assertFalse(LauncherStateEvaluator.canPrepareRuntime(state))
    }

    @Test
    fun selectedProgramMustExistAndDesktopSelectionWins() {
        val program = RecentProgram("/games/valid.exe", "valid.exe", 42L)
        val state =
            LauncherUiState(
                stagedExePath = program.path,
                recentPrograms = listOf(program),
            )

        assertEquals(program, LauncherStateEvaluator.selectedProgram(state, desktopSelected = false))
        assertNull(LauncherStateEvaluator.selectedProgram(state, desktopSelected = true))
        assertNull(
            LauncherStateEvaluator.selectedProgram(
                state.copy(stagedExePath = "/games/missing.exe"),
                desktopSelected = false,
            ),
        )
    }

    @Test
    fun configurationUsesStableEnumOrderAndLabels() {
        val state =
            LauncherUiState(
                resolution = Resolution.R1920x1200,
                graphicsDriver = GraphicsDriverOption.SYSTEM,
                directDrawWrapper = DirectDrawWrapperOption.D7VK,
            )

        assertEquals(
            listOf(
                ConfigurationValue("Display", "1920×1200 · 16:10"),
                ConfigurationValue("Graphics", "Android system Vulkan · 2D/test"),
                ConfigurationValue("DirectDraw", "d7vk (D3D3–7)"),
            ),
            LauncherStateEvaluator.configuration(state),
        )
    }

    @Test
    fun configurationEnumsRoundTripAndRejectUnknownPreferences() {
        Resolution.entries.forEach { option ->
            assertSame(option, Resolution.fromPreference(option.name))
        }
        GraphicsDriverOption.entries.forEach { option ->
            assertSame(option, GraphicsDriverOption.fromDriverId(option.driverId))
        }
        DirectDrawWrapperOption.entries.forEach { option ->
            assertSame(option, DirectDrawWrapperOption.fromId(option.id))
        }

        assertSame(Resolution.DEFAULT, Resolution.fromPreference("not-a-resolution"))
        assertSame(GraphicsDriverOption.WRAPPER, GraphicsDriverOption.fromDriverId("not-a-driver"))
        assertSame(DirectDrawWrapperOption.DXWRAPPER, DirectDrawWrapperOption.fromId("not-a-wrapper"))
    }

    @Test
    fun onlyInitialDiskCacheLoadRequestsBackgroundRefresh() {
        assertTrue(
            LauncherStateEvaluator.shouldRefreshInBackground(
                forceRefresh = false,
                sourceUrl = "file:/data/content_manifest.json",
            ),
        )
        assertFalse(
            LauncherStateEvaluator.shouldRefreshInBackground(
                forceRefresh = true,
                sourceUrl = "file:/data/content_manifest.json",
            ),
        )
        assertFalse(
            LauncherStateEvaluator.shouldRefreshInBackground(
                forceRefresh = false,
                sourceUrl = "https://example.test/content_manifest.json",
            ),
        )
        assertFalse(
            LauncherStateEvaluator.shouldRefreshInBackground(
                forceRefresh = false,
                sourceUrl = null,
            ),
        )
    }

    private fun readyState(
        components: List<ComponentInstallStatus> = emptyList(),
        runtimeAssets: List<RuntimeAssetStatus> = emptyList(),
    ): LauncherUiState = LauncherUiState(
        catalogStatus =
        ContentCatalog.Status.Ready(
            manifest = mockk<ContentManifest>(),
            sourceUrl = "https://example.test/content_manifest.json",
        ),
        components = components,
        runtimeAssets = runtimeAssets,
    )

    private fun component(
        pinned: String? = "1.0",
        installed: String? = "1.0",
        matchesPin: Boolean = true,
    ): ComponentInstallStatus = ComponentInstallStatus(
        component = ContentComponent.WINE,
        pinned = pinned,
        installed = installed,
        matchesPin = matchesPin,
    )

    private fun runtimeAsset(state: RuntimeAssetStatus.State = RuntimeAssetStatus.State.OK): RuntimeAssetStatus =
        RuntimeAssetStatus(
            assetPath = "wine/component.bin",
            pinnedSha = "a".repeat(64),
            installedSha = "a".repeat(64),
            sizeBytes = 1L,
            state = state,
        )
}
