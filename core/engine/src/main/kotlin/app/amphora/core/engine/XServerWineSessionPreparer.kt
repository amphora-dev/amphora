package app.amphora.core.engine

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.pm.PackageInfoCompat
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.model.Container as AmphoraContainer
import app.amphora.core.content.AppliedAssetPin
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.engine.model.LaunchSpec
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.WinComponentSetup
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.environment.components.PulseAudioRuntimeSupport
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

internal val CNC_DDRAW_SHADER_SIDECARS =
    listOf(
        "Shaders/crt/crt-lottes-fast-no-warp-bilinear.glsl",
        "Shaders/interpolation/bilinear.glsl",
        "Shaders/interpolation/catmull-rom-bilinear.glsl",
        "Shaders/interpolation/fsr.glsl",
        "Shaders/interpolation/fsr.glsl.pass1",
        "Shaders/interpolation/jinc2-dedither.glsl",
        "Shaders/interpolation/lanczos2-sharp.glsl",
        "Shaders/nearest-neighbor.glsl",
        "Shaders/readme.txt",
        "Shaders/scanlines/scanline.glsl",
        "Shaders/sharpen/rca-sharpen.glsl",
        "Shaders/xbr/xbr-lv2-noblend.glsl",
        "Shaders/xbrz/xbrz-freescale-multipass.glsl",
        "Shaders/xbrz/xbrz-freescale-multipass.glsl.pass1",
    )

internal fun directDrawInstallComplete(selectedDdraw: String, syswow64Dir: File): Boolean {
    if (!File(syswow64Dir, "ddraw.dll").isFile) return false
    return when (selectedDdraw) {
        DirectDrawWrapperIds.CNC_DDRAW ->
            File(syswow64Dir, "ddraw.ini").isFile &&
                CNC_DDRAW_SHADER_SIDECARS.all { File(syswow64Dir, it).isFile }
        DirectDrawWrapperIds.DXWRAPPER_DD7TO9 ->
            File(syswow64Dir, "dxwrapper.dll").isFile &&
                File(syswow64Dir, "dxwrapper.ini").isFile
        DirectDrawWrapperIds.D7VK -> File(syswow64Dir, "ddraw_.dll").isFile
        else -> false
    }
}

internal fun resolveSessionAudioDriver(
    requested: String,
    pulsePlatformSupported: Boolean,
    pulseWineDriverAvailable: Boolean,
): String = if (
    requested == AdvancedRuntimePreferences.AUDIO_DRIVER_PULSEAUDIO &&
    pulsePlatformSupported &&
    pulseWineDriverAvailable
) {
    AdvancedRuntimePreferences.AUDIO_DRIVER_PULSEAUDIO
} else {
    AdvancedRuntimePreferences.AUDIO_DRIVER_ALSA
}

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
            // setupWineSystemFiles is the launch boundary. getOrCreate may have
            // reconciled prefs into .container through another ContainerManager,
            // so discard this singleton preparer's previous in-memory snapshot.
            wnContainer = null
            resolveState(spec, container)
            setupWineSystemFilesCore()
        }

    override suspend fun ensureWinePrefixReady(container: AmphoraContainer) = withContext(dispatchers.io) {
        resolveState(null, container)
        ensureWinePrefixReadyCore()
    }

    override suspend fun ensureLaunchRuntimeFilesReady(container: AmphoraContainer) = withContext(dispatchers.io) {
        resolveState(null, container)
        ensureLaunchRuntimeFilesReadyCore()
    }

    override suspend fun ensureWinePrefixEssentialFiles(container: AmphoraContainer) = withContext(dispatchers.io) {
        resolveState(null, container)
        ensureWinePrefixEssentialFilesCore()
        Unit
    }

    override suspend fun extractDXWrapperFiles(container: AmphoraContainer, dxwrapper: String) =
        withContext(dispatchers.io) {
            resolveState(null, container)
            extractDXWrapperFilesCore(dxwrapper)
        }

    override suspend fun extractGraphicsDriverFiles(container: AmphoraContainer) = withContext(dispatchers.io) {
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
        val imageState =
            "$imgVersion|assets=${runtimeFingerprint(listOf(SharedContainerFonts.ASSET_PATH))}"
        var containerDataChanged = false

        // 唯一方式：想要(容器) ≠ 装过(AppliedMarks) → 去做 → 更新标记
        if (AppliedMarks.needsAppImagePatch(c, appVersion, imageState)) {
            Log.d(TAG, "版本或内容变更，打通用补丁 (app=$appVersion image=$imageState)")
            applyGeneralPatches(c)
            AppliedMarks.markAppImagePatched(c, appVersion, imageState)
            firstTimeBoot = true
            containerDataChanged = true
        }

        if (ensureWinePrefixEssentialFilesCore()) {
            containerDataChanged = true
        }

        // 只认分号格式：dxvk-…;vkd3d-…;<DirectDraw layer>
        var localDxwrapper = dxwrapper
        val selection = DxWrapperSelection.parse(localDxwrapper)
        if (selection == null) {
            Log.e(TAG, "dxwrapper 必须是分号格式，当前='$localDxwrapper'")
        } else {
            Log.i(
                TAG,
                "DX 包装: dxvk='${selection.dxvk}' vkd3d='${selection.vkd3d}' ddraw='${selection.ddraw}'",
            )
            localDxwrapper = selection.asDelimited()
        }

        val wincomponents = c.getWinComponents()
        val appliedWincomponents = AppliedMarks.wincomponents(c)
        val previousWincomponents = appliedWincomponents.substringBefore("|assets=")
        val wincomponentsKey =
            "$wincomponents|assets=${runtimeFingerprint(WINCOMPONENT_RUNTIME_ASSETS)}"
        val wincomponentAssetsChanged =
            appliedWincomponents.isNotEmpty() &&
                previousWincomponents == wincomponents &&
                appliedWincomponents != wincomponentsKey
        if (AppliedMarks.needsWincomponents(c, wincomponentsKey) || firstTimeBoot) {
            WinComponentSetup.applyWinComponents(
                context,
                imageFs,
                wineInfo,
                c,
                wincomponents,
                previousWincomponents.ifEmpty { Container.FALLBACK_WINCOMPONENTS },
                firstTimeBoot || wincomponentAssetsChanged,
            )
            AppliedMarks.markWincomponents(c, wincomponentsKey)
            containerDataChanged = true
        }

        val wineArchKey = if (wineVersion.contains("arm64ec")) "arm64ec" else "x86_64"
        val dxwrapperGateKey =
            selection?.let { dxwrapperGateKey(it, wineArchKey) } ?: "$localDxwrapper|arch=$wineArchKey"
        val forceWrapperApply = sharedGraphicsLinksNeedRefresh(localDxwrapper)
        if (selection != null &&
            (AppliedMarks.needsDxwrapper(c, dxwrapperGateKey) || firstTimeBoot || forceWrapperApply)
        ) {
            Log.i(
                TAG,
                "DXVK/VKD3D extract: key='$dxwrapperGateKey' prev='${AppliedMarks.dxwrapperKey(c)}'" +
                    " firstTimeBoot=$firstTimeBoot forced=$forceWrapperApply",
            )
            val resolvedDxwrapper = resolveDxwrapperForExtract(localDxwrapper)
            if (resolvedDxwrapper == null) {
                Log.e(
                    TAG,
                    "DXVK/VKD3D extract aborted: required content profiles missing for '$localDxwrapper'",
                )
            } else {
                localDxwrapper = resolvedDxwrapper
                // Persist invalidation before deleting old files. A crash or a
                // failed apply must never leave the old success mark guarding a
                // partially-cleared wrapper tree.
                AppliedMarks.invalidateDxwrapper(c)
                c.saveData()
                wipeDxwrapperDllsForReextract()
                extractDXWrapperFilesCore(localDxwrapper)
                val resolvedSelection = DxWrapperSelection.parse(localDxwrapper)
                AppliedMarks.markDxwrapper(
                    c,
                    resolvedSelection?.let { dxwrapperGateKey(it, wineArchKey) }
                        ?: "$localDxwrapper|arch=$wineArchKey",
                )
                containerDataChanged = true
            }
        }

        startupSelection = c.getStartupSelection().toString()
        if (AppliedMarks.needsServices(c, startupSelection) || firstTimeBoot) {
            WineUtils.applyServiceStartupProfile(c, startupSelection)
            AppliedMarks.markServices(c, startupSelection)
            containerDataChanged = true
        }

        val requestedAudioDriver = AdvancedRuntimePreferences.audioDriver(context)
        val pulseDriverAvailable =
            File(wineInfo.path, "lib/wine/x86_64-unix/winepulse.so").isFile
        val pulsePlatformSupported = PulseAudioRuntimeSupport.isSupportedPlatform()
        val audioDriver =
            resolveSessionAudioDriver(
                requestedAudioDriver,
                pulsePlatformSupported,
                pulseDriverAvailable,
            )
        if (audioDriver != requestedAudioDriver) {
            Log.w(
                TAG,
                "PulseAudio unavailable (pageSizeSupported=$pulsePlatformSupported, " +
                    "winepulse=$pulseDriverAvailable); using ALSA",
            )
        }
        if (c.getAudioDriver() != audioDriver) {
            c.setAudioDriver(audioDriver)
            containerDataChanged = true
        }
        val wineAudio = WineUtils.wineAudioDriverName(audioDriver)
        if (wineAudio != null && (AppliedMarks.needsAudio(c, wineAudio) || firstTimeBoot)) {
            WineUtils.ensureWineAudioDriver(c, imageFs.getRootDir(), audioDriver)
            AppliedMarks.markAudio(c, wineAudio)
            containerDataChanged = true
        } else if (wineAudio == null) {
            Log.w(TAG, "未知音频驱动配置 '$audioDriver'，跳过注册表写入")
        }

        val startMenuState =
            "schema=$START_MENU_SCHEMA_VERSION|assets=${runtimeFingerprint(listOf(START_MENU_ASSET))}"
        val startMenuMarker = File(c.getRootDir(), ".startmenu")
        if (AppliedMarks.needsStartMenu(c, startMenuState) || !startMenuMarker.isFile) {
            WineStartMenuCreator.create(context, c)
            if (startMenuMarker.isFile) {
                AppliedMarks.markStartMenu(c, startMenuState)
                containerDataChanged = true
            }
        }
        stageGraphicsTestExes(c)

        val currentDrives = c.getDrives() ?: ""
        val drivesDesired = WineUtils.normalizePersistentDrives(context, currentDrives, true)
        if (drivesDesired != currentDrives) {
            c.setDrives(drivesDesired)
            containerDataChanged = true
            Log.i(TAG, "Storage volumes changed; reconciled Wine drives to: $drivesDesired")
        }
        val driveLinksMissing = !WineUtils.hasRequiredDosdevicesSymlinks(c)
        if (AppliedMarks.needsDrives(c, drivesDesired) || driveLinksMissing || firstTimeBoot) {
            if (driveLinksMissing && !firstTimeBoot) {
                Log.w(TAG, "Wine drive links are incomplete; rebuilding dosdevices")
            }
            WineUtils.createDosdevicesSymlinks(c, getActiveGameDirectoryPath(), isSteamShortcut())
            AppliedMarks.markDrives(c, c.getDrives() ?: drivesDesired)
            containerDataChanged = true
        }

        val inputType = c.getInputType()
        val dinputFlag = Container.FLAG_INPUT_TYPE_DINPUT.toInt()
        val dinputEnabled = (inputType and dinputFlag) == dinputFlag
        val exclusiveXInput = c.isExclusiveXInput()
        val inputKey = AppliedMarks.inputKey(inputType, exclusiveXInput)
        if (AppliedMarks.needsInput(c, inputKey) || firstTimeBoot) {
            WineUtils.ensureJoystickRegistryKeys(c, dinputEnabled, exclusiveXInput)
            AppliedMarks.markInput(c, inputKey)
            containerDataChanged = true
        }

        if (AppliedMarks.needsWinebus(c) || firstTimeBoot) {
            WineUtils.ensureWinebusConfig(c)
            AppliedMarks.markWinebus(c)
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
        val storedPrefixArch = AppliedMarks.wineprefixArch(c)
        val archMismatch = storedPrefixArch.isNotEmpty() &&
            !storedPrefixArch.equals(wineInfo.getArch(), ignoreCase = true)
        val prefixNeedsUpdate = AppliedMarks.prefixNeedsUpdate(c)
        Log.d(
            TAG,
            "ensureWinePrefixReady: prefixInvalid=$prefixInvalid archMismatch=$archMismatch" +
                " storedArch=$storedPrefixArch target=${wineInfo.getArch()} needsUpdate=$prefixNeedsUpdate",
        )

        if (!prefixInvalid && !archMismatch && !prefixNeedsUpdate) {
            if (storedPrefixArch.isEmpty()) {
                AppliedMarks.markWineprefixArch(c, wineInfo.getArch())
                AppliedMarks.clearPrefixNeedsUpdate(c)
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
        val repairedPrefixValid = repaired && WineUtils.isPrefixValid(containerDir)
        if (!repairedPrefixValid) {
            throw IllegalStateException(
                "Wine prefix repair failed validation for container ${c.id}: " +
                    "repairResult=$repaired prefixValid=${WineUtils.isPrefixValid(containerDir)}",
            )
        }
        firstTimeBoot = true
        Log.i(TAG, "Wine prefix repaired and validated successfully for container ${c.id}")
    }

    // --- ensureLaunchRuntimeFilesReady (XSDA L6280) + ensureBox64 (L6290) -----

    private fun ensureLaunchRuntimeFilesReadyCore() {
        val c = wnContainer ?: return
        // D5: arm64ec rejected -> box64 only (ensureArm64EcRuntimeDllsReady stripped).
        if (Box64Runtime.ensureApplied(c, imageFs, contentsManager)) {
            c.saveData()
        }
        // ALSA layout must exist before winealsa.so / android_aserver load.
        com.winlator.cmod.runtime.audio.AlsaRuntimeSupport.ensureImageFsLayout(imageFs.getRootDir())
    }

    /**
     * Confirm DXVK / VKD3D profiles exist (with newest-installed fallback) and
     * rewrite container tokens when they drifted. Returns the token to extract,
     * or null when a required profile is still missing — caller must not wipe.
     */
    private fun resolveDxwrapperForExtract(dxwrapper: String): String? {
        val c = wnContainer ?: return null
        val parts = dxwrapper.split(";")
        val dxvkToken = parts.getOrNull(0).orEmpty()
        val vkd3dToken = parts.getOrNull(1).orEmpty()
        val ddraw = parts.getOrNull(2).orEmpty()
        var changed = false

        var resolvedDxvk = dxvkToken
        if (hasSelectedDxvkWrapper(dxvkToken)) {
            val profile = resolveDxvkProfile(dxvkToken)
            if (profile == null) {
                Log.w(TAG, "DXVK profile missing for token=$dxvkToken")
                return null
            }
            val token = ContentPinResolver.wrapperToken("dxvk", profile)
            if (token != dxvkToken) {
                Log.w(TAG, "DXVK token '$dxvkToken' -> installed '$token'")
                resolvedDxvk = token
                changed = true
            }
        }

        var resolvedVkd3d = vkd3dToken
        if (hasSelectedDxvkWrapper(dxvkToken) &&
            vkd3dToken.isNotEmpty() &&
            !vkd3dToken.contains("None", ignoreCase = true)
        ) {
            val profile = resolveVkd3dProfile(vkd3dToken)
            if (profile == null) {
                Log.w(TAG, "VKD3D profile missing for token=$vkd3dToken")
                return null
            }
            val token = ContentPinResolver.wrapperToken("vkd3d", profile)
            if (token != vkd3dToken) {
                Log.w(TAG, "VKD3D token '$vkd3dToken' -> installed '$token'")
                resolvedVkd3d = token
                changed = true
            }
        }

        val next =
            buildList {
                add(resolvedDxvk)
                if (resolvedVkd3d.isNotEmpty()) add(resolvedVkd3d)
                if (ddraw.isNotEmpty()) add(ddraw)
            }.joinToString(";")
        if (changed) {
            c.setDXWrapper(next)
            this.dxwrapper = next
            c.saveData()
        }
        return next
    }

    private fun resolveVkd3dProfile(vkd3dWrapper: String): ContentProfile? = ContentPinResolver.resolveInstalledProfile(
        contentsManager,
        ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
        vkd3dWrapper,
    )

    // --- ensureWinePrefixEssentialFiles (XSDA L7164) -------------------------

    /**
     * Amphora no longer vendors Winlator's `container_pattern_common.tzst`
     * (fonts / icons / winhandler / wfm / cnc-ddraw tooling). The Wine prefix
     * comes from Proton `prefixPack.txz`; the only shared overlay we still
     * install is the CJK font pack ([SharedContainerFonts]: contents/ + symlink
     * + FontSubstitutes / Wine Replacements registry).
     */
    private fun ensureWinePrefixEssentialFilesCore(): Boolean {
        val c = wnContainer ?: return false
        File(c.getRootDir(), ".wine/drive_c/windows").mkdirs()
        val registryLocale = WineLocalePreferences.resolve(context)
        val fontState =
            "schema=${SharedContainerFonts.REGISTRY_SCHEMA_VERSION}|" +
                "assets=${runtimeFingerprint(listOf(SharedContainerFonts.ASSET_PATH))}|" +
                "locale=$registryLocale"
        val registryNeedsApply = AppliedMarks.needsFonts(c, fontState)
        val fontsOk =
            SharedContainerFonts.ensureInstalled(
                context,
                c.getRootDir(),
                applyRegistry = registryNeedsApply,
                registryLocale = registryLocale,
            )
        val localeOk =
            !registryNeedsApply ||
                WineUtils.applyLocaleToPrefix(c.getRootDir(), registryLocale)
        Log.d(
            TAG,
            "ensureWinePrefixEssentialFiles: sharedFonts=$fontsOk locale=$localeOk " +
                "registryApplied=$registryNeedsApply",
        )
        if (fontsOk && localeOk && registryNeedsApply) {
            AppliedMarks.markFonts(c, fontState)
            return true
        }
        return false
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
                    Log.d(
                        TAG,
                        "Applying DXVK content profile: $dxvkWrapper -> " +
                            ContentsManager.getEntryName(dxvkProfile),
                    )
                    check(contentsManager.applyContent(dxvkProfile)) {
                        "DXVK content apply failed: ${ContentsManager.getEntryName(dxvkProfile)}"
                    }
                    // DXVK ≥ 2.0 dropped its incomplete d3d10/d3d10_1 front-ends;
                    // Amphora's self-built 3.0.2 WCP matches upstream and only ships
                    // d3d10core. Wine's builtins forward CreateDevice/effects there.
                    // The pre-extract wipe clears stale fork links, so restore them.
                    WinComponentSetup.restoreWineBuiltinDllFiles(
                        imageFs,
                        wineInfo,
                        "d3d10.dll",
                        "d3d10_1.dll",
                    )
                    extractD8VKIfNeeded(dxvkWrapper, windowsDir)
                } else {
                    // Match WinNative XSDA: no fake ARCHIVE/Wine-builtin substitute.
                    // Real DXVK must be installed via ContentsManager (amphora bundles
                    // Dxvk-*.wcp through ContentSource.resolve(DXVK)).
                    Log.w(
                        TAG,
                        "DXVK content profile not installed; no bundled DXVK archive " +
                            "will be loaded: $dxvkWrapper",
                    )
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

            Log.d(TAG, "Linking nglide wrapper from shared cache")
            DirectDrawWrapperCache.linkDlls(context, "nglide", windowsDir)

            // DirectDraw wrappers are mutually exclusive. d7vk is a D3D proxy,
            // so it additionally keeps Wine's real DirectDraw as ddraw_.dll.
            val syswow64Dir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/syswow64")
            val system32Dir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/system32")
            File(syswow64Dir, "ddraw_.dll").delete()
            File(system32Dir, "ddraw_.dll").delete()
            File(syswow64Dir, "dxwrapper.dll").delete()
            File(syswow64Dir, "dxwrapper.ini").delete()
            File(syswow64Dir, "ddraw.ini").delete()

            // Both optional wrappers are PE32-only. The pre-extract wipe also
            // removes system32/ddraw.dll, but modern Wine resolves builtin PE
            // modules through the prefix. Restore both Wine architectures now;
            // the selected wrapper extraction below intentionally overwrites
            // only syswow64/ddraw.dll, leaving x86_64 system32/ddraw.dll builtin.
            WinComponentSetup.restoreWineBuiltinDllFiles(imageFs, wineInfo, "ddraw.dll")

            val selectedDdraw = DirectDrawWrapperIds.normalize(ddrawrapper)
            check(selectedDdraw == ddrawrapper) {
                "Unsupported DirectDraw wrapper '$ddrawrapper'; refusing WineD3D fallback"
            }
            if (selectedDdraw == DirectDrawWrapperIds.D7VK) {
                // Upstream's recommended system-path deployment: d7vk loads the
                // original Wine DirectDraw implementation through ddraw_.dll.
                val builtin = File(syswow64Dir, "ddraw.dll")
                val fallback = File(syswow64Dir, "ddraw_.dll")
                check(builtin.isFile && builtin.renameTo(fallback) && fallback.isFile) {
                    "Cannot preserve Wine DirectDraw as syswow64/ddraw_.dll for d7vk"
                }
            }
            Log.i(TAG, "Extracting DirectDraw wrapper '$selectedDdraw'")
            val ddrawCache = DirectDrawWrapperCache.linkDlls(context, selectedDdraw, windowsDir)
            check(File(syswow64Dir, "ddraw.dll").isFile) {
                "DirectDraw wrapper '$selectedDdraw' did not install syswow64/ddraw.dll"
            }
            when (selectedDdraw) {
                DirectDrawWrapperIds.CNC_DDRAW -> {
                    // Keep upstream's complete per-game preset database. Only its
                    // global auto renderer is changed to D3D9 in the vendored copy;
                    // auto would select OpenGL under Wine and re-enter Mesa Zink.
                    context.assets.open(CNC_DDRAW_CONFIG_ASSET).use { input ->
                        File(syswow64Dir, "ddraw.ini").outputStream().use(input::copyTo)
                    }
                    for (relativePath in CNC_DDRAW_SHADER_SIDECARS) {
                        val destination = File(syswow64Dir, relativePath)
                        val parent = requireNotNull(destination.parentFile)
                        check(parent.mkdirs() || parent.isDirectory) {
                            "Cannot create cnc-ddraw shader directory for $relativePath"
                        }
                        context.assets.open("cnc-ddraw/$relativePath").use { input ->
                            destination.outputStream().use(input::copyTo)
                        }
                    }
                    envState.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini")
                }

                DirectDrawWrapperIds.DXWRAPPER_DD7TO9 -> {
                    val cachedIni = File(ddrawCache, "syswow64/dxwrapper.ini")
                    check(cachedIni.isFile && FileUtils.copy(cachedIni, File(syswow64Dir, "dxwrapper.ini"))) {
                        "Cannot install container-private DxWrapper configuration"
                    }
                    check(
                        File(syswow64Dir, "dxwrapper.dll").isFile &&
                            File(syswow64Dir, "dxwrapper.ini").isFile,
                    ) {
                        "DxWrapper Dd7to9 install is incomplete"
                    }
                }

                // d7vk needs no sidecar configuration: its upstream release is
                // a single 32-bit ddraw.dll that translates D3D3–7 to Vulkan.
                DirectDrawWrapperIds.D7VK -> Unit
            }
            check(app.amphora.core.engine.directDrawInstallComplete(selectedDdraw, syswow64Dir)) {
                "DirectDraw wrapper '$selectedDdraw' install is incomplete"
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
        val vkd3dProfile = resolveVkd3dProfile(vkd3dWrapper)
        if (vkd3dProfile != null) {
            Log.i(TAG, "Loading VKD3D content profile: $vkd3dWrapper")
            check(contentsManager.applyContent(vkd3dProfile)) {
                "VKD3D content apply failed: ${ContentsManager.getEntryName(vkd3dProfile)}"
            }
        } else {
            Log.w(TAG, "VKD3D content profile not installed; no bundled VKD3D archive will be loaded: $vkd3dWrapper")
        }
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
     * (`dxvk-<verName>-<verCode>` or full `DXVK-...` entry name). Prefers the
     * named install, then newest installed of the type.
     */
    private fun resolveDxvkProfile(dxvkWrapper: String): ContentProfile? = ContentPinResolver.resolveInstalledProfile(
        contentsManager,
        ContentProfile.ContentType.CONTENT_TYPE_DXVK,
        dxvkWrapper,
    )

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
        val adrenotoolsDir = AdrenotoolsManager(context).getDriverDir(driverId)
        val resolvedLibraryName = if (libraryName.isNotEmpty()) libraryName else "libvulkan_wrapper.so"
        val metaFile = File(adrenotoolsDir, "meta.json")
        val dstDriver = File(adrenotoolsDir, resolvedLibraryName)
        val srcDriver = File(imageFs.getLibDir(), resolvedLibraryName)
        val wrapperSource = runtimeAsset(WRAPPER_ASSET)
        val pinMismatch = AppliedAssetPin.needsApply(adrenotoolsDir, wrapperSource, WRAPPER_ASSET)
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
                Log.w(
                    TAG,
                    "installAdrenotoolsDriverIfNeeded: driver .so not found at " +
                        "$srcDriver — re-extracting wrapper.tzst",
                )
                applyRuntimeArchive(WRAPPER_ASSET, imageFs.getRootDir())
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
            AppliedAssetPin.markApplied(adrenotoolsDir, wrapperSource, WRAPPER_ASSET)
        } else {
            Log.d(TAG, "Adrenotools driver already installed: $driverId ($resolvedLibraryName)")
        }

        // Always (re)seed runtime deps — Turnip zip is so+meta only; without these
        // copies adrenotools' driver namespace cannot dlopen NEEDED libs from
        // imagefs and silently falls back to proprietary Adreno.
        seedAdrenotoolsRuntimeDeps(adrenotoolsDir)
    }

    private fun wrapperImagefsNeedsRefresh(rootDir: File): Boolean {
        val so = File(rootDir, "usr/lib/libvulkan_wrapper.so")
        return !so.isFile || AppliedAssetPin.needsApply(rootDir, runtimeAsset(WRAPPER_ASSET), WRAPPER_ASSET)
    }

    private fun runtimeAsset(relativePath: String): File =
        File(RuntimeAssetProvisioner.runtimeAssetsDir(context), relativePath)

    private fun runtimeFingerprint(relativePaths: Iterable<String>): String =
        AppliedAssetPin.fingerprint(RuntimeAssetProvisioner.runtimeAssetsDir(context), relativePaths)

    private fun dxwrapperGateKey(selection: DxWrapperSelection, arch: String): String =
        "${selection.gateKey(arch)}|assets=${runtimeFingerprint(DDRAW_RUNTIME_ASSETS)}"

    private fun applyRuntimeArchive(relativePath: String, targetRoot: File) {
        check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, relativePath, targetRoot)) {
            "Cannot apply runtime asset: $relativePath"
        }
        AppliedAssetPin.markApplied(targetRoot, runtimeAsset(relativePath), relativePath)
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
            val copySource =
                runCatching {
                    if (Files.isSymbolicLink(src.toPath())) src.canonicalFile else src
                }.getOrDefault(src)
            if (!copySource.isFile) {
                Log.w(TAG, "seedAdrenotoolsRuntimeDeps: dep $dep not found at $src (skipping)")
                continue
            }
            val dst = File(adrenotoolsDir, dep)
            // Refresh when imagefs was hot-fixed (e.g. libandroid_shm* aliases).
            if (dst.exists() &&
                dst.length() == copySource.length() &&
                dst.lastModified() >= copySource.lastModified()
            ) {
                continue
            }
            if (FileUtils.copy(copySource, dst)) {
                // FileUtils.copy does not preserve timestamps. Without carrying
                // the source mtime forward, archive timestamps newer than the
                // device clock make this branch rewrite every dependency on
                // every session even when size and contents are unchanged.
                copySource.lastModified().takeIf { it > 0L }?.let(dst::setLastModified)
                copiedDeps++
            } else {
                Log.w(TAG, "seedAdrenotoolsRuntimeDeps: copy failed for dep $dep")
            }
        }
        if (copiedDeps > 0) {
            Log.i(TAG, "Seeded $copiedDeps adrenotools runtime dep(s) into $adrenotoolsDir")
        }
    }

    /**
     * Single on-disk hook set for **guest and host** adrenotools.
     *
     * `wrapper.tzst` extracts `libadrenotools.so` + 4 hooks into
     * `imageFs.getLibDir()` (CI pins adrenotools @ 8483dfd, same as the static
     * copy linked into `libwinlator.so`). Guest ICD loads them via this env;
     * host [VulkanRenderer] passes the same path as `hookLibDir` (see
     * `vulkan.c`). APK no longer packages a second hook set — pointing guest at
     * `nativeLibraryDir` used to fail with `Couldn't preload the hook
     * implementation` / `vkCreateInstance -9`.
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
        // 图形驱动：容器是唯一真相（getOrCreate 已从设置写入）。
        val configuredDriverId = GraphicsDriverIds.normalize(graphicsDriverConfig["version"])
        val isAdreno = GPUInformation.isAdrenoGPU(context)
        val effectiveDriverId =
            GraphicsDriverIds.resolveEffectiveDriver(configuredDriverId, isAdreno)
        Log.i(
            TAG,
            "Launch graphics driver: configured='$configuredDriverId' effective='$effectiveDriverId' " +
                "graphicsDriver='$graphicsDriver' adreno=$isAdreno",
        )

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
        // DXVK replaces D3D8-11, but Wine's x86_64 builtin DirectDraw still
        // falls back to WineD3D because the optional DirectDraw wrappers only
        // ship 32-bit DLLs. Keep that path explicitly on OpenGL → EGL/Zink.
        WineD3DConfigUtils.setEnvVars(context, dxwrapperConfig, envState)
        if (dxwrapper.split(";").lastOrNull() == DirectDrawWrapperIds.CNC_DDRAW) {
            envState.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini")
        }
        // Both DirectDraw wrapper assets are PE32 only. native,builtin therefore
        // selects syswow64/ddraw.dll for 32-bit games, while a 64-bit process
        // (with no system32 native ddraw.dll) falls through to Proton's builtin
        // x86_64 ddraw/WineD3D. D3D9 stays native-only so wrapper output always
        // enters DXVK.
        envState.put("WINEDLLOVERRIDES", "ddraw=n,b;d3d9=n")

        applyGalliumDriver(rootDir)
        applyWineEglBackend(rootDir)
        // LIBGL_KOPPER_DISABLE is deliberately not set. Winlator ships it, but it only
        // exists in Mesa's DRI GLX/EGL paths (src/glx, src/egl) — the xlib GLX frontend
        // never reads it, which is why it looked harmless. Now that imagefs builds the
        // DRI frontend it would really turn kopper off, and kopper is exactly how zink
        // presents through DRI3 + Present.

        if (firstTimeBoot || wrapperImagefsNeedsRefresh(rootDir)) {
            Log.i(TAG, "Applying $WRAPPER_ASSET to imagefs")
            applyRuntimeArchive(WRAPPER_ASSET, rootDir)
        }
        // layers.tzst (Khronos validation) is debug-only and is not extracted by default.
        // Host VulkanRenderer enables validation only when explicitly requested.
        // extra_libs.tzst is gone: its only live payload was Mesa libGL, which the
        // self-built imagefs now ships (imagefs packages/graphics/mesa-gl.sh). The
        // rest (Turnip, vkBasalt, bcn_layer) has no consumer — the default Vulkan
        // path is the wrapper ICD and full Turnip is the optional WN-Turnip zip.
        // D5: arm64ec zink_dlls branch stripped (wineInfo.isArm64EC() always false for x86_64).

        val wantLeegao =
            effectiveDriverId != GraphicsDriverIds.SYSTEM && "wrapper-leegao" == graphicsDriver
        val leegaoMarker = File(rootDir, "usr/lib/.wrapper_leegao")
        if (wantLeegao) {
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                "graphics_driver/wrapper-leegao.tzst",
                rootDir,
            )
            try {
                leegaoMarker.createNewFile()
            } catch (e: IOException) { /* ignored */ }
        } else if (leegaoMarker.exists()) {
            applyRuntimeArchive(WRAPPER_ASSET, rootDir)
            leegaoMarker.delete()
        }

        if (effectiveDriverId != GraphicsDriverIds.SYSTEM) {
            val adrenotoolsManager = AdrenotoolsManager(context)
            val driverLibrary = adrenotoolsManager.getLibraryName(effectiveDriverId)
            Log.i(TAG, "Loading graphics/Turnip driver: id='$effectiveDriverId' library='$driverLibrary'")
            // Only seed wrapper.so from imagefs for the bundled "wrapper" id.
            // Optional WN-Turnip packages are installed by TurnipDriverProvisioner
            // and must not be overwritten with libvulkan_wrapper.so.
            if (effectiveDriverId == GraphicsDriverIds.WRAPPER ||
                driverLibrary.isEmpty() ||
                driverLibrary == "libvulkan_wrapper.so"
            ) {
                installAdrenotoolsDriverIfNeeded(
                    effectiveDriverId,
                    if (driverLibrary.isNotEmpty()) driverLibrary else "libvulkan_wrapper.so",
                )
            }
            val libraryName = adrenotoolsManager.getLibraryName(effectiveDriverId)
                .ifEmpty { driverLibrary }
            // Guest: wrapper ICD + adrenotools backend. For the default wrapper
            // id leave PATH/NAME unset so wrapper uses system Adreno. For an
            // optional Turnip package (libraryName=libvulkan_freedreno.so), set
            // PATH/NAME/HOOKS like WinNative setDriverById.
            if (libraryName == "libvulkan_freedreno.so" &&
                adrenotoolsManager.isInstalled(effectiveDriverId)
            ) {
                val driverDir = adrenotoolsManager.getDriverDir(effectiveDriverId)
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
                "No Adrenotools driver applied (id='$effectiveDriverId' graphicsDriver='$graphicsDriver')" +
                    " - system Vulkan driver will be used",
            )
        }

        if (effectiveDriverId == GraphicsDriverIds.SYSTEM) {
            // Let Android's Vulkan loader select the platform ICD (Mali,
            // SwiftShader, virtual GPU, ...). Pointing at wrapper_icd here would
            // re-enter the Adreno-only wrapper despite the explicit selection.
            Log.i(TAG, "Guest Vulkan backend: Android system loader")
        } else {
            envState.put(
                "VK_ICD_FILENAMES",
                imageFs.getShareDir().path + "/vulkan/icd.d/wrapper_icd.aarch64.json",
            )
        }

        var vulkanVersion = graphicsDriverConfig["vulkanVersion"] ?: "1.4"
        try {
            // The wrapper is an ICD around the platform driver, not an HMI-exporting
            // Android HAL. Probe its underlying system driver just like the host
            // compositor; only a real Turnip package goes through adrenotools here.
            val probeDriver =
                GraphicsDriverIds.resolveHostDriver(
                    effectiveDriverId,
                    isAdreno,
                )
            val fullVkVersion = GPUInformation.getVulkanVersion(probeDriver, context)
            if (fullVkVersion != null && fullVkVersion.contains(".")) {
                val parts = fullVkVersion.split("\\.".toRegex())
                if (parts.size >= 3) {
                    val driverMinor = parts[1].toIntOrNull()
                    val requestedMinor = vulkanVersion.substringAfter('.', "").toIntOrNull()
                    if (driverMinor != null && requestedMinor != null && driverMinor < requestedMinor) {
                        Log.i(
                            TAG,
                            "Clamping Vulkan $vulkanVersion to driver-supported ${parts[0]}.${parts[1]}",
                        )
                        vulkanVersion = "${parts[0]}.${parts[1]}"
                    }
                    vulkanVersion = "$vulkanVersion.${parts[2]}"
                }
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
        // No container_pattern overlay — prefix is Proton prefixPack. Shared CJK
        // fonts are reconciled once below by ensureWinePrefixEssentialFilesCore.
        // PulseAudio runtime assets are installed on demand by PulseAudioRuntimeSupport.
        WineUtils.applySystemTweaks(context, wineInfo)
    }

    /**
     * Select zink through the *loader*, and let kopper present, when the imagefs
     * Mesa build actually carries zink (the [LIBGL_ZINK_MARKER] sidecar).
     *
     * `GALLIUM_DRIVER=zink` is the wrong knob here even though it does produce a
     * zink context. It only overrides the gallium screen; EGL still believes it
     * is on swrast, so it presents with `drisw` put-image — and zink's
     * `flush_frontbuffer` only knows how to present kopper displaytargets. The
     * result renders on the GPU but shows a blank window. Worse, Mesa skips its
     * own zink selection entirely whenever `GALLIUM_DRIVER` is set
     * (`eglapi.c: !getenv("GALLIUM_DRIVER")`), so setting it is what kept kopper
     * off in the first place. `MESA_LOADER_DRIVER_OVERRIDE` is the loader-level
     * knob that sets `Options.Zink`, which is what selects the kopper path.
     *
     * [KOPPER_DRI2] then makes that path survive our X server. Kopper normally
     * qualifies the server first — a DRI3 render-device FD plus DRI3 multibuffer
     * support — and Amphora's X server answers `DRI3Open` with zero FDs on
     * purpose, because an unprivileged Android app cannot open `/dev/dri/render*`.
     * That check is upstream's "force zink even if the X server is missing a
     * bunch of features" escape hatch. Presentation does not need the DRM device:
     * kopper creates a `VkSurfaceKHR` through the wrapper ICD's
     * `VK_KHR_xcb_surface`, which is the same AHardwareBuffer + DRI3
     * `PixmapFromBuffers` + Present route DXVK already presents through.
     *
     * Without zink there is no accelerated path at all, so leave every knob unset
     * and let Mesa pick its software rasterizer — slow, but it draws.
     */
    private fun applyGalliumDriver(rootDir: File) {
        if (File(rootDir, LIBGL_ZINK_MARKER).isFile) {
            envState.put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
            envState.put(KOPPER_DRI2, "1")
            return
        }
        Log.w(
            TAG,
            "imagefs Mesa has no zink ($LIBGL_ZINK_MARKER missing); leaving the zink/kopper " +
                "knobs unset so Mesa falls back to software rasterization for OpenGL/DirectDraw",
        )
    }

    /**
     * Opt into Wine's EGL OpenGL backend when the imagefs Mesa build ships
     * [LIBEGL_SONAME].
     *
     * The GLX backend cannot work here: Amphora's X server is a Java
     * implementation with no GLX extension, and Mesa's xlib GLX frontend has no
     * way to present a zink surface. Mesa's EGL X11 platform never queries GLX —
     * it goes straight to DRI3/Present — so EGL is the only path that reaches
     * the GPU. Proton keeps that backend behind `WINE_USE_EGL` (upstream Wine 11
     * enables it by default on X11; this build does not), and without the
     * variable `egl_init` bails with "EGL support is disabled" and falls back to
     * `dlopen`ing libGL for GLX.
     *
     * Gated on the library actually being present so an older imagefs, whose
     * Mesa was built with the xlib GLX frontend and ships no libEGL, keeps its
     * existing behaviour instead of losing OpenGL entirely.
     */
    private fun applyWineEglBackend(rootDir: File) {
        if (File(rootDir, LIBEGL_SONAME).exists()) {
            envState.put("WINE_USE_EGL", "1")
            return
        }
        Log.w(
            TAG,
            "imagefs has no $LIBEGL_SONAME; leaving WINE_USE_EGL unset so Wine keeps the GLX backend",
        )
    }

    /** wipeDxwrapperDllsForReextract (XSDA L7950). */
    private fun wipeDxwrapperDllsForReextract() {
        val rootDir = imageFs.getRootDir()
        val system32 = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/system32")
        val syswow64 = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/syswow64")
        var deleted = 0
        for (name in DXWRAPPER_DLLS) {
            // Keep d3dimm.dll — owned by ddraw wrappers, not DXVK applyContent.
            // Wipe d3d8 (DXVK ships it) and d3d10/d3d10_1 (Wine front-ends after
            // DXVK ≥ 2.0) so stale fork links to missing WCPs are cleared; extract
            // re-links DXVK DLLs and restores Wine d3d10/d3d10_1 afterward.
            if (name == "d3dimm.dll") continue
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
     * preparation. The shared applied-asset marker prevents rewriting ~4.7 MB
     * on every launch while still replacing binaries when the source SHA moves.
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
            if (!source.isFile || AppliedAssetPin.sourceSha(source) == null) {
                Log.e(TAG, "Verified graphics test asset is missing: $assetPath")
                continue
            }

            val destination = File(destinationDir, FileUtils.getName(assetPath))
            if (destination.isFile &&
                destination.length() == source.length() &&
                !AppliedAssetPin.needsApply(container.getRootDir(), source, assetPath)
            ) {
                continue
            }

            if (FileUtils.copy(source, destination)) {
                AppliedAssetPin.markApplied(container.getRootDir(), source, assetPath)
                // Remove the pre-unification destination-side marker, if present.
                File(destination.absolutePath + ".sha256").delete()
                Log.i(TAG, "Staged ${destination.name} (${destination.length()} bytes)")
            } else {
                Log.e(TAG, "Failed to stage graphics test asset: $assetPath")
            }
        }
    }

    /** Amphora has no per-shortcut override; the global session limit is authoritative. */
    private fun getDxvkFrameRateOverride(): Int = AdvancedRuntimePreferences.frameRateLimit(context)

    /**
     * Repair deleted or broken component links. Normal launches skip a full
     * graphics reinstall while the expected shared links are healthy.
     */
    private fun sharedGraphicsLinksNeedRefresh(dxwrapper: String): Boolean {
        val windowsDir =
            File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows")
        val system32 = File(windowsDir, "system32")
        val syswow64 = File(windowsDir, "syswow64")
        fun validLink(file: File): Boolean = Files.isSymbolicLink(file.toPath()) && file.isFile
        fun bothLinked(name: String): Boolean = validLink(File(system32, name)) && validLink(File(syswow64, name))

        val parts = dxwrapper.split(";")
        if (parts.firstOrNull()?.startsWith("dxvk-") == true) {
            // DLLs owned by the DXVK WCP (Amphora 3.0.2 / upstream ≥ 2.0).
            val dxvkDlls =
                arrayOf(
                    "d3d8.dll",
                    "d3d9.dll",
                    "d3d10core.dll",
                    "d3d11.dll",
                    "dxgi.dll",
                )
            if (dxvkDlls.any { !bothLinked(it) }) return true
            // Wine front-ends (not in the DXVK WCP); still must be present links.
            if (arrayOf("d3d10.dll", "d3d10_1.dll").any { !bothLinked(it) }) return true
        }
        if (parts.getOrNull(1)?.startsWith("vkd3d-") == true &&
            arrayOf("d3d12.dll", "d3d12core.dll").any { !bothLinked(it) }
        ) {
            return true
        }

        val selectedDdraw = DirectDrawWrapperIds.normalize(parts.getOrNull(2))
        if (!validLink(File(system32, "ddraw.dll")) ||
            !validLink(File(syswow64, "ddraw.dll"))
        ) {
            return true
        }
        if (selectedDdraw == DirectDrawWrapperIds.D7VK &&
            !validLink(File(syswow64, "ddraw_.dll"))
        ) {
            return true
        }
        if (selectedDdraw == DirectDrawWrapperIds.DXWRAPPER_DD7TO9 &&
            (
                !validLink(File(syswow64, "dxwrapper.dll")) ||
                    !File(syswow64, "dxwrapper.ini").isFile
                )
        ) {
            return true
        }
        if (selectedDdraw == DirectDrawWrapperIds.CNC_DDRAW &&
            (
                !File(syswow64, "ddraw.ini").isFile ||
                    CNC_DDRAW_SHADER_SIDECARS.any { !File(syswow64, it).isFile }
                )
        ) {
            return true
        }
        return arrayOf(
            "3DfxSpl.dll",
            "3DfxSpl2.dll",
            "3DfxSpl3.dll",
            "glide.dll",
            "glide2x.dll",
            "glide3x.dll",
        ).any { !validLink(File(syswow64, it)) }
    }

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
                (
                    profile.verCode == best.verCode && profile.verName != null && best.verName != null &&
                        profile.verName.compareTo(best.verName, ignoreCase = true) > 0
                    )
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
        private const val START_MENU_ASSET = "metadata/startmenu.json"
        private const val START_MENU_SCHEMA_VERSION = 1
        private val WINCOMPONENT_RUNTIME_ASSETS =
            listOf(
                "wincomponents/wincomponents.json",
                "wincomponents/direct3d.tzst",
                "wincomponents/directsound.tzst",
                "wincomponents/directmusic.tzst",
                "wincomponents/directshow.tzst",
                "wincomponents/directplay.tzst",
                "wincomponents/xaudio.tzst",
                "wincomponents/vcrun2010.tzst",
            )
        private val DDRAW_RUNTIME_ASSETS =
            listOf(
                "ddrawrapper/nglide.tzst",
                "ddrawrapper/dd7to9.tzst",
                "ddrawrapper/cnc-ddraw.tzst",
                "ddrawrapper/d7vk.zip",
            )

        /** Written by the imagefs mesa-gl package when the megadriver links zink. */
        private const val LIBGL_ZINK_MARKER = "usr/lib/.libgl-zink"

        /** Wine dlopens this SONAME; present only once mesa-gl builds the EGL frontend. */
        private const val LIBEGL_SONAME = "usr/lib/libEGL.so.1"
        private const val CNC_DDRAW_CONFIG_ASSET = "cnc-ddraw/ddraw.ini"

        /** Mesa's opt-in to kopper on an X server without a DRI3 render device. */
        private const val KOPPER_DRI2 = "LIBGL_KOPPER_DRI2"
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

        private fun appVersionCode(context: Context): String = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            "0"
        }
    }
}
