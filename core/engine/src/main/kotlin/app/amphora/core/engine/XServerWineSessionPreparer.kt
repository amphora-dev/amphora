package app.amphora.core.engine

import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import android.content.Context
import android.util.Log
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.model.Container as AmphoraContainer
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.engine.model.LaunchSpec
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.WinComponentSetup
import androidx.annotation.VisibleForTesting
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.system.GPUInformation
import com.winlator.cmod.runtime.wine.DXVKConfigUtils
import com.winlator.cmod.runtime.wine.EnvVars
import com.winlator.cmod.runtime.wine.GraphicsDriverConfigUtils
import com.winlator.cmod.runtime.wine.WineD3DConfigUtils
import com.winlator.cmod.runtime.wine.WineInfo
import com.winlator.cmod.runtime.wine.WineStartMenuCreator
import com.winlator.cmod.runtime.wine.WineUtils
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.TarCompressorUtils
import com.winlator.cmod.shared.util.StringUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [WineSessionPreparer] (RFC §7 / D9): the ~800-1000 line "core launch"
 * extracted verbatim from WinNative's `XServerDisplayActivity` (10,995 lines),
 * with Steam / recording / shortcut / Activity-lifecycle / arm64ec branches
 * stripped. Method bodies are mechanical copies of the XSDA private methods
 * (line numbers in [WineSessionPreparer]); the only redesign is the
 * [AmphoraContainer] -> WinNative `Container` resolution ([resolveState]) and
 * the [envVars] output accessor.
 *
 * **Why this lives in `:core:engine`:** the prep logic adapts the ported
 * `com.winlator.cmod` kernel (`ImageFs` / `ContentsManager` / `ContainerManager`
 * / `WineUtils` / `WinComponentSetup` ...), which is only visible here (RFC §6
 * dep graph `engine -> {rootfs,container}`). The [WineSessionPreparer] contract
 * stays an amphora type; the concretion lives next to the kernel it adapts
 * (Dependency Inversion -- same pattern as [ImageFsRootfsInstaller]).
 *
 * **Stripped (D9 / D5 / D8):**
 * - Steam: `isSteamShortcut`/`setSteamClientVisibility`/`SteamBridge`/
 *   `verifySteamClientFiles`/`PrefManager` -- non-target.
 * - Shortcut: `getShortcutSetting(key, containerValue)` -> `containerValue`
 *   directly. Amphora passes exe+env only (D9).
 * - Activity lifecycle: `isFinishing`/`isDestroyed`/`xServer` null guards --
 *   prep has no Activity/xServer (xServer is created in P3 setupXEnvironment).
 * - `desktopTheme` apply (WineThemeManager.apply removed for MVP)
 *   deferred to P3 post-xServer-creation.
 * - arm64ec (`ensureArm64EcRuntimeDllsReady` / zink_dlls branch) -- D5 rejects
 *   arm64ec, amphora is x86_64 + box64 only.
 * - `applyPreferredRefreshRate` (Activity/Window refresh-rate UI) -> Compose P3.
 *
 * **Adrenotools driver install:** [installAdrenotoolsDriverIfNeeded] copies the
 * bundled Turnip `libvulkan_wrapper.so` (+ runtime deps) from imagefs into
 * `filesDir/contents/adrenotools/<id>/` with a minimal `meta.json`, so the host
 * `VulkanRenderer` and guest ICD share the same driver. Skips only when both
 * `meta.json` and the driver `.so` are already present.
 *
 * **Stubbed (deferred):**
 * - `getDxvkFrameRateOverride` returns 0 (shortcut/preferences-driven).
 *
 * **Status:** prep path verified on device (P2 §P2 #7) and wired into the live
 * launch chain (RFC §8). Self-calls `syncContents()` in [resolveState].
 *
 * @param envState the mutable wrapper/GPU env-var accumulator (XSDA `envVars`
 *   field); exposed read-only via [envVars] for `WineEngineImpl` to merge into
 *   the `box64 wine explorer` launch env (P3).
 */
@Singleton
class XServerWineSessionPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : WineSessionPreparer {

    // --- kernel singletons (constructed like XSDA L1041/1084/1099) -----------
    private val imageFs: ImageFs = ImageFs.find(context)
    private val contentsManager: ContentsManager = ContentsManager(context)
    private val containerManager: ContainerManager = ContainerManager(context)

    // --- launch-time state (resolved per prep from the WinNative Container) --
    private var wnContainer: Container? = null
    private var wineVersion: String = WineInfo.MAIN_WINE_VERSION.identifier()
    private var wineInfo: WineInfo = WineInfo.MAIN_WINE_VERSION
    private var graphicsDriver: String = Container.DEFAULT_GRAPHICS_DRIVER
    private var dxwrapper: String = Container.DEFAULT_DXWRAPPER
    private var dxwrapperConfig: com.winlator.cmod.shared.util.KeyValueSet =
        DXVKConfigUtils.parseConfig(null)
    private var graphicsDriverConfig: HashMap<String, String> = HashMap()
    private var firstTimeBoot: Boolean = false
    private var bootExePath: String? = null
    private var startupSelection: String = "0"
    private val envState: EnvVars = EnvVars()

    override fun envVars(): Map<String, String> = buildMap {
        for (key in envState) this[key] = envState.get(key)
    }

    /**
     * Test-only: loads installed content profiles into the internal
     * [ContentsManager] so [ensureWinePrefixReady] / `WineInfo.fromIdentifier`
     * can resolve Proton/Box64 profiles. Production wires `syncContents()` at
     * app init; tests construct the preparer directly and must sync explicitly.
     */
    @VisibleForTesting
    fun syncContentsForTesting() {
        contentsManager.syncContents()
    }

    // --- interface overrides: resolve state on a worker thread, then delegate -

    override suspend fun setupWineSystemFiles(spec: LaunchSpec, container: AmphoraContainer) =
        withContext(dispatchers.io) {
            envState.clear()
            resolveState(spec, container)
            setupWineSystemFilesCore()
        }

    override suspend fun ensureWinePrefixReady(container: AmphoraContainer) =
        withContext(dispatchers.io) {
            resolveState(null, container)
            ensureWinePrefixReadyCore()
        }

    override suspend fun ensureLaunchRuntimeFilesReady(container: AmphoraContainer) =
        withContext(dispatchers.io) {
            resolveState(null, container)
            ensureLaunchRuntimeFilesReadyCore()
        }

    override suspend fun ensureWinePrefixEssentialFiles(container: AmphoraContainer) =
        withContext(dispatchers.io) {
            resolveState(null, container)
            ensureWinePrefixEssentialFilesCore()
        }

    override suspend fun extractDXWrapperFiles(container: AmphoraContainer, dxwrapper: String) =
        withContext(dispatchers.io) {
            resolveState(null, container)
            extractDXWrapperFilesCore(dxwrapper)
        }

    override suspend fun extractGraphicsDriverFiles(container: AmphoraContainer) =
        withContext(dispatchers.io) {
            resolveState(null, container)
            extractGraphicsDriverFilesCore()
        }

    // --- state resolution (AmphoraContainer -> WinNative Container + config) --

    private fun resolveContainer(amphora: AmphoraContainer): Container {
        val target = File(amphora.rootPath).absoluteFile
        containerManager.loadContainers()
        val match = containerManager.getContainers().firstOrNull { it.getRootDir().absoluteFile == target }
        if (match != null) return match
        throw IllegalStateException(
            "WinNative container not found at ${amphora.rootPath} " +
                "(loaded ${containerManager.getContainers().size} container(s))",
        )
    }

    private fun resolveState(spec: LaunchSpec?, amphora: AmphoraContainer) {
        val target = File(amphora.rootPath).absoluteFile
        val existing = wnContainer
        val alreadyResolved = existing != null && existing.getRootDir().absoluteFile == target
        if (alreadyResolved && spec == null) return
        if (!alreadyResolved) {
            val c = resolveContainer(amphora)
            wnContainer = c
            wineVersion = c.getWineVersion()
            // Load installed profiles so WineInfo.fromIdentifier + repairContainerWinePrefix
            // resolve the Proton prefixPack. This manager instance is separate from the
            // engine's / ContainerManager's (per-instance state) -- each must sync.
            contentsManager.syncContents()
            wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion)
            imageFs.setWinePath(wineInfo.path)
            graphicsDriver = c.getGraphicsDriver()
            dxwrapper = c.getDXWrapper()
            dxwrapperConfig = DXVKConfigUtils.parseConfig(c.getDXWrapperConfig())
            graphicsDriverConfig = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(c.getGraphicsDriverConfig())
            firstTimeBoot = false
            startupSelection = c.getStartupSelection().toString()
        }
        if (spec != null) bootExePath = spec.exePath
    }

    // --- setupWineSystemFiles (XSDA L6127) ------------------------------------

    private fun setupWineSystemFilesCore() {
        val c = wnContainer ?: return
        Log.d(
            TAG,
            "=== setupWineSystemFiles START === container=${c.id} wine=$wineVersion" +
                " arch=${wineInfo.getArch()} rootDir=${c.getRootDir().getAbsolutePath()}",
        )

        ensureWinePrefixReadyCore()
        ensureLaunchRuntimeFilesReadyCore()

        val appVersion = appVersionCode(context)
        val imgVersion = imageFs.getVersion().toString()
        var containerDataChanged = false

        if (c.getExtra("appVersion") != appVersion || c.getExtra("imgVersion") != imgVersion) {
            Log.d(TAG, "Version mismatch, applying general patches (app=$appVersion img=$imgVersion)")
            applyGeneralPatches(c)
            c.putExtra("appVersion", appVersion)
            c.putExtra("imgVersion", imgVersion)
            firstTimeBoot = true
            containerDataChanged = true
        }

        ensureWinePrefixEssentialFilesCore()

        // No shortcut (D9): derive dxwrapper + config straight from the container.
        var localDxwrapper = dxwrapper

        // amphora: WinlatorContainerManager writes the full delimited form
        // ("dxvk-3.0.2-gplasync-0;vkd3d-3.0.1-sm69-0;none") into `dxwrapper` and
        // leaves `dxwrapperConfig` empty. WinNative's XSDA, by contrast, stores the
        // short form ("dxvk+vkd3d") in `dxwrapper` and reconstructs the delimited
        // form from `dxwrapperConfig` below. Detect which shape we have and only rebuild
        // when the short form is present — otherwise pass the delimited form
        // through as-is so the version token isn't lost.
        if (!localDxwrapper.contains(";")) {
            val dxwrapperConfigStr = dxwrapperConfig.toString()
            val currentDXWrapperConfig = DXVKConfigUtils.parseConfig(dxwrapperConfigStr)

            if (localDxwrapper.contains("dxvk")) {
                val dxvkWrapper = "dxvk-" + currentDXWrapperConfig.get("version")
                val vkd3dWrapper = "vkd3d-" + currentDXWrapperConfig.get("vkd3dVersion")
                val ddrawrapper = currentDXWrapperConfig.get("ddrawrapper")
                Log.i(
                    TAG,
                    "Launch DX wrapper files selected: dxvk='$dxvkWrapper' vkd3d='$vkd3dWrapper' ddrawrapper='$ddrawrapper'",
                )
                localDxwrapper = "$dxvkWrapper;$vkd3dWrapper;$ddrawrapper"
            } else {
                val vkd3dVersion = currentDXWrapperConfig.get("vkd3dVersion")
                if (hasSelectedVkd3dVersion(vkd3dVersion)) {
                    val vkd3dWrapper = "vkd3d-$vkd3dVersion"
                    Log.i(TAG, "Launch VKD3D-only wrapper files selected: vkd3d='$vkd3dWrapper'")
                    localDxwrapper = "$localDxwrapper;$vkd3dWrapper"
                }
            }
        } else {
            // Already delimited — log the passthrough for diagnostics.
            val parts = localDxwrapper.split(";")
            val dxvkWrapper = if (parts.size > 0) parts[0] else ""
            val vkd3dWrapper = if (parts.size > 1) parts[1] else ""
            val ddrawrapper = if (parts.size > 2) parts[2] else ""
            Log.i(
                TAG,
                "Launch DX wrapper files selected (delimited form): dxvk='$dxvkWrapper' vkd3d='$vkd3dWrapper' ddrawrapper='$ddrawrapper'",
            )
        }

        val wincomponents = c.getWinComponents()
        if (wincomponents != c.getExtra("wincomponents") || firstTimeBoot) {
            WinComponentSetup.applyWinComponents(
                context,
                imageFs,
                wineInfo,
                c,
                wincomponents,
                c.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS),
                firstTimeBoot,
                null,
            )
            c.putExtra("wincomponents", wincomponents)
            containerDataChanged = true
        }

        val wineArchKey = if (wineVersion.contains("arm64ec")) "arm64ec" else "x86_64"
        val dxwrapperGateKey = "$localDxwrapper|arch=$wineArchKey"
        val forceWrapperApply = bootExePath != null && bootExePath!!.isNotEmpty()
        if (dxwrapperGateKey != c.getExtra("dxwrapper") || firstTimeBoot || forceWrapperApply) {
            Log.i(
                TAG,
                "DXVK/VKD3D extract: gate fired (key='$dxwrapperGateKey' prev='${c.getExtra("dxwrapper")}'" +
                    " firstTimeBoot=$firstTimeBoot forced=$forceWrapperApply)",
            )
            wipeDxwrapperDllsForReextract()
            extractDXWrapperFilesCore(localDxwrapper)
            c.putExtra("dxwrapper", dxwrapperGateKey)
            containerDataChanged = true
        }

        // Steam / custom-shortcut visibility branches STRIPPED (D9: non-target).
        // Activity teardown guard (xServer/isFinishing/isDestroyed) STRIPPED --
        // prep has no Activity/xServer.
        // desktopTheme apply omitted (WineThemeManager.apply removed for MVP)
        // deferred to P3 setupXEnvironment (post xServer creation).

        WineStartMenuCreator.create(context, c)
        stageGraphicsTestExes(c)
        WineUtils.createDosdevicesSymlinks(c, getActiveGameDirectoryPath(), isSteamShortcut())

        val inputType = c.getInputType()
        val dinputFlag = Container.FLAG_INPUT_TYPE_DINPUT.toInt()
        val dinputEnabled = (inputType and dinputFlag) == dinputFlag
        val exclusiveXInput = c.isExclusiveXInput()
        WineUtils.setJoystickRegistryKeys(c, dinputEnabled, exclusiveXInput)
        WineUtils.ensureWinebusConfig(c)

        startupSelection = c.getStartupSelection().toString()
        WineUtils.changeServicesStatus(c, startupSelection)
        if (startupSelection != c.getExtra("startupSelection")) {
            c.putExtra("startupSelection", startupSelection)
            containerDataChanged = true
        }
        if (containerDataChanged) {
            Log.d(TAG, "Saving container data id=${c.id}")
            c.saveData()
        }
        Log.d(TAG, "=== setupWineSystemFiles END === container=${c.id} firstTimeBoot=$firstTimeBoot")
    }

    // --- ensureWinePrefixReady (XSDA L7127) -----------------------------------

    private fun ensureWinePrefixReadyCore() {
        val c = wnContainer ?: return
        val containerDir = c.getRootDir()
        val prefixInvalid = !WineUtils.isPrefixValid(containerDir)
        val storedPrefixArch = c.getExtra("wineprefixArch")
        val archMismatch = storedPrefixArch.isNotEmpty() &&
            !storedPrefixArch.equals(wineInfo.getArch(), ignoreCase = true)
        val prefixNeedsUpdate = "t".equals(c.getExtra("wineprefixNeedsUpdate"), ignoreCase = true)
        Log.d(
            TAG,
            "ensureWinePrefixReady: prefixInvalid=$prefixInvalid archMismatch=$archMismatch" +
                " storedArch=$storedPrefixArch target=${wineInfo.getArch()} needsUpdate=$prefixNeedsUpdate",
        )

        if (!prefixInvalid && !archMismatch && !prefixNeedsUpdate) {
            if (storedPrefixArch.isEmpty()) {
                c.putExtra("wineprefixArch", wineInfo.getArch())
                c.putExtra("wineprefixNeedsUpdate", null)
                c.saveData()
            }
            return
        }

        Log.w(
            TAG,
            "Repairing Wine prefix for container ${c.id} invalid=$prefixInvalid" +
                " archMismatch=$archMismatch storedArch=$storedPrefixArch" +
                " target=${wineInfo.getArch()} needsUpdate=$prefixNeedsUpdate",
        )
        val repaired = containerManager.repairContainerWinePrefix(c, wineVersion, contentsManager, null)
        if (repaired) {
            firstTimeBoot = true
            Log.i(TAG, "Wine prefix repaired successfully for container ${c.id}")
        } else {
            Log.e(TAG, "Wine prefix repair failed for container ${c.id}")
        }
    }

    // --- ensureLaunchRuntimeFilesReady (XSDA L6280) + ensureBox64 (L6290) -----

    private fun ensureLaunchRuntimeFilesReadyCore() {
        val c = wnContainer ?: return
        // D5: arm64ec rejected -> box64 only (ensureArm64EcRuntimeDllsReady stripped).
        ensureBox64RuntimeReady(c)
    }

    private fun ensureBox64RuntimeReady(c: Container) {
        val rootDir = imageFs.getRootDir()
        val box64Missing = !File(rootDir, "usr/bin/box64").exists()
        var box64Version = c.getBox64Version() ?: ""
        if (box64Version.isEmpty()) {
            box64Version = pickNewestInstalledContentVersion(ContentProfile.ContentType.CONTENT_TYPE_BOX64)
            if (box64Version.isNotEmpty()) c.setBox64Version(box64Version)
        }

        if (!box64Missing && box64Version == c.getExtra("box64Version")) return

        if (box64Version.isEmpty()) {
            Log.w(TAG, "No Box64 version selected before first boot; runtime extraction skipped")
            return
        }

        val profile = resolveContentProfile(ContentProfile.ContentType.CONTENT_TYPE_BOX64, box64Version)
        if (profile == null) {
            Log.w(TAG, "Box64 content profile not installed for version: $box64Version")
            return
        }

        Log.i(TAG, "Preparing Box64 before Wine setup: version=$box64Version")
        contentsManager.applyContent(profile)
        c.putExtra("box64Version", box64Version)
        c.saveData()
    }

    // --- ensureWinePrefixEssentialFiles (XSDA L7164) -------------------------

    private fun ensureWinePrefixEssentialFilesCore() {
        val c = wnContainer ?: return
        val containerWindowsDir = File(c.getRootDir(), ".wine/drive_c/windows")
        val essentialFiles = arrayOf("winhandler.exe", "wfm.exe")

        val status = StringBuilder("ensureWinePrefixEssentialFiles:")
        var anyMissing = false
        for (filename in essentialFiles) {
            val exists = File(containerWindowsDir, filename).exists()
            status.append(" ").append(filename).append("=").append(exists)
            if (!exists) anyMissing = true
        }
        Log.d(TAG, status.toString())

        if (!anyMissing) return

        val homeDir = File(imageFs.getRootDir(), "home")
        val homeDirs = homeDir.listFiles()
        var sourceWindowsDir: File? = null
        if (homeDirs != null) {
            Log.d(TAG, "Searching ${homeDirs.size} dirs in home/ for essential files")
            for (dir in homeDirs) {
                if (!dir.isDirectory) continue
                if (dir.name == ImageFs.USER) continue
                if (dir.absolutePath == c.getRootDir().absolutePath) continue
                val candidate = File(dir, ".wine/drive_c/windows")
                if (File(candidate, "winhandler.exe").exists()) {
                    sourceWindowsDir = candidate
                    Log.d(TAG, "Found essential files source: ${dir.name}")
                    break
                }
            }
        }

        if (sourceWindowsDir != null) {
            for (filename in essentialFiles) {
                val dest = File(containerWindowsDir, filename)
                if (!dest.exists()) {
                    val source = File(sourceWindowsDir, filename)
                    if (source.exists()) {
                        Log.d(TAG, "Copying $filename from ${sourceWindowsDir.parent}")
                        FileUtils.copy(source, dest)
                    }
                }
            }
        } else {
            Log.w(TAG, "No source container found, extracting from container_pattern_common.tzst")
            containerWindowsDir.mkdirs()
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                "container_pattern_common.tzst",
                imageFs.getRootDir(),
            )
            for (filename in essentialFiles) {
                Log.d(TAG, "$filename exists after extraction: ${File(containerWindowsDir, filename).exists()}")
            }
        }
    }

    // --- extractDXWrapperFiles (XSDA L7970) + helpers -------------------------

    private fun extractDXWrapperFilesCore(dxwrapper: String) {
        val dlls = DXWRAPPER_DLLS
        val d3d12Dlls = arrayOf("d3d12.dll", "d3d12core.dll")
        val nonD3D12WrapperDlls = arrayOf(
            "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll",
            "d3d8.dll", "d3d9.dll", "dxgi.dll", "ddraw.dll", "d3dimm.dll",
        )

        val rootDir = imageFs.getRootDir()
        val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")

        if (dxwrapper.contains("dxvk")) {
            Log.d(TAG, "Extracting DXVK wrapper files, version: $dxwrapper")
            val parts = dxwrapper.split(";")
            val dxvkWrapper = parts[0]
            val vkd3dWrapper = parts[1]
            val ddrawrapper = parts[2]

            if (hasSelectedDxvkWrapper(dxvkWrapper)) {
                val dxvkProfile = resolveDxvkProfile(dxvkWrapper)
                if (dxvkProfile != null) {
                    Log.d(TAG, "Applying DXVK content profile: $dxvkWrapper -> ${ContentsManager.getEntryName(dxvkProfile)}")
                    contentsManager.applyContent(dxvkProfile)
                    extractD8VKIfNeeded(dxvkWrapper, windowsDir)
                } else {
                    // Match WinNative XSDA: no fake ARCHIVE/Wine-builtin substitute.
                    // Real DXVK must be installed via ContentsManager (amphora bundles
                    // Dxvk-*.wcp through ContentSource.resolve(DXVK)).
                    Log.w(TAG, "DXVK content profile not installed; no bundled DXVK archive will be loaded: $dxvkWrapper")
                }
            } else {
                Log.i(TAG, "Launch DXVK selected: None; restoring non-D3D12 wrapper files")
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, *nonD3D12WrapperDlls)
            }

            if (vkd3dWrapper.contains("None")) {
                Log.i(TAG, "Launch VKD3D selected: None; restoring original d3d12")
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, *d3d12Dlls)
            } else {
                applyVkd3dWrapper(vkd3dWrapper)
            }

            Log.d(TAG, "Extracting nglide wrapper")
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "ddrawrapper/nglide.tzst", windowsDir)

            // Clear any stale D7VK passthrough DLL left from a previous selection.
            val syswow64Dir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/syswow64")
            val system32Dir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/system32")
            File(syswow64Dir, "ddraw_.dll").delete()
            File(system32Dir, "ddraw_.dll").delete()

            val d7vkProfile = findD7vkProfileForDdrawrapper(ddrawrapper)
            if (d7vkProfile != null) {
                Log.d(TAG, "Applying D7VK ddraw wrapper: $ddrawrapper")
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, "ddraw.dll", "d3dimm.dll")
                val origDdraw = File(syswow64Dir, "ddraw.dll")
                val renamedDdraw = File(syswow64Dir, "ddraw_.dll")
                if (origDdraw.exists()) FileUtils.copy(origDdraw, renamedDdraw)
                contentsManager.applyContent(d7vkProfile)
            } else if (ddrawrapper.equals("none", ignoreCase = true) || ddrawrapper.contains("None")) {
                Log.d(TAG, "No DDRaw wrapper has been selected, restoring original ddraw files")
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, "ddraw.dll", "d3dimm.dll")
            } else {
                if (ddrawrapper == "cnc-ddraw") {
                    envState.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini")
                }
                Log.d(TAG, "Extracting ddrawrapper $ddrawrapper")
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context,
                    "ddrawrapper/$ddrawrapper.tzst",
                    windowsDir,
                )
            }
            Log.d(TAG, "Finished extraction of DXVK wrapper files, version: $dxwrapper")
        } else if (dxwrapper.contains("wined3d")) {
            val vkd3dWrapper = findDelimitedWrapper(dxwrapper, "vkd3d-")
            if (vkd3dWrapper != null) {
                Log.d(TAG, "Restoring non-D3D12 wrapper files for WineD3D+VKD3D.")
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, *nonD3D12WrapperDlls)
                applyVkd3dWrapper(vkd3dWrapper)
            } else {
                Log.d(TAG, "Restoring original DLL files for wined3d.")
                WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, *dlls)
            }
        }
    }

    private fun applyVkd3dWrapper(vkd3dWrapper: String?) {
        if (vkd3dWrapper == null || vkd3dWrapper.contains("None")) {
            Log.i(TAG, "Launch VKD3D selected: None; restoring original d3d12")
            WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, "d3d12.dll", "d3d12core.dll")
            return
        }
        val vkd3dProfile = contentsManager.getProfileByEntryName(vkd3dWrapper)
        if (vkd3dProfile != null) {
            Log.i(TAG, "Loading VKD3D content profile: $vkd3dWrapper")
            contentsManager.applyContent(vkd3dProfile)
        } else {
            Log.w(TAG, "VKD3D content profile not installed; no bundled VKD3D archive will be loaded: $vkd3dWrapper")
        }
    }

    private fun findD7vkProfileForDdrawrapper(ddrawrapper: String): ContentProfile? {
        val profiles = contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_D7VK) ?: return null
        for (profile in profiles) {
            if (StringUtils.parseIdentifier(ContentsManager.getEntryName(profile)) == ddrawrapper) return profile
        }
        return null
    }

    private fun findDelimitedWrapper(value: String?, prefix: String): String? {
        if (value == null) return null
        for (part in value.split(";")) {
            if (part.startsWith(prefix)) return part
        }
        return null
    }

    private fun hasSelectedVkd3dVersion(version: String?): Boolean =
        version != null && version.isNotEmpty() && !version.equals("None", ignoreCase = true)

    private fun hasSelectedDxvkWrapper(dxvkWrapper: String?): Boolean {
        if (dxvkWrapper == null) return false
        val version = if (dxvkWrapper.startsWith("dxvk-")) dxvkWrapper.substring("dxvk-".length) else dxvkWrapper
        return version.trim().isNotEmpty() && !version.equals("None", ignoreCase = true)
    }

    /**
     * Resolve a DXVK [ContentProfile] from the delimited-form token
     * (`dxvk-<verName>-<verCode>` or full `DXVK-...` entry name). Tries
     * [ContentsManager.getProfileByEntryName] first, then the XSDA
     * [resolveContentProfile] fallback (type + version-after-first-dash).
     */
    private fun resolveDxvkProfile(dxvkWrapper: String): ContentProfile? {
        contentsManager.getProfileByEntryName(dxvkWrapper)?.let { return it }
        val version = if (dxvkWrapper.startsWith("dxvk-", ignoreCase = true)) {
            dxvkWrapper.substringAfter('-')
        } else {
            dxvkWrapper
        }
        return resolveContentProfile(ContentProfile.ContentType.CONTENT_TYPE_DXVK, version)
    }

    private fun extractD8VKIfNeeded(dxvkWrapper: String, windowsDir: File) {
        if (compareVersion(dxvkWrapper, "2.4") >= 0) return
        Log.d(TAG, "Extracting d8vk as part of DXVK version $dxvkWrapper")
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, D8VK_ASSET_PATH, windowsDir)
    }

    /**
     * amphora: install the bundled wrapper ICD into the adrenotools content
     * directory (`filesDir/contents/adrenotools/<id>/`) so the host-side
     * `VulkanRenderer` can load it via `adrenotools_open_libvulkan`. Used for
     * the default [GraphicsDriverIds.WRAPPER] id only — optional WN-Turnip
     * packages are installed by [TurnipDriverProvisioner] and must not be
     * overwritten here.
     *
     * `wrapper.tzst` (extracted into `imagefs/usr/lib/` by
     * [extractGraphicsDriverFilesCore]) ships `libvulkan_wrapper.so` and the
     * ICD JSON; the adrenotools loader expects the `.so` plus a `meta.json`
     * (with `libraryName`) at its own content path. This bridges the two
     * locations — without it, the host falls back to the system Adreno driver
     * while the guest uses the wrapper ICD, producing a black screen.
     */
    private fun installAdrenotoolsDriverIfNeeded(driverId: String, libraryName: String) {
        if (driverId.isEmpty()) {
            Log.w(TAG, "installAdrenotoolsDriverIfNeeded: empty driverId, skipping")
            return
        }
        val adrenotoolsDir = File(context.filesDir, "contents/adrenotools/$driverId")
        val resolvedLibraryName = if (libraryName.isNotEmpty()) libraryName else "libvulkan_wrapper.so"
        val metaFile = File(adrenotoolsDir, "meta.json")
        val dstDriver = File(adrenotoolsDir, resolvedLibraryName)
        val srcDriver = File(imageFs.getLibDir(), resolvedLibraryName)
        val imagePin = readWrapperPinMarker(imageFs.getRootDir())
        val adrenotoolsPinMarker = File(adrenotoolsDir, WRAPPER_PIN_MARKER)
        val adrenotoolsPin = adrenotoolsPinMarker.takeIf { it.isFile }?.readText()?.trim()
        val pinMismatch = imagePin != null && !imagePin.equals(adrenotoolsPin, ignoreCase = true)
        val sizeMismatch =
            srcDriver.exists() && dstDriver.exists() && srcDriver.length() != dstDriver.length()
        // Require both meta + .so — a lone meta.json from a partial install would
        // otherwise skip forever and leave the host on the system Adreno driver.
        // Also refresh when imagefs wrapper pin/size moved (content_manifest bump).
        val alreadyInstalled =
            metaFile.exists() && dstDriver.exists() && !pinMismatch && !sizeMismatch
        if (!alreadyInstalled) {
            if (!adrenotoolsDir.exists() && !adrenotoolsDir.mkdirs()) {
                Log.w(TAG, "installAdrenotoolsDriverIfNeeded: failed to mkdir $adrenotoolsDir")
                return
            }

            // Source: the wrapper.tzst extract landed the driver .so at imagefs/usr/lib/.
            if (!srcDriver.exists()) {
                Log.w(TAG, "installAdrenotoolsDriverIfNeeded: driver .so not found at $srcDriver — re-extracting wrapper.tzst")
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, WRAPPER_ASSET, imageFs.getRootDir())
                writeWrapperPinMarker(imageFs.getRootDir())
            }
            if (srcDriver.exists()) {
                if (FileUtils.copy(srcDriver, dstDriver)) {
                    Log.i(TAG, "Installed Adrenotools driver .so: $srcDriver -> $dstDriver")
                } else {
                    Log.e(TAG, "installAdrenotoolsDriverIfNeeded: copy failed $srcDriver -> $dstDriver")
                    return
                }
            } else {
                Log.e(TAG, "installAdrenotoolsDriverIfNeeded: driver .so still missing after re-extract: $srcDriver")
                return
            }

            // Write meta.json so AdrenotoolsManager.getLibraryName can report the
            // library to vulkan.c's JNI callback at nativeCreate time.
            val metaJson = """{"libraryName":"$resolvedLibraryName"}"""
            if (FileUtils.writeString(metaFile, metaJson)) {
                Log.i(TAG, "Wrote Adrenotools meta.json: $metaFile ($metaJson)")
            } else {
                Log.e(TAG, "installAdrenotoolsDriverIfNeeded: failed to write $metaFile")
            }
            val pin = readWrapperPinMarker(imageFs.getRootDir())
            if (pin != null) {
                adrenotoolsPinMarker.writeText(pin.lowercase())
            }
        } else {
            Log.d(TAG, "Adrenotools driver already installed: $driverId ($resolvedLibraryName)")
        }

        // Always (re)seed runtime deps — Turnip zip is so+meta only; without these
        // copies adrenotools' driver namespace cannot dlopen NEEDED libs from
        // imagefs and silently falls back to proprietary Adreno.
        seedAdrenotoolsRuntimeDeps(adrenotoolsDir)
    }

    /**
     * True when [WRAPPER_ASSET] in runtime-assets has a SHA that does not match
     * the sidecar written next to imagefs `libvulkan_wrapper.so`.
     */
    private fun wrapperImagefsNeedsRefresh(rootDir: File): Boolean {
        val so = File(rootDir, "usr/lib/libvulkan_wrapper.so")
        if (!so.isFile) return true
        val pin = runtimeWrapperPin() ?: return false
        val current = readWrapperPinMarker(rootDir) ?: return true
        return !pin.equals(current, ignoreCase = true)
    }

    private fun runtimeWrapperPin(): String? {
        val marker = File(
            RuntimeAssetProvisioner.runtimeAssetsDir(context),
            "$WRAPPER_ASSET.sha256",
        )
        if (!marker.isFile) return null
        val digest = marker.readText().trim()
        return digest.takeIf { it.length == 64 }
    }

    private fun readWrapperPinMarker(rootDir: File): String? {
        val marker = File(rootDir, "usr/lib/$WRAPPER_PIN_MARKER")
        if (!marker.isFile) return null
        return marker.readText().trim().takeIf { it.length == 64 }
    }

    private fun writeWrapperPinMarker(rootDir: File) {
        val pin = runtimeWrapperPin() ?: return
        val marker = File(rootDir, "usr/lib/$WRAPPER_PIN_MARKER")
        marker.parentFile?.mkdirs()
        marker.writeText(pin.lowercase())
        Log.i(TAG, "Recorded wrapper pin $pin at ${marker.path}")
    }

    /**
     * Copy imagefs libs that wrapper/Turnip `DT_NEEDED` into the adrenotools
     * driver dir. The custom-driver linker namespace searches `customDriverDir`
     * (+ hooks dir), not guest `LD_LIBRARY_PATH`, so missing copies look like
     * "Turnip selected" while DXVK still reports `Wrapper(Adreno …)`.
     */
    private fun seedAdrenotoolsRuntimeDeps(adrenotoolsDir: File) {
        if (!adrenotoolsDir.isDirectory) return
        val imageFsLibDir = imageFs.getLibDir()
        val deps = listOf(
            // SysV shmem — must export libandroid_shm* (see imagefs android-sysvshm).
            "libandroid-sysvshm.so",
            // Compression / C++ — Turnip + wrapper.
            "libc++_shared.so", "libz.so.1", "libz.so", "libzstd.so.1",
            // X11 / DRM / sync — Mesa WSI.
            "libxcb.so", "libX11-xcb.so", "libxcb-dri3.so", "libxcb-present.so",
            "libxcb-sync.so", "libxcb-randr.so", "libxcb-shm.so", "libdrm.so",
            "libxshmfence.so", "libexpat.so.1", "libxkbcommon.so",
        )
        var copiedDeps = 0
        for (dep in deps) {
            val src = File(imageFsLibDir, dep)
            if (!src.exists()) {
                Log.w(TAG, "seedAdrenotoolsRuntimeDeps: dep $dep not found at $src (skipping)")
                continue
            }
            val dst = File(adrenotoolsDir, dep)
            // Refresh when imagefs was hot-fixed (e.g. libandroid_shm* aliases).
            if (dst.exists() && dst.length() == src.length() && dst.lastModified() >= src.lastModified()) {
                continue
            }
            if (FileUtils.copy(src, dst)) copiedDeps++ else {
                Log.w(TAG, "seedAdrenotoolsRuntimeDeps: copy failed for dep $dep")
            }
        }
        if (copiedDeps > 0) {
            Log.i(TAG, "Seeded $copiedDeps adrenotools runtime dep(s) into $adrenotoolsDir")
        }
    }

    /**
     * `hookLibDir` for **guest** adrenotools (env consumed by imagefs
     * `libvulkan_wrapper.so` → `libadrenotools.so`).
     *
     * Must match the guest `libadrenotools.so` vintage: that blob ships in
     * `wrapper.tzst` / imagefs and only successfully `dlopen`s the hook set
     * extracted beside it under `imageFs.getLibDir()`. Pointing at APK
     * `nativeLibraryDir` (our submodule hooks) makes guest adrenotools log
     * `Couldn't preload the hook implementation`, so Turnip never loads and
     * every DXVK path returns `vkCreateInstance -9`.
     *
     * Host [VulkanRenderer] still uses `nativeLibraryDir` via JNI — that path
     * links adrenotools into `libwinlator.so` and is a separate binary.
     */
    private fun adrenotoolsHooksPath(): String = imageFs.getLibDir().path

    private fun compareVersion(varA: String, varB: String): Int {
        val a = parseSemverLoose(varA)
        val b = parseSemverLoose(varB)
        if (a[0] != b[0]) return a[0] - b[0]
        if (a[1] != b[1]) return a[1] - b[1]
        return a[2] - b[2]
    }

    private fun parseSemverLoose(s: String?): IntArray {
        if (s == null) return intArrayOf(0, 0, 0)
        val m = SEMVER_LOOSE.matcher(s)
        var g1: String? = null
        var g2: String? = null
        var g3: String? = null
        while (m.find()) {
            g1 = m.group(1)
            g2 = m.group(2)
            g3 = m.group(3)
        }
        if (g1 == null || g2 == null) return intArrayOf(0, 0, 0)
        return intArrayOf(safeParseInt(g1), safeParseInt(g2), safeParseInt(g3))
    }

    private fun safeParseInt(s: String?): Int {
        if (s.isNullOrEmpty()) return 0
        return try {
            s.toInt()
        } catch (e: NumberFormatException) {
            0
        }
    }

    // --- extractGraphicsDriverFiles (XSDA L7537) ------------------------------

    private fun extractGraphicsDriverFilesCore() {
        // Launcher SharedPreferences is the source of truth for adrenotools id
        // (selectGraphicsDriver only writes prefs). Container version= can lag;
        // using a stale wrapper id here leaves ADRENOTOOLS_DRIVER_* unset so the
        // guest silently runs proprietary Adreno (DX9 FF PSO -13 with HUD).
        val prefsDriverId = GraphicsDriverIds.normalize(
            context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, null),
        )
        val containerDriverId = GraphicsDriverIds.normalize(graphicsDriverConfig["version"])
        val adrenoToolsDriverId = prefsDriverId
        if (adrenoToolsDriverId != containerDriverId) {
            Log.i(
                TAG,
                "Using prefs adrenotools id '$adrenoToolsDriverId' (container had '$containerDriverId')",
            )
            graphicsDriverConfig["version"] = adrenoToolsDriverId
            // Persist so host VulkanRenderer (reads container) stays in sync with guest.
            val c = wnContainer
            if (c != null) {
                c.setGraphicsDriverConfig(
                    GraphicsDriverConfigUtils.toGraphicsDriverConfig(graphicsDriverConfig),
                )
                c.saveData()
            }
        }
        Log.i(TAG, "Launch graphics driver selected: graphicsDriver='$graphicsDriver' driverId='$adrenoToolsDriverId'")

        // applyPreferredRefreshRate() STRIPPED (D9: Activity/Window refresh UI -> Compose P3).

        val rootDir = imageFs.getRootDir()

        if (dxwrapper.contains("dxvk")) {
            val refreshRateOverride = getDxvkFrameRateOverride()
            DXVKConfigUtils.setEnvVars(context, dxwrapperConfig, envState, refreshRateOverride)
            val version = dxwrapperConfig.get("version")
            if (version == "1.11.1-sarek") {
                Log.d(TAG, "Disabling Wrapper PATCH_OPCONSTCOMP SPIR-V pass")
                envState.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1")
            }
        }
        // Always set WINE_D3D_CONFIG. DXVK replaces d3d8/9/10/11 DLLs, but ddraw/DX7
        // and any residual WineD3D path still read this (renderer=gl → Zink).
        // Previously skipped whenever dxwrapper contained "dxvk", leaving DX7 without
        // csmt/FBO/renderer knobs on the OpenGL→Zink stack.
        WineD3DConfigUtils.setEnvVars(context, dxwrapperConfig, envState)

        envState.put("GALLIUM_DRIVER", "zink")
        envState.put("LIBGL_KOPPER_DISABLE", "true")

        val wrapperPinChanged = wrapperImagefsNeedsRefresh(rootDir)
        if (firstTimeBoot) {
            Log.d(TAG, "First time container boot, re-extracting libs")
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, WRAPPER_ASSET, rootDir)
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "layers.tzst", rootDir)
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/extra_libs.tzst", rootDir)
            // D5: arm64ec zink_dlls branch stripped (wineInfo.isArm64EC() always false for x86_64).
            writeWrapperPinMarker(rootDir)
        } else if (wrapperPinChanged) {
            // runtimeAssets pin can move without firstTimeBoot (content_manifest bump).
            // Without this, devices keep a stale imagefs/usr/lib/libvulkan_wrapper.so.
            Log.i(TAG, "wrapper.tzst pin changed; re-extracting into imagefs")
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, WRAPPER_ASSET, rootDir)
            writeWrapperPinMarker(rootDir)
        }

        val wantLeegao = "wrapper-leegao" == graphicsDriver
        val leegaoMarker = File(rootDir, "usr/lib/.wrapper_leegao")
        if (wantLeegao) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/wrapper-leegao.tzst", rootDir)
            try { leegaoMarker.createNewFile() } catch (e: IOException) { /* ignored */ }
        } else if (leegaoMarker.exists()) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, WRAPPER_ASSET, rootDir)
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "layers.tzst", rootDir)
            writeWrapperPinMarker(rootDir)
            leegaoMarker.delete()
        }

        if (adrenoToolsDriverId.isNotEmpty() && adrenoToolsDriverId != "System") {
            val adrenotoolsManager = AdrenotoolsManager(context)
            val driverLibrary = adrenotoolsManager.getLibraryName(adrenoToolsDriverId)
            Log.i(TAG, "Loading graphics/Turnip driver: id='$adrenoToolsDriverId' library='$driverLibrary'")
            // Only seed wrapper.so from imagefs for the bundled "wrapper" id.
            // Optional WN-Turnip packages are installed by TurnipDriverProvisioner
            // and must not be overwritten with libvulkan_wrapper.so.
            if (adrenoToolsDriverId == "wrapper" ||
                driverLibrary.isEmpty() ||
                driverLibrary == "libvulkan_wrapper.so"
            ) {
                installAdrenotoolsDriverIfNeeded(
                    adrenoToolsDriverId,
                    if (driverLibrary.isNotEmpty()) driverLibrary else "libvulkan_wrapper.so",
                )
            }
            val libraryName = adrenotoolsManager.getLibraryName(adrenoToolsDriverId)
                .ifEmpty { driverLibrary }
            // Guest: wrapper ICD + adrenotools backend. For the default wrapper
            // id leave PATH/NAME unset so wrapper uses system Adreno. For an
            // optional Turnip package (libraryName=libvulkan_freedreno.so), set
            // PATH/NAME/HOOKS like WinNative setDriverById.
            if (libraryName == "libvulkan_freedreno.so" &&
                adrenotoolsManager.isInstalled(adrenoToolsDriverId)
            ) {
                val driverDir = adrenotoolsManager.getDriverDir(adrenoToolsDriverId)
                // Turnip zip is so+meta only — seed NEEDED libs from imagefs every
                // launch (same namespace constraint as installAdrenotoolsDriverIfNeeded).
                seedAdrenotoolsRuntimeDeps(driverDir)
                envState.put("ADRENOTOOLS_DRIVER_PATH", driverDir.path + "/")
                envState.put("ADRENOTOOLS_DRIVER_NAME", libraryName)
                envState.put("ADRENOTOOLS_HOOKS_PATH", adrenotoolsHooksPath())
                envState.put("ADRENOTOOLS_DRIVER_CUSTOM", "1")
                Log.i(
                    TAG,
                    "Guest adrenotools backend: PATH=${driverDir.path} NAME=$libraryName",
                )
            } else if (wantLeegao) {
                envState.put("ADRENOTOOLS_HOOKS_PATH", adrenotoolsHooksPath())
            }
        } else {
            Log.w(
                TAG,
                "No Adrenotools driver applied (id='$adrenoToolsDriverId' graphicsDriver='$graphicsDriver')" +
                    " - system Vulkan driver will be used",
            )
        }

        envState.put("VK_ICD_FILENAMES", imageFs.getShareDir().path + "/vulkan/icd.d/wrapper_icd.aarch64.json")

        var vulkanVersion = graphicsDriverConfig["vulkanVersion"]
        if (vulkanVersion == null) vulkanVersion = "1.3"
        try {
            val fullVkVersion = GPUInformation.getVulkanVersion(adrenoToolsDriverId, context)
            if (fullVkVersion != null && fullVkVersion.contains(".")) {
                val parts = fullVkVersion.split("\\.".toRegex())
                if (parts.size >= 3) vulkanVersion = "$vulkanVersion.${parts[2]}"
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error getting Vulkan version patch", e)
        }
        envState.put("WRAPPER_VK_VERSION", vulkanVersion)

        val blacklistedExtensions = graphicsDriverConfig["blacklistedExtensions"] ?: ""
        envState.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions)

        val gpuName = graphicsDriverConfig["gpuName"]
        val dxvkVersion = dxwrapperConfig.get("version")
        if (gpuName != null && gpuName != "Device" && dxvkVersion != null && dxvkVersion != "1.11.1-sarek") {
            envState.put("WRAPPER_DEVICE_NAME", gpuName)
            envState.put("WRAPPER_DEVICE_ID", WineD3DConfigUtils.getDeviceIdFromGPUName(context, gpuName))
            envState.put("WRAPPER_VENDOR_ID", WineD3DConfigUtils.getVendorIdFromGPUName(context, gpuName))
        }

        val maxDeviceMemory = graphicsDriverConfig["maxDeviceMemory"]
        if (maxDeviceMemory != null && maxDeviceMemory.toInt() > 0) {
            envState.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory)
        }

        var presentMode = graphicsDriverConfig["presentMode"]
        if (presentMode.isNullOrEmpty()) presentMode = "mailbox"
        if (presentMode.contains("immediate")) envState.put("WRAPPER_MAX_IMAGE_COUNT", "1")
        envState.put("MESA_VK_WSI_PRESENT_MODE", presentMode)

        val resourceType = graphicsDriverConfig["resourceType"] ?: ""
        envState.put("WRAPPER_RESOURCE_TYPE", resourceType)

        val wsiDebugFlags = ArrayList<String>()
        val syncFrame = graphicsDriverConfig["syncFrame"]
        if ("1" == syncFrame) wsiDebugFlags.add("forcesync")
        if (wsiDebugFlags.isNotEmpty()) envState.put("MESA_VK_WSI_DEBUG", wsiDebugFlags.joinToString(","))

        val disablePresentWait = graphicsDriverConfig["disablePresentWait"] ?: ""
        envState.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait)

        val bcnEmulation = graphicsDriverConfig["bcnEmulation"] ?: ""
        val bcnEmulationType = graphicsDriverConfig["bcnEmulationType"] ?: ""
        when (bcnEmulation) {
            "auto" -> {
                if ("compute" == bcnEmulationType && GPUInformation.getVendorID(null, null) != 20803) {
                    envState.put("ENABLE_BCN_COMPUTE", "1")
                    envState.put("BCN_COMPUTE_AUTO", "1")
                }
                envState.put("WRAPPER_EMULATE_BCN", "3")
            }
            "full" -> {
                if ("compute" == bcnEmulationType && GPUInformation.getVendorID(null, null) != 20803) {
                    envState.put("ENABLE_BCN_COMPUTE", "1")
                    envState.put("BCN_COMPUTE_AUTO", "0")
                }
                envState.put("WRAPPER_EMULATE_BCN", "2")
            }
            "none" -> envState.put("WRAPPER_EMULATE_BCN", "0")
            else -> envState.put("WRAPPER_EMULATE_BCN", "1")
        }

        val bcnEmulationCache = graphicsDriverConfig["bcnEmulationCache"] ?: ""
        envState.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache)
    }

    // --- helpers --------------------------------------------------------------

    /** applyGeneralPatches (XSDA L10793). */
    private fun applyGeneralPatches(c: Container) {
        val rootDir = imageFs.getRootDir()
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "container_pattern_common.tzst", rootDir)
        // MVP is ALSA-only; pulseaudio.tzst extract omitted (nobody consumed filesDir/pulseaudio).
        WineUtils.applySystemTweaks(context, wineInfo)
        c.putExtra("graphicsDriver", null)
        c.putExtra("desktopTheme", null)
    }

    /** wipeDxwrapperDllsForReextract (XSDA L7950). */
    private fun wipeDxwrapperDllsForReextract() {
        val rootDir = imageFs.getRootDir()
        val system32 = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/system32")
        val syswow64 = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/syswow64")
        var deleted = 0
        for (name in DXWRAPPER_DLLS) {
            if (name == "d3d10.dll" || name == "d3d10_1.dll" || name == "d3d8.dll" || name == "d3dimm.dll") continue
            val a = File(system32, name)
            val b = File(syswow64, name)
            if (a.exists() && a.delete()) deleted++
            if (b.exists() && b.delete()) deleted++
        }
        if (deleted > 0) {
            Log.i(TAG, "DXVK/VKD3D pre-extract wipe removed $deleted stale DLL(s) from system32/syswow64")
        }
    }

    /**
     * Copies the pinned AIO Graphics Test executables into the paths referenced
     * by `metadata/startmenu.json`.
     *
     * RuntimeAssetProvisioner downloads and verifies the source files before
     * preparation. A digest sidecar prevents rewriting ~4.7 MB on every launch
     * while still replacing same-sized binaries when the manifest pin changes.
     */
    private fun stageGraphicsTestExes(container: Container) {
        val destinationDir = File(
            container.getRootDir(),
            ".wine/drive_c/ProgramData/Microsoft/Windows",
        )
        if (!destinationDir.isDirectory && !destinationDir.mkdirs()) {
            Log.e(TAG, "Unable to create graphics test directory: $destinationDir")
            return
        }

        val runtimeRoot = RuntimeAssetProvisioner.runtimeAssetsDir(context)
        for (assetPath in GRAPHICS_TEST_ASSETS) {
            val source = File(runtimeRoot, assetPath)
            val sourceMarker = File(source.absolutePath + ".sha256")
            if (!source.isFile || !sourceMarker.isFile) {
                Log.e(TAG, "Verified graphics test asset is missing: $assetPath")
                continue
            }

            val destination = File(destinationDir, FileUtils.getName(assetPath))
            val destinationMarker = File(destination.absolutePath + ".sha256")
            val digest = sourceMarker.readText().trim()
            if (destination.isFile &&
                destination.length() == source.length() &&
                destinationMarker.isFile &&
                destinationMarker.readText().trim().equals(digest, ignoreCase = true)
            ) {
                continue
            }

            if (FileUtils.copy(source, destination)) {
                destinationMarker.writeText(digest)
                Log.i(TAG, "Staged ${destination.name} (${destination.length()} bytes)")
            } else {
                Log.e(TAG, "Failed to stage graphics test asset: $assetPath")
            }
        }
    }

    /**
     * getDxvkFrameRateOverride (XSDA L627) -- stubbed to 0. The XSDA version reads
     * per-game/global refresh-rate overrides from the shortcut + SharedPreferences;
     * Amphora has neither (D9: no shortcuts). Wire to container/user prefs when
     * frame-rate limiting lands.
     */
    private fun getDxvkFrameRateOverride(): Int = 0

    /** getActiveGameDirectoryPath (XSDA L2043) -- shortcut-only; returns null (D9). */
    private fun getActiveGameDirectoryPath(): String? = null

    /** isSteamShortcut -- D9: Steam is a non-target. */
    private fun isSteamShortcut(): Boolean = false

    private fun contentVersionIdentifier(profile: ContentProfile): String {
        val entryName = ContentsManager.getEntryName(profile)
        val firstDash = entryName.indexOf('-')
        return if (firstDash >= 0) entryName.substring(firstDash + 1) else entryName
    }

    /** resolveContentProfile (XSDA L6398). */
    private fun resolveContentProfile(type: ContentProfile.ContentType, version: String): ContentProfile? {
        val byEntry = contentsManager.getProfileByEntryName("$type-$version")
        if (byEntry != null) return byEntry
        val profiles = contentsManager.getProfiles(type) ?: return null
        for (candidate in profiles) {
            if (version == contentVersionIdentifier(candidate)) return candidate
        }
        return null
    }

    /** pickNewestInstalledContentVersion (XSDA L6410). */
    private fun pickNewestInstalledContentVersion(type: ContentProfile.ContentType): String {
        val profiles = contentsManager.getProfiles(type) ?: return ""
        var best: ContentProfile? = null
        for (profile in profiles) {
            if (!profile.isInstalled) continue
            if (best == null ||
                profile.verCode > best.verCode ||
                (profile.verCode == best.verCode && profile.verName != null && best.verName != null &&
                    profile.verName.compareTo(best.verName, ignoreCase = true) > 0)
            ) {
                best = profile
            }
        }
        return if (best != null) contentVersionIdentifier(best) else ""
    }

    private companion object {
        private const val TAG = "WineSessionPreparer"
        private const val D8VK_ASSET_PATH = "dxwrapper/d8vk-1.0.tzst"
        private const val WRAPPER_ASSET = "graphics_driver/wrapper.tzst"
        /** Sidecar under imagefs/usr/lib recording the runtime-assets wrapper pin. */
        private const val WRAPPER_PIN_MARKER = "libvulkan_wrapper.so.wrapper.sha256"
        private val GRAPHICS_TEST_ASSETS = arrayOf(
            "winnative/Graphics-Test-32bit.exe",
            "winnative/Graphics-Test-64bit.exe",
        )
        private val DXWRAPPER_DLLS = arrayOf(
            "d3d10.dll", "d3d10_1.dll", "d3d10core.dll",
            "d3d11.dll", "d3d12.dll", "d3d12core.dll",
            "d3d8.dll", "d3d9.dll", "dxgi.dll",
            "ddraw.dll", "d3dimm.dll",
        )
        private val SEMVER_LOOSE = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?")

        private fun appVersionCode(context: Context): String {
            return try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                PackageInfoCompat.getLongVersionCode(info).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                "0"
            }
        }
    }
}
