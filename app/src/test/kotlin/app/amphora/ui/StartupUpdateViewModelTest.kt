package app.amphora.ui

import app.amphora.core.content.update.AppUpdateCheckResult
import app.amphora.core.content.update.AppUpdateManifest
import app.amphora.core.engine.ShizukuCleanupStatus
import app.amphora.core.engine.update.AppUpdateInstallResult
import app.amphora.core.engine.update.AppUpdateManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupUpdateViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun developmentBuildSkipsStartupCheck() = runViewModelTest {
        val fixture = fixture(shouldCheck = false)

        val viewModel = StartupUpdateViewModel(fixture.manager)
        advanceUntilIdle()

        assertNull(viewModel.state.value.available)
        coVerify(exactly = 0) { fixture.manager.check() }
    }

    @Test
    fun availableUpdateIsExposedAfterStartupCheck() = runViewModelTest {
        val fixture =
            fixture(
                checkResult = AppUpdateCheckResult.UpdateAvailable(20_000_000, MANIFEST),
            )

        val viewModel = StartupUpdateViewModel(fixture.manager)
        advanceUntilIdle()

        assertEquals(MANIFEST, viewModel.state.value.available)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun nonUpdateStartupResultsRemainSilent() = runViewModelTest {
        val results =
            listOf(
                AppUpdateCheckResult.UpToDate(MANIFEST.versionCode.toLong(), MANIFEST),
                AppUpdateCheckResult.Unavailable("offline"),
                AppUpdateCheckResult.Failed(IllegalStateException("network failed")),
            )

        results.forEach { result ->
            val viewModel = StartupUpdateViewModel(fixture(checkResult = result).manager)
            runCurrent()

            assertEquals(StartupUpdateState(), viewModel.state.value)
        }
    }

    @Test
    fun startedInstallKeepsDialogBusyUntilAppRestarts() = runViewModelTest {
        val fixture =
            fixture(
                checkResult = AppUpdateCheckResult.UpdateAvailable(20_000_000, MANIFEST),
                installResult = AppUpdateInstallResult.Started,
            )
        val viewModel = StartupUpdateViewModel(fixture.manager)
        advanceUntilIdle()

        viewModel.installUpdate()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.busy)
        assertTrue(viewModel.state.value.message.orEmpty().contains("reopen"))
        coVerify(exactly = 1) { fixture.manager.downloadAndInstall(MANIFEST) }
    }

    @Test
    fun systemInstallerFallbackPublishesApkAndClearsBusy() = runViewModelTest {
        val apk = temporaryFolder.newFile("fallback.apk")
        val fixture =
            fixture(
                checkResult = AppUpdateCheckResult.UpdateAvailable(20_000_000, MANIFEST),
                installResult =
                AppUpdateInstallResult.SystemInstallerRequired(
                    apk,
                    "Automatic install unavailable",
                ),
            )
        val viewModel = StartupUpdateViewModel(fixture.manager)
        advanceUntilIdle()

        viewModel.installUpdate()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.busy)
        assertSame(apk, viewModel.state.value.pendingSystemApk)
        assertEquals("Automatic install unavailable", viewModel.state.value.message)
    }

    @Test
    fun shizukuReadyCompletesPendingPermissionFlow() = runViewModelTest {
        val fixture =
            fixture(
                initialStatus = ShizukuCleanupStatus.PERMISSION_REQUIRED,
                checkResult = AppUpdateCheckResult.UpdateAvailable(20_000_000, MANIFEST),
                installResult = AppUpdateInstallResult.Started,
            )
        every { fixture.manager.requestInstallPermission() } returns true
        val viewModel = StartupUpdateViewModel(fixture.manager)
        advanceUntilIdle()

        viewModel.installUpdate()
        runCurrent()
        assertTrue(viewModel.state.value.waitingForShizukuPermission)

        fixture.status.value = ShizukuCleanupStatus.READY
        advanceUntilIdle()

        assertFalse(viewModel.state.value.waitingForShizukuPermission)
        coVerify(exactly = 1) { fixture.manager.downloadAndInstall(MANIFEST) }
    }

    @Test
    fun shizukuPermissionTimeoutUsesFallbackInstall() = runViewModelTest {
        val fixture =
            fixture(
                initialStatus = ShizukuCleanupStatus.PERMISSION_REQUIRED,
                checkResult = AppUpdateCheckResult.UpdateAvailable(20_000_000, MANIFEST),
                installResult = AppUpdateInstallResult.Started,
            )
        every { fixture.manager.requestInstallPermission() } returns true
        val viewModel = StartupUpdateViewModel(fixture.manager)
        advanceUntilIdle()

        viewModel.installUpdate()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertFalse(viewModel.state.value.waitingForShizukuPermission)
        coVerify(exactly = 1) { fixture.manager.downloadAndInstall(MANIFEST) }
    }

    @Test
    fun dismissIsBlockedDuringDownloadAndAllowedWhenIdle() = runViewModelTest {
        val result = CompletableDeferred<AppUpdateInstallResult>()
        val fixture =
            fixture(
                checkResult = AppUpdateCheckResult.UpdateAvailable(20_000_000, MANIFEST),
            )
        coEvery { fixture.manager.downloadAndInstall(MANIFEST) } coAnswers { result.await() }
        val viewModel = StartupUpdateViewModel(fixture.manager)
        advanceUntilIdle()

        viewModel.installUpdate()
        runCurrent()
        viewModel.dismiss()
        assertFalse(viewModel.state.value.dismissed)

        result.complete(AppUpdateInstallResult.SystemInstallerRequired(temporaryFolder.newFile(), "fallback"))
        advanceUntilIdle()
        viewModel.dismiss()
        assertTrue(viewModel.state.value.dismissed)
    }

    private fun fixture(
        shouldCheck: Boolean = true,
        initialStatus: ShizukuCleanupStatus = ShizukuCleanupStatus.READY,
        checkResult: AppUpdateCheckResult = AppUpdateCheckResult.UpToDate(MANIFEST.versionCode.toLong(), MANIFEST),
        installResult: AppUpdateInstallResult = AppUpdateInstallResult.Started,
    ): Fixture {
        val manager = mockk<AppUpdateManager>()
        val status = MutableStateFlow(initialStatus)
        every { manager.installStatus } returns status
        every { manager.shouldCheckAtStartup() } returns shouldCheck
        every { manager.requestInstallPermission() } returns false
        coEvery { manager.check() } returns checkResult
        coEvery { manager.downloadAndInstall(MANIFEST) } returns installResult
        return Fixture(manager, status)
    }

    private fun runViewModelTest(block: suspend TestScope.() -> Unit): Unit = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private data class Fixture(val manager: AppUpdateManager, val status: MutableStateFlow<ShizukuCleanupStatus>)

    private companion object {
        val MANIFEST =
            AppUpdateManifest(
                versionCode = 20_000_001,
                versionName = "1.2.3",
                apkUrl = "https://example.test/amphora.apk",
                sha256 = "b".repeat(64),
                size = 123,
            )
    }
}
