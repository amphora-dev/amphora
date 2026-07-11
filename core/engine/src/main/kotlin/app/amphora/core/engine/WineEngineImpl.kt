package app.amphora.core.engine

import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.ContainerManager
import app.amphora.core.container.model.Container
import app.amphora.core.engine.model.AudioSink
import app.amphora.core.engine.model.InputSink
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.SessionHandle
import app.amphora.core.rootfs.RootfsInstaller
import com.winlator.cmod.runtime.display.environment.XEnvironment
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [WineEngine] facade (RFC §6 / §7 / D9). Delegates to the ported
 * `com.winlator.cmod` runtime (XEnvironment / GuestProgramLauncherComponent /
 * VulkanRenderer) plus the sibling interfaces ([ContainerManager] P4,
 * [RootfsInstaller] P2, [WineSessionPreparer] P2/P3).
 *
 * **P1: compile-only skeleton.** [launch] wires the real dependency chain and
 * marks each not-yet-ported step `TODO` with its owning phase. [inputFeed] /
 * [audioSink] return the shared non-throwing stubs until P3 wires the XServer /
 * ALSAServer. Replaces [StubWineEngine] as the bound [WineEngine] (StubWineEngine
 * retained as a fallback - flip the binding in
 * [app.amphora.core.engine.di.EngineModule] to revert).
 *
 * MVP launch chain (RFC §6 / D9):
 * ```
 * RootfsInstaller.ensureInstalled              // P2
 *   -> ContainerManager.getOrCreate            // P4
 *   -> WineSessionPreparer.setupWineSystemFiles  // P2/P3 (XSDA body extraction)
 *   -> XEnvironment.startEnvironmentComponents // P3 (ALSAServer + XServer + GuestProgramLauncher)
 *   -> GuestProgramLauncherComponent.execGuestProgram  // P3 (box64 wine explorer /desktop=WxH exe)
 *   -> VulkanRenderer.attachSurface            // P3 (from GameSessionScreen SurfaceView)
 * ```
 */
@Singleton
class WineEngineImpl @Inject constructor(
    private val containerManager: ContainerManager,
    private val rootfsInstaller: RootfsInstaller,
    private val preparer: WineSessionPreparer,
    private val dispatchers: DispatcherProvider,
) : WineEngine {

    override suspend fun launch(spec: LaunchSpec): SessionHandle = withContext(dispatchers.default) {
        // 1. Rootfs imagefs must be installed before any prefix work (P2).
        ensureRootfs(spec)
        // 2. Wine container / WINEPREFIX (P4 ContainerManager real impl).
        val container = containerManager.getOrCreate(spec.containerId)
        // 3. Prefix + runtime files + DX wrapper + graphics driver (P2/P3 XSDA body extraction).
        preparer.setupWineSystemFiles(spec, container)
        // 4. XEnvironment: ALSAServer + XServer + GuestProgramLauncher (P3 setupXEnvironment, XSDA L6439).
        val environment = startEnvironment(container, spec)
        // 5. Launch guest: box64 wine explorer /desktop=WxH exe (P3; Amphora passes exe+env only, D9).
        launchGuestProgram(environment, spec)
        // 6. Render surface attaches from the UI layer (P3 GameSessionScreen -> VulkanRenderer.attachSurface).
        sessionHandleFor(environment)
    }

    override fun inputFeed(): InputSink =
        StubInputSink // P3: XServer-backed InputSink (xServer.injectPointerMove/Button)

    override fun audioSink(): AudioSink =
        StubAudioSink // P3: ALSAServer-backed AudioSink (volume/mute -> ALSAServerComponent)

    // --- launch steps (P2/P3 bodies) ------------------------------------------

    private suspend fun ensureRootfs(spec: LaunchSpec): Unit =
        TODO("P2: rootfsInstaller.ensureInstalled(RootfsSpec(targetRoot=<imagefs>, imagefsVersion=<pinned>, termuxfsSha256=<pinned>))")

    private suspend fun startEnvironment(container: Container, spec: LaunchSpec): XEnvironment =
        TODO("P3: XSDA setupXEnvironment (L6439) - construct XEnvironment(container, xServer) + startEnvironmentComponents() (ALSAServer + XServer + GuestProgramLauncher)")

    private suspend fun launchGuestProgram(environment: XEnvironment, spec: LaunchSpec): Unit =
        TODO("P3: GuestProgramLauncherComponent.execGuestProgram(getWineStartCommand(box64 wine explorer /desktop=WxH exe)) - Amphora passes exe+env only (D9)")

    private fun sessionHandleFor(environment: XEnvironment): SessionHandle =
        TODO("P3: SessionHandle backed by XEnvironment.pause/resume/stopComponents + ProcessHelper kill (RFC D9)")
}
