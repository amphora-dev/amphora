package app.amphora.feature.launcher

import app.amphora.core.content.model.ContentComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherStateReducerTest {
    @Test
    fun blockingRefreshSetsBusyAndClearsPreviousError() {
        val state = LauncherUiState(stageError = "old failure")

        val loading =
            LauncherStateReducer.contentRefreshStarted(
                state,
                ContentRefreshMode.BLOCKING,
            )

        assertTrue(loading.contentBusy)
        assertEquals(null, loading.stageError)
    }

    @Test
    fun blockingRefreshSuccessPublishesSnapshotAndClearsBusy() {
        val snapshot = snapshot(componentVersion = "2.0")
        val loading = LauncherUiState(contentBusy = true)

        val result =
            LauncherStateReducer.contentRefreshSucceeded(
                loading,
                ContentRefreshMode.BLOCKING,
                snapshot,
            )

        assertFalse(result.contentBusy)
        assertEquals(snapshot.components, result.components)
        assertEquals(snapshot.runtimeAssets, result.runtimeAssets)
        assertEquals(snapshot.imagefsResidue, result.imagefsResidue)
    }

    @Test
    fun blockingRefreshFailureClearsBusyAndReportsManifestError() {
        val loading = LauncherUiState(contentBusy = true)

        val result =
            LauncherStateReducer.contentRefreshFailed(
                loading,
                ContentRefreshMode.BLOCKING,
                IllegalStateException("network down"),
            )

        assertFalse(result.contentBusy)
        assertEquals("Manifest: network down", result.stageError)
    }

    @Test
    fun backgroundRefreshSuccessUpdatesContentWithoutBlockingUi() {
        val cached = snapshot(componentVersion = "1.0")
        val refreshed = snapshot(componentVersion = "2.0")
        val launchable =
            LauncherUiState(
                contentBusy = false,
                components = cached.components,
                runtimeAssets = cached.runtimeAssets,
                imagefsResidue = cached.imagefsResidue,
            )

        val started =
            LauncherStateReducer.contentRefreshStarted(
                launchable,
                ContentRefreshMode.BACKGROUND,
            )
        val result =
            LauncherStateReducer.contentRefreshSucceeded(
                started,
                ContentRefreshMode.BACKGROUND,
                refreshed,
            )

        assertSame(launchable, started)
        assertFalse(result.contentBusy)
        assertEquals(refreshed.components, result.components)
        assertEquals(refreshed.runtimeAssets, result.runtimeAssets)
    }

    @Test
    fun backgroundRefreshFailureLeavesLaunchableCachedStateUntouched() {
        val cached = snapshot(componentVersion = "1.0")
        val launchable =
            LauncherUiState(
                stageError = null,
                contentBusy = false,
                components = cached.components,
                runtimeAssets = cached.runtimeAssets,
            )

        val result =
            LauncherStateReducer.contentRefreshFailed(
                launchable,
                ContentRefreshMode.BACKGROUND,
                IllegalStateException("offline"),
            )

        assertSame(launchable, result)
        assertFalse(result.contentBusy)
        assertEquals(null, result.stageError)
    }

    @Test
    fun validProgramSelectionChangesPath() {
        val selected = RecentProgram("/games/selected.exe", "selected.exe", 1L)
        val state =
            LauncherUiState(
                stagedExePath = "/games/old.exe",
                recentPrograms =
                listOf(
                    RecentProgram("/games/old.exe", "old.exe", 2L),
                    selected,
                ),
            )

        val result = LauncherStateReducer.selectProgram(state, selected.path)

        assertEquals(selected.path, result.stagedExePath)
    }

    @Test
    fun invalidProgramSelectionCannotInjectArbitraryPath() {
        val state =
            LauncherUiState(
                stagedExePath = "/games/selected.exe",
                recentPrograms =
                listOf(
                    RecentProgram("/games/selected.exe", "selected.exe", 1L),
                ),
            )

        val result = LauncherStateReducer.selectProgram(state, "/outside/untrusted.exe")

        assertSame(state, result)
        assertEquals("/games/selected.exe", result.stagedExePath)
    }

    private fun snapshot(componentVersion: String): ContentSnapshot = ContentSnapshot(
        components =
        listOf(
            ComponentInstallStatus(
                component = ContentComponent.WINE,
                pinned = componentVersion,
                installed = componentVersion,
                matchesPin = true,
            ),
        ),
        runtimeAssets =
        listOf(
            RuntimeAssetStatus(
                assetPath = "wine/component.bin",
                pinnedSha = componentVersion,
                installedSha = componentVersion,
                sizeBytes = 1L,
                state = RuntimeAssetStatus.State.OK,
            ),
        ),
        imagefsResidue = false,
    )
}
