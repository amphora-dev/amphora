package app.amphora.core.engine

import com.winlator.cmod.runtime.display.xserver.XServer
import kotlinx.coroutines.flow.StateFlow

/**
 * Kernel-typed surface handle for the GameSession UI (RFC §7 / D9). The [WineEngine]
 * interface stays kernel-free ("feature layers depend only on this interface, they never
 * touch native internals"); the *one* component that legitimately touches the kernel is
 * the GameSession screen — it is the D9 rewrite of WinNative's `XServerDisplayActivity`,
 * so it must construct `XServerSurfaceView(ctx, xServer)` and therefore needs the [XServer].
 *
 * [GameSessionSurfaceProvider] is implemented by [WineEngineImpl] (the concrete facade) and
 * bound separately in [app.amphora.core.engine.di.EngineModule]; the GameSessionViewModel
 * injects it alongside [WineEngine]. [surface] emits the [XServer] once [WineEngine.launch]
 * has constructed it, and `null` before launch / after stop — the screen creates the
 * `XServerSurfaceView` only while a surface is present.
 */
interface GameSessionSurfaceProvider {
    val surface: StateFlow<GameSessionSurface?>
}

/**
 * The kernel render surface for an active session. Holds the [XServer] the GameSession UI
 * needs to construct `XServerSurfaceView` + the touch overlay. The `VulkanRenderer` itself
 * is created inside `XServerSurfaceView` and wired back via `xServer.setRenderer(...)`
 * (XSDA `setupUI`, L6914); [xServer] is the single shared handle.
 *
 * [graphicsDriver] / [presentMode] come from the container's `graphicsDriverConfig`
 * (`version=` / `presentMode=`) so the host renderer loads the same adrenotools driver
 * id the guest uses (`VK_ICD_FILENAMES=wrapper_icd...`). Hardcoding `"wrapper"` in the
 * UI previously drifted from the container and caused black screens when they diverged.
 */
data class GameSessionSurface(
    val xServer: XServer,
    /** Adrenotools driver id for `VulkanRenderer.setGraphicsDriver` (config `version=`). */
    val graphicsDriver: String = "wrapper",
    /** Optional compositor present mode (`mailbox` / `fifo` / …); null → renderer default. */
    val presentMode: String? = null,
    /** Configured guest D3D translation stack, for example `DXVK 2.7 + VKD3D 2.14`. */
    val guestGraphicsBackend: String = "WineD3D / auto",
    /** Active Wine content identifier. */
    val wineVersion: String? = null,
)
