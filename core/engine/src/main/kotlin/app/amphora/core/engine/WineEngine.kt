package app.amphora.core.engine

import app.amphora.core.content.ProvisionProgress
import app.amphora.core.engine.model.AudioSink
import app.amphora.core.engine.model.InputSink
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.SessionHandle
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable engine kernel surface (RFC §6). Feature layers depend only on this
 * interface; they never touch native internals. The implementation delegates to
 * the ported `com.winlator.cmod` runtime (XEnvironment / GuestProgramLauncher /
 * ALSAServer / VulkanRenderer) per RFC §7 / D9.
 *
 * MVP launch chain (Steam/shortcuts stripped):
 * ```
 * ContainerManager.getOrCreate
 *   -> WineSessionPreparer.ensureWinePrefix + extractDXWrapper + ensureLaunchRuntimeFiles
 *   -> XEnvironment.startEnvironmentComponents (ALSAServer + XServer + GuestProgramLauncher)
 *   -> GuestProgramLauncherComponent.execGuestProgram (box64 wine explorer /desktop=WxH exe)
 *   -> VulkanRenderer.attachSurface (AndroidView{SurfaceView})
 * ```
 */
interface WineEngine {
    /** Live download / extract progress while [launch] provisions remote content. */
    val provisionProgress: StateFlow<ProvisionProgress?>

    suspend fun launch(spec: LaunchSpec): SessionHandle

    fun inputFeed(): InputSink

    fun audioSink(): AudioSink
}
