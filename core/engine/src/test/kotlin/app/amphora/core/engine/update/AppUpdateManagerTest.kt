package app.amphora.core.engine.update

import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.update.AppUpdateManifest
import app.amphora.core.content.update.AppUpdater
import app.amphora.core.engine.ShizukuCleanupStatus
import app.amphora.core.engine.ShizukuEmergencyStopper
import app.amphora.core.engine.ShizukuInstallResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun startedShizukuInstallMapsToStarted(): Unit = runBlocking {
        val fixture = fixture(ShizukuInstallResult.Started)

        assertEquals(AppUpdateInstallResult.Started, fixture.manager.downloadAndInstall(MANIFEST))

        fixture.verifyValidationAndInstall()
    }

    @Test
    fun unavailableShizukuOffersSystemInstaller(): Unit = runBlocking {
        val fixture = fixture(ShizukuInstallResult.Unavailable)

        val result = fixture.manager.downloadAndInstall(MANIFEST)

        assertTrue(result is AppUpdateInstallResult.SystemInstallerRequired)
        result as AppUpdateInstallResult.SystemInstallerRequired
        assertEquals(fixture.apk, result.apk)
        assertTrue(result.reason.contains("unavailable", ignoreCase = true))
        fixture.verifyValidationAndInstall()
    }

    @Test
    fun shizukuFailureOffersSystemInstallerWithReason(): Unit = runBlocking {
        val fixture = fixture(ShizukuInstallResult.Failed("service rejected package"))

        val result = fixture.manager.downloadAndInstall(MANIFEST)

        assertTrue(result is AppUpdateInstallResult.SystemInstallerRequired)
        result as AppUpdateInstallResult.SystemInstallerRequired
        assertEquals(fixture.apk, result.apk)
        assertTrue(result.reason.contains("service rejected package"))
        fixture.verifyValidationAndInstall()
    }

    @Test
    fun validationFailurePreventsInstallAndPropagates(): Unit = runBlocking {
        val fixture = fixture(ShizukuInstallResult.Started)
        val expected = IllegalArgumentException("signature mismatch")
        every { fixture.updater.validateDownloadedApk(fixture.apk, MANIFEST) } throws expected

        val actual =
            try {
                fixture.manager.downloadAndInstall(MANIFEST)
                AssertionError("Expected validation failure")
            } catch (failure: Throwable) {
                failure
            }

        assertEquals(expected::class, actual::class)
        assertEquals(expected.message, actual.message)
        coVerify(exactly = 0) { fixture.shizuku.installPackage(any()) }
    }

    private fun fixture(installResult: ShizukuInstallResult): Fixture {
        val apk = temporaryFolder.newFile("update-${System.nanoTime()}.apk").apply { writeText("apk") }
        val updater = mockk<AppUpdater>()
        val shizuku = mockk<ShizukuEmergencyStopper>()
        coEvery { updater.download(MANIFEST) } returns apk
        every { updater.validateDownloadedApk(apk, MANIFEST) } just Runs
        every { shizuku.status } returns MutableStateFlow(ShizukuCleanupStatus.READY)
        coEvery { shizuku.installPackage(apk) } returns installResult
        return Fixture(
            manager = AppUpdateManager(updater, shizuku, ImmediateDispatchers),
            updater = updater,
            shizuku = shizuku,
            apk = apk,
        )
    }

    private data class Fixture(
        val manager: AppUpdateManager,
        val updater: AppUpdater,
        val shizuku: ShizukuEmergencyStopper,
        val apk: File,
    ) {
        fun verifyValidationAndInstall() {
            io.mockk.verify(exactly = 1) { updater.validateDownloadedApk(apk, MANIFEST) }
            coVerify(exactly = 1) { shizuku.installPackage(apk) }
        }
    }

    private object ImmediateDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private companion object {
        val MANIFEST =
            AppUpdateManifest(
                versionCode = 20_000_001,
                versionName = "1.2.3",
                apkUrl = "https://example.test/amphora.apk",
                sha256 = "a".repeat(64),
                size = 3,
            )
    }
}
