package app.amphora.feature.settings

import android.content.Context
import android.content.SharedPreferences
import app.amphora.core.common.testing.MainDispatcherRule
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.ContentReconciler
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.update.AppUpdateManifest
import app.amphora.core.engine.ContentHealthScanner
import app.amphora.core.engine.DirectDrawWrapperIds
import app.amphora.core.engine.DxvkFlavorIds
import app.amphora.core.engine.DxvkRequirements
import app.amphora.core.engine.GraphicsDriverCapabilities
import app.amphora.core.engine.GraphicsDriverIds
import app.amphora.core.engine.GuestDriveManager
import app.amphora.core.engine.GuestDriveMapping
import app.amphora.core.engine.GuestVulkanDevice
import app.amphora.core.engine.GuestVulkanProbe
import app.amphora.core.engine.GuestVulkanProbeReport
import app.amphora.core.engine.LaunchRuntimeSettings
import app.amphora.core.engine.PulseAudioCapabilities
import app.amphora.core.engine.PulseAudioProbe
import app.amphora.core.engine.RuntimeSettingsStore
import app.amphora.core.engine.ShizukuCleanupStatus
import app.amphora.core.engine.ShizukuEmergencyStopper
import app.amphora.core.engine.TurnipDriverProvisioner
import app.amphora.core.engine.model.ContentComponentHealth
import app.amphora.core.engine.model.ContentHealthSnapshot
import app.amphora.core.engine.model.RuntimeAssetHealth
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val dispatchers = MainDispatcherRule()

    @Test
    fun startupDelegatesContentStorageAndDriveWorkToServiceBoundaries() = runTest(dispatchers.testDispatcher) {
        val fixture = fixture()

        runCurrent()

        with(fixture.viewModel.uiState.value) {
            assertTrue(manifestReady)
            assertFalse(refreshing)
            assertEquals(ComponentHealth.UPDATE, components.single().health)
            assertEquals(AssetHealth.LOCAL, runtimeAssets.single().health)
            assertTrue(imagefsResidue)
            assertEquals(fixture.storageBackend.usage, storageUsage)
            assertEquals(fixture.drives, guestDrives)
        }
        coVerify(exactly = 1) { fixture.catalog.refresh() }
        verify(exactly = 1) { fixture.contentReconciler.reconcile(fixture.manifest) }
        coVerify(exactly = 1) { fixture.contentHealthScanner.scan(fixture.manifest) }
        assertEquals(1, fixture.storageBackend.scanCalls)
        coVerify(exactly = 1) { fixture.guestDriveManager.refresh() }
    }

    @Test
    fun sharedRuntimeSettingsStaySynchronizedAndSelectionsPersistThroughStore() = runTest(dispatchers.testDispatcher) {
        val fixture = fixture()
        runCurrent()

        fixture.runtimeState.value =
            LaunchRuntimeSettings(
                resolutionName = DisplayResolution.R1920x1080.name,
                graphicsDriverId = GraphicsDriverIds.SYSTEM,
                directDrawWrapperId = DirectDrawWrapperIds.D7VK,
            )
        runCurrent()

        with(fixture.viewModel.uiState.value) {
            assertEquals(DisplayResolution.R1920x1080, resolution)
            assertEquals(GraphicsDriverSetting.SYSTEM, graphicsDriver)
            assertEquals(DirectDrawSetting.D7VK, directDrawWrapper)
        }

        fixture.viewModel.selectResolution(DisplayResolution.R1600x900)
        fixture.viewModel.selectDirectDraw(DirectDrawSetting.CNC_DDRAW)
        fixture.viewModel.selectGraphicsDriver(GraphicsDriverSetting.SYSTEM)
        advanceUntilIdle()

        verify { fixture.runtimeSettings.setResolutionName(DisplayResolution.R1600x900.name) }
        verify { fixture.runtimeSettings.setDirectDrawWrapperId(DirectDrawSetting.CNC_DDRAW.id) }
        verify { fixture.runtimeSettings.setGraphicsDriverId(GraphicsDriverSetting.SYSTEM.id) }
    }

    @Test
    fun storageCleanupUsesServiceResultAndRefreshesMeasuredUsage() = runTest(dispatchers.testDispatcher) {
        val fixture = fixture()
        runCurrent()
        fixture.storageBackend.usage = StorageUsage(totalBytes = 40, reclaimableBytes = 0)
        fixture.storageBackend.cleanupResult = StorageCleanupResult(bytesFreed = 60)

        fixture.viewModel.deleteUnusedGuestData(listOf("/cache/old.tmp"))
        advanceUntilIdle()

        assertEquals(listOf("/cache/old.tmp"), fixture.storageBackend.deletedPaths)
        assertEquals(2, fixture.storageBackend.scanCalls)
        assertEquals("Freed 60 B.", fixture.viewModel.uiState.value.storageMessage)
        assertEquals(fixture.storageBackend.usage, fixture.viewModel.uiState.value.storageUsage)
    }

    @Test
    fun updateEffectsAreExecutedByControllerAndReducedBackIntoUiState() = runTest(dispatchers.testDispatcher) {
        val fixture = fixture()
        runCurrent()
        coEvery {
            fixture.updateController.execute(SettingsUpdateEffect.CheckForUpdate)
        } returns
            SettingsUpdateEvent.CheckCompleted(
                UpdateCheckOutcome.UpdateAvailable(
                    update = UPDATE,
                    installedVersionCode = 20_000_000,
                    remoteVersionCode = UPDATE.versionCode.toLong(),
                    remoteVersionName = UPDATE.versionName,
                ),
            )
        coEvery {
            fixture.updateController.execute(SettingsUpdateEffect.DownloadAndInstall(UPDATE))
        } returns SettingsUpdateEvent.InstallStarted

        fixture.viewModel.checkForUpdate()
        assertTrue(fixture.viewModel.uiState.value.updateBusy)
        runCurrent()

        assertEquals(UPDATE, fixture.viewModel.uiState.value.availableUpdate)
        assertFalse(fixture.viewModel.uiState.value.updateBusy)

        fixture.viewModel.installUpdate()
        runCurrent()

        coVerify(exactly = 1) {
            fixture.updateController.execute(SettingsUpdateEffect.DownloadAndInstall(UPDATE))
        }
        assertTrue(fixture.viewModel.uiState.value.updateBusy)
        assertEquals(
            "Install started. Amphora will reopen automatically.",
            fixture.viewModel.uiState.value.updateMessage,
        )
    }

    @Test
    fun selectingPulseOnAnUnsupportedDeviceKeepsTheRequestAndShowsFallbackUi() = runTest(dispatchers.testDispatcher) {
        val fixture =
            fixture(
                PulseAudioProbe(
                    platformSupported = false,
                    wineDriver = PulseAudioProbe.WineDriver.PRESENT,
                ),
            )
        runCurrent()

        fixture.viewModel.selectAudioBackend(AudioBackend.PULSEAUDIO)

        with(fixture.viewModel.uiState.value) {
            assertEquals(AudioBackend.PULSEAUDIO, audioBackend)
            assertEquals(AudioBackend.ALSA, audioStatus.effective)
            assertEquals("PulseAudio · uses ALSA", audioStatus.chipLabel)
            assertEquals("PulseAudio → ALSA", audioStatus.summaryLabel)
            assertTrue(audioStatus.warning!!.contains("ALSA will run instead"))
            assertTrue(audioFallbackDialog!!.contains("16 KB"))
        }

        fixture.viewModel.dismissAudioFallbackDialog()
        assertEquals(null, fixture.viewModel.uiState.value.audioFallbackDialog)
        assertEquals(AudioBackend.PULSEAUDIO, fixture.viewModel.uiState.value.audioBackend)
    }

    @Test
    fun automaticFlavorOnQualcommProprietaryShowsTheD3d89Split() = runTest(dispatchers.testDispatcher) {
        val fixture = fixture(adreno = true)
        runCurrent()

        with(fixture.viewModel.uiState.value.dxvkFlavor) {
            assertEquals(DxvkFlavorSetting.AUTO, selected)
            assertEquals(DxvkFlavorSetting.DXVK, resolved)
            assertEquals("DXVK 2.6.2", d3d89)
            assertEquals(
                "Automatic chose DXVK 3.0.2 + DXVK 2.6.2 for Direct3D 8/9 · " +
                    "this device's Qualcomm driver rejects DXVK's fixed-function shaders",
                impact,
            )
        }
    }

    @Test
    fun explicitFlavorAndNonAdrenoStayPure() = runTest(dispatchers.testDispatcher) {
        val fixture = fixture(adreno = false)
        runCurrent()
        assertEquals(null, fixture.viewModel.uiState.value.dxvkFlavor.d3d89)

        val adreno = fixture(adreno = true)
        runCurrent()
        adreno.runtimeState.value = LaunchRuntimeSettings(dxvkFlavorId = DxvkFlavorIds.DXVK)
        runCurrent()
        with(adreno.viewModel.uiState.value.dxvkFlavor) {
            assertEquals(DxvkFlavorSetting.DXVK, selected)
            assertEquals(DxvkFlavorSetting.DXVK, resolved)
            assertEquals(null, d3d89)
            assertEquals(
                "DXVK 3.0.2 · Direct3D 8–11 · applies on next launch",
                impact,
            )
        }
    }

    private fun fixture(
        pulseProbe: PulseAudioProbe =
            PulseAudioProbe(
                platformSupported = true,
                wineDriver = PulseAudioProbe.WineDriver.PRESENT,
            ),
        adreno: Boolean = true,
    ): Fixture {
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { preferences.getString(any(), any()) } answers { secondArg() }
        every { preferences.getBoolean(any(), any()) } answers { secondArg() }
        every { preferences.edit() } returns editor
        val context = mockk<Context>()
        every {
            context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
        } returns preferences

        val runtimeState = MutableStateFlow(LaunchRuntimeSettings())
        val runtimeSettings = mockk<RuntimeSettingsStore>(relaxed = true)
        every { runtimeSettings.settings } returns runtimeState

        val catalog = mockk<ContentCatalog>()
        val manifest = mockk<ContentManifest>()
        coEvery { catalog.refresh() } returns manifest
        val contentReconciler = mockk<ContentReconciler>()
        every { contentReconciler.reconcile(manifest) } returns ContentReconciler.Report(0, 0)
        val contentHealthScanner = mockk<ContentHealthScanner>()
        coEvery { contentHealthScanner.scan(manifest) } returns CONTENT_HEALTH

        val storageBackend = FakeStorageBackend()
        val storageService =
            SettingsStorageService(
                ioDispatcher = dispatchers.testDispatcher,
                scanStorage = storageBackend::scan,
                deleteStorage = storageBackend::delete,
                clearStateCache = storageBackend::clear,
            )

        val guestDriveManager = mockk<GuestDriveManager>()
        val drives =
            listOf(
                GuestDriveMapping(
                    letter = "E",
                    label = "SD card",
                    path = "/storage/card",
                    removable = true,
                    available = true,
                ),
            )
        coEvery { guestDriveManager.refresh() } returns drives

        val shizukuStatus = MutableStateFlow(ShizukuCleanupStatus.UNAVAILABLE)
        val shizuku = mockk<ShizukuEmergencyStopper>(relaxed = true)
        every { shizuku.status } returns shizukuStatus
        val updateController = mockk<SettingsUpdateController>()
        every { updateController.installedVersionName() } returns "1.0.0"
        every { updateController.installedVersionCode() } returns 20_000_000
        every { updateController.installStatus } returns shizukuStatus

        // An Adreno device whose guest adapter meets every DXVK 3.0.2
        // requirement: the probe is a guest process, so the fixture states its
        // verdict instead of pretending to run it.
        val guestReport = GuestVulkanProbeReport(
            surfaceInstanceCreate = 0,
            instanceCreate = 0,
            instanceExtensions = emptySet(),
            enumerateDevices = 0,
            devices = listOf(
                GuestVulkanDevice(
                    deviceName = "Wrapper(Adreno (TM) 830)",
                    apiVersion = GuestVulkanDevice.apiVersion(1, 3),
                    vendorId = 0x5143,
                    deviceId = 0,
                    deviceType = 1,
                    driverName = "qualcomm",
                    driverInfo = "",
                    driverId = 6,
                    conformance = "1.3.6.0",
                    features = (
                        DxvkRequirements.DXVK_3_0_2_GPLASYNC.requiredFeatures +
                            "extDescriptorHeap.descriptorHeap"
                        ).associateWith { true },
                    extensions = DxvkRequirements.DXVK_3_0_2_GPLASYNC.requiredExtensions,
                    limits = mapOf("maxPushConstantsSize" to 256L),
                    queueFamilies = listOf(
                        GuestVulkanDevice.QueueFamily(
                            flags = GuestVulkanDevice.QUEUE_GRAPHICS_OR_COMPUTE,
                            count = 1,
                        ),
                    ),
                ),
            ),
        )
        val guestVerdict = GuestVulkanProbe.Verdict(
            key = "fixture",
            report = guestReport,
            failure = null,
            durationMs = 1,
            fromCache = true,
            driverId = GraphicsDriverIds.WRAPPER,
        )
        val graphicsDriverCapabilities = mockk<GraphicsDriverCapabilities>()
        every { graphicsDriverCapabilities.availableDriverIds() } returns
            listOf(GraphicsDriverIds.WRAPPER, GraphicsDriverIds.TURNIP_BALANCED)
        every { graphicsDriverCapabilities.effectiveDriverId(any()) } answers {
            GraphicsDriverIds.normalize(firstArg())
        }
        every { graphicsDriverCapabilities.guestProbeVerdict(any()) } returns guestVerdict
        every { graphicsDriverCapabilities.missingDxvkRequirements(any(), any()) } returns null
        every { graphicsDriverCapabilities.zinkBlockers(any()) } returns emptyList()
        every { graphicsDriverCapabilities.vkd3dMissing(any(), any()) } returns emptyList()
        every { graphicsDriverCapabilities.guestNativeBcn(any()) } returns true
        every { graphicsDriverCapabilities.invalidateProbeVerdicts(any()) } returns Unit
        every { graphicsDriverCapabilities.effectiveDxvkFlavor(any(), any()) } answers {
            DxvkFlavorIds.resolve(firstArg(), guestReport)
        }
        every { graphicsDriverCapabilities.effectiveD3d89DxvkFlavor(any(), any()) } answers {
            DxvkFlavorIds.d3d89Flavor(
                id = firstArg(),
                resolvedFlavor = DxvkFlavorIds.resolve(firstArg(), guestReport),
                effectiveDriverId = GraphicsDriverIds.normalize(secondArg()),
                isAdreno = adreno,
            )
        }

        val pulseAudioCapabilities = mockk<PulseAudioCapabilities>()
        every { pulseAudioCapabilities.probe() } returns pulseProbe

        return Fixture(
            viewModel =
            SettingsViewModel(
                context = context,
                dispatchers = dispatchers,
                catalog = catalog,
                contentReconciler = contentReconciler,
                contentHealthScanner = contentHealthScanner,
                storageService = storageService,
                turnipProvisioner = mockk<TurnipDriverProvisioner>(relaxed = true),
                guestDriveManager = guestDriveManager,
                shizukuEmergencyStopper = shizuku,
                updateController = updateController,
                runtimeSettings = runtimeSettings,
                graphicsDriverCapabilities = graphicsDriverCapabilities,
                pulseAudioCapabilities = pulseAudioCapabilities,
            ),
            runtimeState = runtimeState,
            runtimeSettings = runtimeSettings,
            catalog = catalog,
            manifest = manifest,
            contentReconciler = contentReconciler,
            contentHealthScanner = contentHealthScanner,
            storageBackend = storageBackend,
            guestDriveManager = guestDriveManager,
            drives = drives,
            updateController = updateController,
        )
    }

    private data class Fixture(
        val viewModel: SettingsViewModel,
        val runtimeState: MutableStateFlow<LaunchRuntimeSettings>,
        val runtimeSettings: RuntimeSettingsStore,
        val catalog: ContentCatalog,
        val manifest: ContentManifest,
        val contentReconciler: ContentReconciler,
        val contentHealthScanner: ContentHealthScanner,
        val storageBackend: FakeStorageBackend,
        val guestDriveManager: GuestDriveManager,
        val drives: List<GuestDriveMapping>,
        val updateController: SettingsUpdateController,
    )

    private class FakeStorageBackend {
        var usage = StorageUsage(totalBytes = 100, shaderCacheBytes = 20, reclaimableBytes = 20)
        var cleanupResult = StorageCleanupResult()
        var scanCalls = 0
        var clearCalls = 0
        var deletedPaths: List<String> = emptyList()

        suspend fun scan(): StorageUsage {
            scanCalls++
            return usage
        }

        suspend fun delete(paths: List<String>): StorageCleanupResult {
            deletedPaths = paths
            return cleanupResult
        }

        suspend fun clear() {
            clearCalls++
        }
    }

    private companion object {
        val CONTENT_HEALTH =
            ContentHealthSnapshot(
                components =
                listOf(
                    ContentComponentHealth(
                        component = ContentComponent.ROOTFS,
                        pinned = "2",
                        installed = "1",
                        state = ContentComponentHealth.State.UPDATE,
                    ),
                ),
                runtimeAssets =
                listOf(
                    RuntimeAssetHealth(
                        assetPath = "drivers/local.so",
                        pinnedSha = "a".repeat(64),
                        installedSha = "b".repeat(64),
                        sizeBytes = 1,
                        state = RuntimeAssetHealth.State.LOCAL_OVERRIDE,
                    ),
                ),
                imageFsResidue = true,
            )
        val UPDATE =
            AppUpdateManifest(
                versionCode = 20_000_001,
                versionName = "1.0.1",
                apkUrl = "https://example.test/amphora.apk",
                sha256 = "c".repeat(64),
                size = 123,
            )
    }
}
