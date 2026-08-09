package app.amphora.core.engine

import android.content.Context
import android.util.Log
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.ContainerManager
import app.amphora.core.container.model.Container as AmphoraContainer
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.content.ProvisionProgressBus
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.engine.model.AudioSink
import app.amphora.core.engine.model.InputSink
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.core.engine.model.SessionHandle
import app.amphora.core.rootfs.RootfsInstaller
import app.amphora.core.rootfs.model.RootfsSpec
import com.winlator.cmod.runtime.audio.alsaserver.ALSAClient
import com.winlator.cmod.runtime.container.Container as WinNativeContainer
import com.winlator.cmod.runtime.container.ContainerManager as WinNativeContainerManager
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.connector.UnixSocketConfig
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.environment.ImageFsInstaller
import com.winlator.cmod.runtime.display.environment.XEnvironment
import com.winlator.cmod.runtime.display.environment.components.ALSAServerComponent
import com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent
import com.winlator.cmod.runtime.display.environment.components.NetworkInfoUpdateComponent
import com.winlator.cmod.runtime.display.environment.components.SysVSharedMemoryComponent
import com.winlator.cmod.runtime.display.environment.components.XServerComponent
import com.winlator.cmod.runtime.display.xserver.ScreenInfo
import com.winlator.cmod.runtime.display.xserver.XServer
import com.winlator.cmod.runtime.system.GPUInformation
import com.winlator.cmod.runtime.system.ProcessHelper
import com.winlator.cmod.runtime.wine.EnvVars
import com.winlator.cmod.runtime.wine.GraphicsDriverConfigUtils
import com.winlator.cmod.runtime.wine.WineInfo
import com.winlator.cmod.shared.io.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Real [WineEngine] facade (RFC §6 / §7 / D9). Delegates to the ported
 * `com.winlator.cmod` runtime: [XEnvironment] (ALSAServer + XServer + GuestProgramLauncher
 * components) + [GuestProgramLauncherComponent] (the `box64 wine explorer /desktop=WxH exe`
 * launch) + [XServer] (render target + input). Sibling interfaces: [ContainerManager] (P4 ✅),
 * [RootfsInstaller] (P2), [WineSessionPreparer] (P2). Bound as the [WineEngine] impl in
 * [app.amphora.core.engine.di.EngineModule].
 *
 * Also implements [GameSessionSurfaceProvider]: the GameSession UI (the D9 rewrite of
 * `XServerDisplayActivity`) needs the [XServer] to construct `XServerSurfaceView` + the touch
 * overlay, so [surface] exposes it once [launch] has built it. The [WineEngine] interface
 * itself stays kernel-free.
 *
 * MVP launch chain (XSDA `setupXEnvironment`, L6439, stripped of Steam / shortcut / recording /
 * arm64ec / WinHandler per RFC §7 / D5 / D9):
 * ```
 * RootfsInstaller.ensureInstalled                 // P2
 *   -> ContainerManager.getOrCreate               // P4 ✅ (WinlatorContainerManager)
 *   -> WineSessionPreparer.setupWineSystemFiles + extractGraphicsDriverFiles  // P2
 *   -> resolve WinNative Container + WineInfo      // bridge (mirror preparer.resolveState)
 *   -> XServer(ScreenInfo(spec.displaySize))       // render target + input sink
 *   -> EnvVars (LC_ALL/WINEPREFIX/WINEDEBUG + preparer.envVars + spec.env + ALSA)
 *   -> XEnvironment + components (SysVShm / XServer / ALSA / NetworkInfo / GuestProgramLauncher)
 *   -> startEnvironmentComponents()                // GPLC runs last, execs box64 wine
 * ```
 *
 * **P4:** the chain is wired end-to-end with a real [ContainerManager]
 * ([WinlatorContainerManager]). `getOrCreate` installs the bundled Wine/Box64
 * content + creates the Wine prefix; this facade then resolves the WinNative
 * [WineInfo] + builds the [XEnvironment] + launches `box64 wine`. End-to-end
 * verification is the RFC §8 acceptance test (launch a .exe -> Vulkan frame +
 * touch + audio). All kernel orchestration is real and faithful to XSDA.
 */
@Singleton
class WineEngineImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val containerManager: ContainerManager,
    private val rootfsInstaller: RootfsInstaller,
    private val preparer: WineSessionPreparer,
    private val runtimeAssets: RuntimeAssetProvisioner,
    private val catalog: ContentCatalog,
    private val progressBus: ProvisionProgressBus,
    private val dispatchers: DispatcherProvider,
) : WineEngine,
    GameSessionSurfaceProvider {
    // --- kernel singletons (constructed like XServerWineSessionPreparer / XSDA L1041+) -----
    private val imageFs: ImageFs = ImageFs.find(context)
    private val contentsManager: ContentsManager = ContentsManager(context)
    private val wnContainerManager: WinNativeContainerManager = WinNativeContainerManager(context)

    // --- session surface (GameSessionSurfaceProvider) ---------------------------------------
    private val _surface = MutableStateFlow<GameSessionSurface?>(null)
    override val surface: StateFlow<GameSessionSurface?> = _surface.asStateFlow()
    override val provisionProgress: StateFlow<ProvisionProgress?> = progressBus.progress

    private var currentXServer: XServer? = null
    private var currentHandle: XServerSessionHandle? = null
    private val sessionAudioSink = XServerAudioSink()

    override suspend fun launch(spec: LaunchSpec): SessionHandle = withContext(dispatchers.default) {
        // A new launch is also the recovery boundary after process death or an
        // interrupted teardown. Stop the previous handle first, then sweep any
        // own-UID Wine/Box64 processes that survived before creating new ones.
        currentHandle?.stop()
        DefaultSessionProcessCleaner.terminateAndWait(PRELAUNCH_CLEANUP_TIMEOUT_MS)

        // Clear any prior session state before starting a new one.
        _surface.value = null
        currentXServer = null
        currentHandle = null
        // Ensure Wine stderr can land under filesDir (ProcessHelper has no PluviaApp).
        ProcessHelper.init(context)

        try {
            progressBus.update(ProvisionProgress(stage = "manifest", detail = "Fetching content manifest…"))
            catalog.require()

            // 1. Kernel-direct archives/metadata are downloaded once and then served
            //    transparently through the legacy asset-path IO bridge.
            progressBus.update(ProvisionProgress(stage = "runtime", detail = "Preparing runtime assets…"))
            runtimeAssets.ensureAvailable()
            // 2. imagefs rootfs.
            progressBus.update(ProvisionProgress(stage = "rootfs", detail = "Checking imagefs…"))
            ensureRootfs()
            // 3. Wine container / WINEPREFIX (P4 WinlatorContainerManager: installs
            //    Wine/Box64/DXVK content + creates the prefix from the Proton prefixPack).
            progressBus.update(ProvisionProgress(stage = "container", detail = "Preparing Wine container…"))
            val container = containerManager.getOrCreate(spec.containerId)
            // This engine instance's ContentsManager needs the installed profiles loaded for
            // WineInfo.fromIdentifier (step 4) + buildGuestLauncher (getProfileByEntryName).
            // The ContainerManager + preparer each sync their own ContentsManager instance
            // (per-instance state -- see docs/03-TRACKING.md §P2 #7c).
            contentsManager.syncContents()
            // 3. Prefix + runtime files + DX wrapper + graphics driver (P2 WineSessionPreparer).
            progressBus.update(ProvisionProgress(stage = "prefix", detail = "Setting up Wine prefix…"))
            preparer.setupWineSystemFiles(spec, container)
            preparer.extractGraphicsDriverFiles(container)
            // 4. Resolve the WinNative Container + WineInfo the launcher needs (bridge, mirror
            //    preparer.resolveState: amphora Container.rootPath -> WinNative Container by rootDir).
            val wnContainer = resolveWinNativeContainer(container)
            val wineVersion = wnContainer.getWineVersion()
            val wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion)
            imageFs.setWinePath(wineInfo.path)
            // 5. XServer: the X render target + the input injection surface for the touch overlay.
            progressBus.update(ProvisionProgress(stage = "session", detail = "Starting X session…"))
            val xServer = XServer(ScreenInfo(spec.displaySize.width, spec.displaySize.height))
            currentXServer = xServer
            val driverConfig =
                GraphicsDriverConfigUtils.parseGraphicsDriverConfig(
                    wnContainer.getGraphicsDriverConfig(),
                )
            val configuredHostDriver =
                GraphicsDriverIds.normalize(driverConfig["version"])
            // WinNative's default wrapper is a Vulkan ICD, not an Android HAL:
            // guest wrapper -> system Adreno, host compositor -> system Vulkan.
            // Only downloaded Turnip packages have an HMI entry point suitable
            // for host adrenotools loading, and those remain Adreno-only.
            val hostDriver =
                GraphicsDriverIds.resolveHostDriver(
                    configuredHostDriver,
                    GPUInformation.isAdrenoGPU(context),
                )
            if (hostDriver != configuredHostDriver) {
                Log.i(
                    "WineEngineImpl",
                    "Host Vulkan renderer resolved '$configuredHostDriver' -> '$hostDriver'",
                )
            }
            _surface.value =
                GameSessionSurface(
                    xServer = xServer,
                    graphicsDriver = hostDriver,
                    presentMode = driverConfig["presentMode"],
                )
            // 6. Launch env: container Zink/Turnip defaults + preparer + caller + ALSA.
            val envVars = buildLaunchEnvVars(spec, wnContainer)
            // 7. XEnvironment + service components (GPLC added separately so the handle can wire its
            //    termination callback first).
            val environment = buildEnvironment(xServer, envVars)
            lateinit var handle: XServerSessionHandle
            handle =
                XServerSessionHandle(
                    environment,
                    xServer,
                    dispatchers,
                    onStopped = {
                        if (currentHandle === handle) {
                            _surface.value = null
                            currentXServer = null
                            currentHandle = null
                        }
                    },
                )
            currentHandle = handle
            // 8. Guest launcher: `box64 wine explorer /desktop=WxH "<exe>"` (D9: Amphora passes
            //    exe + env only; it never rewrites getWineStartCommand).
            val launcher = buildGuestLauncher(wnContainer, wineInfo, spec, envVars)
            launcher.setTerminationCallback { handle.requestStop() }
            environment.addComponent(launcher)
            // 9. Start (GPLC starts last and execs the guest process).
            handle.markStarting()
            try {
                environment.startEnvironmentComponents()
                handle.markRunning()
            } catch (e: Exception) {
                handle.markFailed(e)
                // launch() throws, so the ViewModel never receives this handle.
                // Teardown must happen here or partially-started components and
                // Wine children become unreachable.
                withContext(NonCancellable) { handle.stop() }
                throw e
            }
            handle
        } finally {
            progressBus.clear()
        }
    }

    override fun inputFeed(): InputSink = currentXServer?.let { XServerInputSink(it) } ?: StubInputSink

    override fun audioSink(): AudioSink = sessionAudioSink

    // --- launch steps ------------------------------------------------------------------------

    private suspend fun ensureRootfs() {
        val installed =
            rootfsInstaller.ensureInstalled(
                RootfsSpec(
                    targetRoot = imageFs.getRootDir().absolutePath,
                    imagefsVersion = IMAGEFS_VERSION,
                    // termuxfs has no separate archive (D7: rpath baked in Wine ELF, resolved at
                    // launch via LD_LIBRARY_PATH); the field is reserved for future pinning.
                    termuxfsSha256 = "",
                ),
            )
        check(installed) {
            "Rootfs installation failed: imagefs extraction returned false " +
                "(verified download missing or native extract error; see provisioning logs)."
        }
    }

    /**
     * Bridge amphora [AmphoraContainer] -> WinNative [WinNativeContainer] by matching
     * `rootPath` (mirrors `XServerWineSessionPreparer.resolveContainer`). The WinNative
     * Container carries the emulator / box64 / graphics-driver config the launcher reads;
     * amphora's model is intentionally lean. [WinlatorContainerManager] creates the
     * container; this facade + the preparer both resolve the WinNative view by rootPath.
     */
    private fun resolveWinNativeContainer(amphora: AmphoraContainer): WinNativeContainer {
        val target = File(amphora.rootPath).absoluteFile
        wnContainerManager.loadContainers()
        return wnContainerManager.getContainers().firstOrNull { it.getRootDir().absoluteFile == target }
            ?: throw IllegalStateException(
                "WinNative container not found at ${amphora.rootPath} " +
                    "(loaded ${wnContainerManager.getContainers().size} container(s))",
            )
    }

    /**
     * Build guest launch env (XSDA `setupXEnvironment` merge order):
     * locale / prefix → container defaults → preparer (driver/DXVK/wrapper) →
     * [LaunchSpec.env] → ALSA.
     *
     * Container [WinNativeContainer.getEnvVars] carries `ZINK_*` / `TU_DEBUG` /
     * `mesa_glthread` from [WinNativeContainer.DEFAULT_ENV_VARS] for native
     * OpenGL through EGL/Zink. DirectDraw is handled separately by a native
     * wrapper whose D3D9 output goes to DXVK.
     */
    private fun buildLaunchEnvVars(spec: LaunchSpec, container: WinNativeContainer): EnvVars {
        val envVars = EnvVars()
        envVars.put("LC_ALL", WineLocalePreferences.resolve(context))
        envVars.put("WINEPREFIX", imageFs.wineprefix)
        envVars.put("WINEDEBUG", "-all")
        // Container Zink/Turnip defaults (before preparer so WRAPPER_*/DXVK_* win).
        val containerEnv = container.getEnvVars()
        if (!containerEnv.isNullOrBlank()) {
            envVars.putAll(containerEnv)
        } else {
            envVars.putAll(WinNativeContainer.DEFAULT_ENV_VARS)
        }
        // A container that names its own WINEDEBUG channels is asking for those
        // traces specifically. GraphicsDiag also sets WINEDEBUG, but only to lift
        // the `-all` default so wine_stderr.log gets written at all — letting it
        // land last means turning diagnostics on silently discards the channels
        // someone selected, which is the opposite of what either knob is for.
        val requestedWineDebug = envVars.get("WINEDEBUG")?.takeIf { it.isNotBlank() && it != "-all" }
        // Preparer-computed DirectDraw / GPU / DXVK / OpenGL env.
        for ((key, value) in preparer.envVars()) envVars.put(key, value)
        if (requestedWineDebug != null) envVars.put("WINEDEBUG", requestedWineDebug)
        // Persisted advanced settings override automatic/container defaults, but
        // LaunchSpec remains the final caller-controlled layer.
        AdvancedRuntimePreferences.applyEnvOverrides(context, envVars)
        // Caller-supplied env (LaunchSpec.env).
        for ((key, value) in spec.env) envVars.put(key, value)
        if (envVars.get("DXVK_HUD") != null) {
            Log.i(
                "WineEngineImpl",
                "Graphics diag env active: DXVK_HUD=${envVars.get("DXVK_HUD")} " +
                    "DXVK_LOG_PATH=${envVars.get("DXVK_LOG_PATH")} " +
                    "WINEDEBUG=${envVars.get("WINEDEBUG")}",
            )
        }
        // ALSA socket when using alsa backend (WinNative alsa branch).
        // Registry Audio 由 AppliedMarks 门控写入；可配置 alsa / pulseaudio。
        // 当前运行时只接了 ALSA aserver；选 pulse 需另接 Pulse 组件。
        val rootPath = imageFs.getRootDir().path
        envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH)
        envVars.put("ANDROID_ASERVER_USE_SHM", "true")
        return envVars
    }

    private fun buildEnvironment(xServer: XServer, envVars: EnvVars): XEnvironment {
        val rootPath = imageFs.getRootDir().path
        val environment = XEnvironment(context, imageFs)
        environment.addComponent(
            SysVSharedMemoryComponent(
                xServer,
                UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH),
            ),
        )
        environment.addComponent(
            XServerComponent(
                xServer,
                UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH),
            ),
        )
        environment.addComponent(
            ALSAServerComponent(
                UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH),
                ALSAClient.Options.fromEnvVars(envVars),
            ),
        )
        environment.addComponent(NetworkInfoUpdateComponent())
        return environment
    }

    private fun buildGuestLauncher(
        container: WinNativeContainer,
        wineInfo: WineInfo,
        spec: LaunchSpec,
        envVars: EnvVars,
    ): GuestProgramLauncherComponent {
        // wineProfile may be null (falls back to imageFs.getWinePath() inside the launcher).
        val wineProfile = contentsManager.getProfileByEntryName(container.getWineVersion())
        val launcher = GuestProgramLauncherComponent(contentsManager, wineProfile)
        launcher.setContainer(container)
        launcher.setWineInfo(wineInfo)
        val screenInfo = "${spec.displaySize.width}x${spec.displaySize.height}"
        val guestExecutable =
            when (spec.target) {
                LaunchTarget.EXPLORER -> buildWineExplorerCommand(screenInfo)
                LaunchTarget.PROGRAM -> {
                    // Stage the picked exe into the container's C: drive (drive_c) and
                    // launch it as a Windows path. Wine's drive letters do not map the
                    // app-private SAF staging directory, while C: is always available.
                    val wineExePath = stageExeIntoPrefix(container, spec.exePath)
                    buildWineProgramCommand(screenInfo, wineExePath)
                }
            }
        Log.i("WineEngineImpl", "guestExecutable=$guestExecutable")
        launcher.setGuestExecutable(guestExecutable)
        launcher.setEnvVars(envVars)
        launcher.setBox64Preset(AdvancedRuntimePreferences.box64Preset(context))
        spec.workingDirectory?.let { launcher.setWorkingDir(File(it)) }
        return launcher
    }

    /**
     * Copy the staged exe into the container's `drive_c` and return its Wine path
     * (`C:\<name>`). Idempotent: skips the copy when the destination already matches
     * the source size (re-launching the same exe). The `C:` dosdevice -> `drive_c`
     * (createDosdevicesSymlinks), so `C:\<name>` resolves to the copied file.
     */
    private fun stageExeIntoPrefix(container: WinNativeContainer, exePath: String): String {
        val src = File(exePath)
        val exeName = src.name.ifEmpty { "amphora-game.exe" }
        val driveC = File(container.getRootDir(), ".wine/drive_c").apply { mkdirs() }
        val dest = File(driveC, exeName)
        if (!dest.exists() || dest.length() != src.length()) {
            FileUtils.copy(src, dest)
        }
        return "C:\\$exeName"
    }

    private companion object {
        const val PRELAUNCH_CLEANUP_TIMEOUT_MS = 1_000L

        /** Pinned imagefs version (WinNative `ImageFsInstaller.LATEST_VERSION`). */
        const val IMAGEFS_VERSION = ImageFsInstaller.LATEST_VERSION.toString()
    }
}

internal fun buildWineExplorerCommand(screenInfo: String): String =
    "wine explorer /desktop=shell,$screenInfo explorer.exe"

internal fun buildWineProgramCommand(screenInfo: String, wineExePath: String): String =
    "wine explorer /desktop=shell,$screenInfo \"$wineExePath\""
