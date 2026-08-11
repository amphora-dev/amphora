package app.amphora.feature.settings

import android.content.Intent
import app.amphora.core.content.update.AppUpdateCheckResult
import app.amphora.core.content.update.AppUpdateManifest
import app.amphora.core.engine.ShizukuCleanupStatus
import app.amphora.core.engine.update.AppUpdateInstallResult
import app.amphora.core.engine.update.AppUpdateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsUpdateControllerTest {
    @Test
    fun versionStatusAndIntentOperationsDelegateToManager() {
        val manager = mockk<AppUpdateManager>()
        val status = MutableStateFlow(ShizukuCleanupStatus.PERMISSION_REQUIRED)
        val permissionIntent = mockk<Intent>()
        val installerIntent = mockk<Intent>()
        val apk = File("fallback.apk")
        every { manager.installedVersionCode() } returns 20_000_000
        every { manager.installedVersionName() } returns "1.0.0"
        every { manager.installStatus } returns status
        every { manager.needsSystemInstallPermission() } returns true
        every { manager.installPermissionSettingsIntent() } returns permissionIntent
        every { manager.systemInstallerIntent(apk) } returns installerIntent
        val controller = SettingsUpdateController(manager)

        assertEquals(20_000_000, controller.installedVersionCode())
        assertEquals("1.0.0", controller.installedVersionName())
        assertSame(status, controller.installStatus)
        assertTrue(controller.needsSystemInstallPermission())
        assertSame(permissionIntent, controller.installPermissionSettingsIntent())
        assertSame(installerIntent, controller.systemInstallerIntent(apk))
    }

    @Test
    fun upToDateCheckMapsRemoteVersion() = runTest {
        val controller =
            controllerWithCheckResult(
                AppUpdateCheckResult.UpToDate(20_000_001, MANIFEST),
            )

        assertEquals(
            SettingsUpdateEvent.CheckCompleted(UpdateCheckOutcome.UpToDate(VERSION_NAME)),
            controller.execute(SettingsUpdateEffect.CheckForUpdate),
        )
    }

    @Test
    fun availableCheckMapsManifestAndVersionDetails() = runTest {
        val controller =
            controllerWithCheckResult(
                AppUpdateCheckResult.UpdateAvailable(20_000_000, MANIFEST),
            )

        assertEquals(
            SettingsUpdateEvent.CheckCompleted(
                UpdateCheckOutcome.UpdateAvailable(
                    update = MANIFEST,
                    installedVersionCode = 20_000_000,
                    remoteVersionCode = 20_000_001,
                    remoteVersionName = VERSION_NAME,
                ),
            ),
            controller.execute(SettingsUpdateEffect.CheckForUpdate),
        )
    }

    @Test
    fun unavailableCheckMapsReason() = runTest {
        val controller =
            controllerWithCheckResult(
                AppUpdateCheckResult.Unavailable("update URL not configured"),
            )

        assertEquals(
            SettingsUpdateEvent.CheckCompleted(
                UpdateCheckOutcome.Unavailable("update URL not configured"),
            ),
            controller.execute(SettingsUpdateEffect.CheckForUpdate),
        )
    }

    @Test
    fun failedCheckMapsErrorMessage() = runTest {
        val controller =
            controllerWithCheckResult(
                AppUpdateCheckResult.Failed(IllegalStateException("network failed")),
            )

        assertEquals(
            SettingsUpdateEvent.CheckCompleted(UpdateCheckOutcome.Failed("network failed")),
            controller.execute(SettingsUpdateEffect.CheckForUpdate),
        )
    }

    @Test
    fun failedCheckFallsBackToErrorDescription() = runTest {
        val failure = Throwable()
        val controller = controllerWithCheckResult(AppUpdateCheckResult.Failed(failure))

        assertEquals(
            SettingsUpdateEvent.CheckCompleted(UpdateCheckOutcome.Failed(failure.toString())),
            controller.execute(SettingsUpdateEffect.CheckForUpdate),
        )
    }

    @Test
    fun checkCancellationPropagates() = runTest {
        val cancellation = CancellationException("cancel check")
        val manager = mockk<AppUpdateManager>()
        coEvery { manager.check() } throws cancellation

        assertCancellationPropagates(cancellation) {
            SettingsUpdateController(manager).execute(SettingsUpdateEffect.CheckForUpdate)
        }
    }

    @Test
    fun permissionRequestReportsStartedRequestAndCurrentReadyStatus() = runTest {
        val manager = mockk<AppUpdateManager>()
        every { manager.requestInstallPermission() } returns true
        every { manager.installStatus } returns MutableStateFlow(ShizukuCleanupStatus.READY)

        assertEquals(
            SettingsUpdateEvent.PermissionRequestCompleted(
                requestStarted = true,
                permissionReady = true,
            ),
            SettingsUpdateController(manager).execute(SettingsUpdateEffect.RequestPermission),
        )
    }

    @Test
    fun permissionRequestReportsFailureAndNonReadyStatus() = runTest {
        val manager = mockk<AppUpdateManager>()
        every { manager.requestInstallPermission() } returns false
        every { manager.installStatus } returns
            MutableStateFlow(ShizukuCleanupStatus.PERMISSION_REQUIRED)

        assertEquals(
            SettingsUpdateEvent.PermissionRequestCompleted(
                requestStarted = false,
                permissionReady = false,
            ),
            SettingsUpdateController(manager).execute(SettingsUpdateEffect.RequestPermission),
        )
    }

    @Test
    fun permissionTimeoutReturnsEventAfterDelay() = runTest {
        val event =
            async {
                SettingsUpdateController(mockk()).execute(
                    SettingsUpdateEffect.SchedulePermissionTimeout,
                )
            }
        runCurrent()

        advanceTimeBy(9_999)
        runCurrent()
        assertFalse(event.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(SettingsUpdateEvent.PermissionWaitExpired, event.await())
    }

    @Test
    fun startedInstallMapsToInstallStarted() = runTest {
        val manager = mockk<AppUpdateManager>()
        coEvery { manager.downloadAndInstall(MANIFEST) } returns AppUpdateInstallResult.Started

        assertEquals(
            SettingsUpdateEvent.InstallStarted,
            SettingsUpdateController(manager).execute(
                SettingsUpdateEffect.DownloadAndInstall(MANIFEST),
            ),
        )
    }

    @Test
    fun systemInstallerFallbackMapsArtifactAndReason() = runTest {
        val manager = mockk<AppUpdateManager>()
        val apk = File("fallback.apk")
        coEvery { manager.downloadAndInstall(MANIFEST) } returns
            AppUpdateInstallResult.SystemInstallerRequired(apk, "Use system installer")

        assertEquals(
            SettingsUpdateEvent.SystemInstallerRequired(
                artifact = apk,
                reason = "Use system installer",
            ),
            SettingsUpdateController(manager).execute(
                SettingsUpdateEffect.DownloadAndInstall(MANIFEST),
            ),
        )
    }

    @Test
    fun installFailureMapsErrorMessage() = runTest {
        val manager = mockk<AppUpdateManager>()
        coEvery { manager.downloadAndInstall(MANIFEST) } throws
            IllegalStateException("signature mismatch")

        assertEquals(
            SettingsUpdateEvent.InstallFailed("signature mismatch"),
            SettingsUpdateController(manager).execute(
                SettingsUpdateEffect.DownloadAndInstall(MANIFEST),
            ),
        )
    }

    @Test
    fun installFailureFallsBackToErrorDescription() = runTest {
        val failure = Throwable()
        val manager = mockk<AppUpdateManager>()
        coEvery { manager.downloadAndInstall(MANIFEST) } throws failure

        assertEquals(
            SettingsUpdateEvent.InstallFailed(failure.toString()),
            SettingsUpdateController(manager).execute(
                SettingsUpdateEffect.DownloadAndInstall(MANIFEST),
            ),
        )
    }

    @Test
    fun installCancellationPropagates() = runTest {
        val cancellation = CancellationException("cancel install")
        val manager = mockk<AppUpdateManager>()
        coEvery { manager.downloadAndInstall(MANIFEST) } throws cancellation

        assertCancellationPropagates(cancellation) {
            SettingsUpdateController(manager).execute(
                SettingsUpdateEffect.DownloadAndInstall(MANIFEST),
            )
        }
    }

    private fun controllerWithCheckResult(result: AppUpdateCheckResult): SettingsUpdateController {
        val manager = mockk<AppUpdateManager>()
        coEvery { manager.check() } returns result
        return SettingsUpdateController(manager)
    }

    private suspend fun assertCancellationPropagates(expected: CancellationException, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected CancellationException")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    private companion object {
        const val VERSION_NAME = "1.2.3"
        val MANIFEST =
            AppUpdateManifest(
                versionCode = 20_000_001,
                versionName = VERSION_NAME,
                apkUrl = "https://example.test/amphora.apk",
                sha256 = "b".repeat(64),
                size = 123,
            )
    }
}
