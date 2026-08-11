package app.amphora.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUpdateCoordinatorTest {
    @Test
    fun upToDateClearsBusyAndAvailableUpdate() {
        val coordinator = coordinatorWithAvailable()

        coordinator.dispatch(SettingsUpdateEvent.CheckRequested)
        val transition =
            coordinator.dispatch(
                SettingsUpdateEvent.CheckCompleted(UpdateCheckOutcome.UpToDate("1.2.3")),
            )

        assertFalse(transition.state.busy)
        assertNull(transition.state.availableUpdate)
        assertEquals("Up to date (1.2.3)", transition.state.message)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun updateAvailablePublishesManifestAndVersionSummary() {
        val coordinator = coordinator()
        coordinator.dispatch(SettingsUpdateEvent.CheckRequested)

        val transition =
            coordinator.dispatch(
                SettingsUpdateEvent.CheckCompleted(
                    UpdateCheckOutcome.UpdateAvailable(
                        update = UPDATE,
                        installedVersionCode = 20_000_000,
                        remoteVersionCode = 20_000_001,
                        remoteVersionName = VERSION_NAME,
                    ),
                ),
            )

        assertFalse(transition.state.busy)
        assertEquals(UPDATE, transition.state.availableUpdate)
        assertEquals(
            "Update available: 1.2.3 (20000000 → 20000001)",
            transition.state.message,
        )
    }

    @Test
    fun unavailableClearsAvailableUpdateAndShowsReason() {
        val coordinator = coordinatorWithAvailable()
        coordinator.dispatch(SettingsUpdateEvent.CheckRequested)

        val transition =
            coordinator.dispatch(
                SettingsUpdateEvent.CheckCompleted(UpdateCheckOutcome.Unavailable("offline")),
            )

        assertNull(transition.state.availableUpdate)
        assertEquals("offline", transition.state.message)
        assertFalse(transition.state.busy)
    }

    @Test
    fun failedCheckClearsAvailableUpdateAndShowsFailure() {
        val coordinator = coordinatorWithAvailable()
        coordinator.dispatch(SettingsUpdateEvent.CheckRequested)

        val transition =
            coordinator.dispatch(
                SettingsUpdateEvent.CheckCompleted(UpdateCheckOutcome.Failed("network failed")),
            )

        assertNull(transition.state.availableUpdate)
        assertEquals("network failed", transition.state.message)
        assertFalse(transition.state.busy)
    }

    @Test
    fun repeatedInstallRequestsProduceOneInstallEffect() {
        val coordinator = coordinatorWithAvailable()

        val first = coordinator.dispatch(SettingsUpdateEvent.InstallRequested(permissionRequired = false))
        val duplicate = coordinator.dispatch(SettingsUpdateEvent.InstallRequested(permissionRequired = false))

        assertEquals(
            listOf(SettingsUpdateEffect.DownloadAndInstall(UPDATE)),
            first.effects,
        )
        assertTrue(first.state.installing)
        assertTrue(duplicate.effects.isEmpty())
        assertTrue(duplicate.state.installing)
    }

    @Test
    fun successfulPermissionRequestWaitsAndSchedulesTimeoutOnce() {
        val coordinator = coordinatorWithAvailable()

        val request =
            coordinator.dispatch(SettingsUpdateEvent.InstallRequested(permissionRequired = true))
        val duplicate =
            coordinator.dispatch(SettingsUpdateEvent.InstallRequested(permissionRequired = true))
        val waiting =
            coordinator.dispatch(
                SettingsUpdateEvent.PermissionRequestCompleted(requestStarted = true),
            )

        assertEquals(listOf(SettingsUpdateEffect.RequestPermission), request.effects)
        assertTrue(duplicate.effects.isEmpty())
        assertTrue(waiting.state.waitingForPermission)
        assertFalse(waiting.state.busy)
        assertEquals(
            "Grant Shizuku access to install automatically.",
            waiting.state.message,
        )
        assertEquals(listOf(SettingsUpdateEffect.SchedulePermissionTimeout), waiting.effects)
    }

    @Test
    fun failedPermissionRequestImmediatelyUsesInstallFallback() {
        val coordinator = coordinatorWithAvailable()
        coordinator.dispatch(SettingsUpdateEvent.InstallRequested(permissionRequired = true))

        val transition =
            coordinator.dispatch(
                SettingsUpdateEvent.PermissionRequestCompleted(requestStarted = false),
            )

        assertTrue(transition.state.installing)
        assertFalse(transition.state.waitingForPermission)
        assertEquals(
            listOf(SettingsUpdateEffect.DownloadAndInstall(UPDATE)),
            transition.effects,
        )
    }

    @Test
    fun readyStatusContinuesWaitingInstallExactlyOnce() {
        val coordinator = waitingForPermission()

        val ready = coordinator.dispatch(SettingsUpdateEvent.PermissionReady)
        val duplicateReady = coordinator.dispatch(SettingsUpdateEvent.PermissionReady)
        val expiredTimeout = coordinator.dispatch(SettingsUpdateEvent.PermissionWaitExpired)

        assertTrue(ready.state.installing)
        assertFalse(ready.state.waitingForPermission)
        assertEquals(
            listOf(SettingsUpdateEffect.DownloadAndInstall(UPDATE)),
            ready.effects,
        )
        assertTrue(duplicateReady.effects.isEmpty())
        assertTrue(expiredTimeout.effects.isEmpty())
    }

    @Test
    fun readyObservedDuringPermissionRequestContinuesAfterRequestReturns() {
        val coordinator = coordinatorWithAvailable()
        coordinator.dispatch(SettingsUpdateEvent.InstallRequested(permissionRequired = true))

        val earlyReady = coordinator.dispatch(SettingsUpdateEvent.PermissionReady)
        val completed =
            coordinator.dispatch(
                SettingsUpdateEvent.PermissionRequestCompleted(requestStarted = true),
            )

        assertTrue(earlyReady.effects.isEmpty())
        assertTrue(completed.state.installing)
        assertEquals(
            listOf(SettingsUpdateEffect.DownloadAndInstall(UPDATE)),
            completed.effects,
        )
    }

    @Test
    fun permissionTimeoutUsesFallbackWithoutRealDelay() {
        val coordinator = waitingForPermission()

        val transition = coordinator.dispatch(SettingsUpdateEvent.PermissionWaitExpired)

        assertTrue(transition.state.installing)
        assertFalse(transition.state.waitingForPermission)
        assertEquals(
            listOf(SettingsUpdateEffect.DownloadAndInstall(UPDATE)),
            transition.effects,
        )
    }

    @Test
    fun installStartedKeepsBusyUntilProcessRestarts() {
        val coordinator = installingUpdate()

        val transition = coordinator.dispatch(SettingsUpdateEvent.InstallStarted)

        assertTrue(transition.state.busy)
        assertEquals(
            "Install started. Amphora will reopen automatically.",
            transition.state.message,
        )
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun systemInstallerResultPublishesArtifactAndClearsBusy() {
        val coordinator = installingUpdate()

        val transition =
            coordinator.dispatch(
                SettingsUpdateEvent.SystemInstallerRequired(
                    artifact = "fallback.apk",
                    reason = "Automatic install unavailable",
                ),
            )

        assertFalse(transition.state.busy)
        assertEquals("fallback.apk", transition.state.pendingArtifact)
        assertEquals("Automatic install unavailable", transition.state.message)
    }

    @Test
    fun installExceptionClearsBusyAndArtifact() {
        val coordinator = installingUpdate()

        val transition =
            coordinator.dispatch(SettingsUpdateEvent.InstallFailed("signature mismatch"))

        assertFalse(transition.state.busy)
        assertNull(transition.state.pendingArtifact)
        assertEquals("signature mismatch", transition.state.message)
    }

    private fun waitingForPermission(): Coordinator =
        coordinatorWithAvailable().also { coordinator ->
            coordinator.dispatch(SettingsUpdateEvent.InstallRequested(permissionRequired = true))
            coordinator.dispatch(
                SettingsUpdateEvent.PermissionRequestCompleted(requestStarted = true),
            )
        }

    private fun installingUpdate(): Coordinator =
        coordinatorWithAvailable().also {
            it.dispatch(SettingsUpdateEvent.InstallRequested(permissionRequired = false))
        }

    private fun coordinatorWithAvailable(): Coordinator =
        coordinator().also { coordinator ->
            coordinator.dispatch(SettingsUpdateEvent.CheckRequested)
            coordinator.dispatch(
                SettingsUpdateEvent.CheckCompleted(
                    UpdateCheckOutcome.UpdateAvailable(
                        update = UPDATE,
                        installedVersionCode = 20_000_000,
                        remoteVersionCode = 20_000_001,
                        remoteVersionName = VERSION_NAME,
                    ),
                ),
            )
        }

    private fun coordinator(): Coordinator = SettingsUpdateCoordinator()

    private companion object {
        const val UPDATE = "manifest-1.2.3"
        const val VERSION_NAME = "1.2.3"
    }
}

private typealias Coordinator = SettingsUpdateCoordinator<String, String>
