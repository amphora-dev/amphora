package app.amphora.gamesession

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.amphora.core.engine.GameSessionSurface
import app.amphora.gamesession.input.TouchpadView
import com.winlator.cmod.runtime.display.renderer.VulkanRenderer
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView
import com.winlator.cmod.runtime.display.xserver.XServer

/** The Vulkan render surface (XSDA `setupUI`, L6914). Wires the renderer back to the XServer. */
@Composable
internal fun GameSurface(
    surface: GameSessionSurface,
    onViewReady: (XServerSurfaceView) -> Unit,
    onFirstGuestFrameRendered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val xServer = surface.xServer
    val graphicsDriver = surface.graphicsDriver
    val presentMode = surface.presentMode
    AndroidView(
        factory = { ctx ->
            XServerSurfaceView(ctx, xServer).also { view ->
                val renderer = view.getRenderer()
                // Host driver is pre-resolved by the engine: the wrapper ICD maps to
                // system Vulkan, while an explicit Turnip package stays on adrenotools.
                renderer.setGraphicsDriver(graphicsDriver)
                renderer.setCursorVisible(true)
                renderer.setNativeMode(true) // dri3
                renderer.setPresentMode(VulkanRenderer.parsePresentMode(presentMode))
                renderer.setSwapRB(false)
                // Do NOT hide the explorer.exe desktop window — amphora launches via
                // `explorer /desktop`, so the desktop IS the render target. Hiding it = black.
                // Keep relativeMouseMovement=false: buttons route via the X-protocol path (no
                // WinHandler, which is null in the MVP). The touch overlay still moves the
                // cursor by delta via injectPointerMoveDelta (relative cursor feel).
                xServer.setRenderer(renderer)
                view.setOnFirstGuestFrameRenderedListener(onFirstGuestFrameRendered)
                onViewReady(view)
            }
        },
        modifier = modifier,
        update = { view ->
            // Re-apply if the surface config changes without recreating the AndroidView.
            val renderer = view.getRenderer()
            renderer.setGraphicsDriver(graphicsDriver)
            renderer.setPresentMode(VulkanRenderer.parsePresentMode(presentMode))
            view.setOnFirstGuestFrameRenderedListener(onFirstGuestFrameRendered)
        },
        onRelease = { view ->
            view.setOnFirstGuestFrameRenderedListener(null)
            view.onPause()
        },
    )
}

/**
 * WinNative-parity mouse/touch overlay. Defaults to trackpad mode (relative delta +
 * tap/LMB, two-finger RMB/scroll, long-press RMB). External mouse and stylus are handled
 * on the same View via generic motion. Touchscreen absolute mode is available via
 * [TouchpadView.setScreenTouchMode].
 */
@Composable
internal fun TouchpadOverlay(
    xServer: XServer,
    onViewReady: (TouchpadView) -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            TouchpadView(ctx, xServer).also { pad ->
                // Keep relativeMouseMovement=false: without WinHandler, buttons must stay on
                // the X-protocol path. Trackpad still feels relative via injectPointerMoveDelta.
                xServer.setRelativeMouseMovement(false)
                pad.setScreenTouchMode(TouchpadView.MODE_TRACKPAD)
                pad.setFourFingersTapCallback(onOpenDrawer)
                onViewReady(pad)
            }
        },
        update = { pad ->
            pad.setFourFingersTapCallback(onOpenDrawer)
            onViewReady(pad)
        },
        modifier = modifier,
        onRelease = { pad ->
            pad.setFourFingersTapCallback(null)
            pad.releaseInput()
        },
    )
}
