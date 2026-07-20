package app.amphora.gamesession

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.amphora.core.engine.model.SessionState
import com.winlator.cmod.runtime.display.renderer.VulkanRenderer
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView
import com.winlator.cmod.runtime.display.xserver.Pointer
import com.winlator.cmod.runtime.display.xserver.XServer

const val GameSessionRoute = "game_session"

private const val GAME_SESSION_ROUTE_WITH_ARGS =
    "$GameSessionRoute?exePath={exePath}&width={width}&height={height}"

/** Builds the navigation URL for the game-session route (exe path URL-encoded). */
fun gameSessionRoute(exePath: String, width: Int = 1280, height: Int = 720): String =
    "$GameSessionRoute?exePath=${Uri.encode(exePath)}&width=$width&height=$height"

fun NavGraphBuilder.gameSessionScreen(onExit: () -> Unit) {
    composable(
        route = GAME_SESSION_ROUTE_WITH_ARGS,
        arguments = listOf(
            navArgument("exePath") { type = NavType.StringType; defaultValue = "" },
            navArgument("width") { type = NavType.IntType; defaultValue = 1280 },
            navArgument("height") { type = NavType.IntType; defaultValue = 720 },
        ),
    ) {
        GameSessionScreen(viewModel = hiltViewModel(), onExit = onExit)
    }
}

/**
 * The Wine game-session screen (RFC §8 / D9): the Compose rewrite of WinNative's 10,995-line
 * `XServerDisplayActivity`. Once the engine has built the [XServer] (exposed via the VM's
 * `surface`), it renders into an [XServerSurfaceView] (Vulkan) with a touch overlay that maps
 * touches to X pointer events. Lifecycle (pause/resume/stop) delegates to the VM, which
 * forwards to the [app.amphora.core.engine.model.SessionHandle] (XEnvironment + ProcessHelper).
 *
 * P4: the launch chain is real ([app.amphora.core.container.ContainerManager] + launcher
 * exe picker wired); if a session fails to start the error is surfaced here + an Exit button.
 */
@Composable
internal fun GameSessionScreen(viewModel: GameSessionViewModel, onExit: () -> Unit) {
    val surface by viewModel.surface.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val launchError by viewModel.launchError.collectAsState()

    // Auto-exit when the guest process terminates normally (SessionHandle.markStopped).
    LaunchedEffect(sessionState) {
        if (sessionState == SessionState.STOPPED) onExit()
    }

    // Tie the session pause/resume to the host lifecycle (XEnvironment.onPause/onResume +
    // ProcessHelper.resumeAllWineProcesses - the render-thread pause is handled by the
    // SurfaceView's own SurfaceHolder lifecycle).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.resume()
                Lifecycle.Event.ON_PAUSE -> viewModel.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val running = sessionState in setOf(SessionState.STARTING, SessionState.RUNNING, SessionState.PAUSED)

    Box(modifier = Modifier.fillMaxSize()) {
        val xServer = surface?.xServer
        android.util.Log.i("AMP_SURFACE", "GameSessionScreen recompose: surface=" + (if (surface != null) "non-null" else "null") + " sessionState=$sessionState")
        if (xServer != null) {
            GameSurface(xServer = xServer, modifier = Modifier.fillMaxSize())
            TouchInputOverlay(xServer = xServer, modifier = Modifier.fillMaxSize())
        } else {
            SessionPlaceholder(sessionState = sessionState, launchError = launchError)
        }

        // Exit affordance (top-start): stops a running session (auto-exit follows on STOPPED),
        // or exits immediately when not running.
        Button(
            onClick = { if (running) viewModel.stop() else onExit() },
            modifier = Modifier.padding(12.dp).align(Alignment.TopStart),
        ) { Text("Exit") }
    }
}

/** The Vulkan render surface (XSDA `setupUI`, L6914). Wires the renderer back to the XServer. */
@Composable
private fun GameSurface(xServer: XServer, modifier: Modifier = Modifier) {
    android.util.Log.i("AMP_SURFACE", "GameSurface: AndroidView factory xServer=$xServer")
    AndroidView(
        factory = { ctx ->
            XServerSurfaceView(ctx, xServer).also { view ->
                android.util.Log.i("AMP_SURFACE", "GameSurface: XServerSurfaceView created, renderer=${view.getRenderer()}")
                val renderer = view.getRenderer()
                // TODO(P4): wire preparer graphicsDriverConfig (version / compositorPresentMode).
                // "wrapper" = use adrenotools-wrapped Turnip+freedreno (the bundled driver in
                // imagefs/usr/lib). "System" would bypass the wrapper and dlopen the host
                // /system/lib64/libvulkan.so, which renders to a different Vulkan instance than
                // the guest (VK_ICD_FILENAMES=wrapper_icd.aarch64.json) — black screen.
                renderer.setGraphicsDriver("wrapper")
                renderer.setCursorVisible(true)
                renderer.setNativeMode(true) // dri3
                renderer.setPresentMode(VulkanRenderer.parsePresentMode(null))
                renderer.setSwapRB(false)
                // DEBUG: do NOT hide the explorer.exe desktop window — amphora launches via
                // `explorer /desktop`, so the desktop IS the render target. Hiding it = black.
                // Keep relativeMouseMovement=false: buttons route via the X-protocol path (no
                // WinHandler, which is null in the MVP). The touch overlay still moves the
                // cursor by delta via injectPointerMoveDelta (relative cursor feel).
                xServer.setRenderer(renderer)
            }
        },
        modifier = modifier,
    )
}

/**
 * Minimal touch overlay (D9 rewrite of `TouchpadView`). Touchpad/relative mode:
 * a drag moves the cursor by the finger's delta (not absolute), a quick tap
 * (little movement, <250ms) is a left click. Drag-to-select (tap then drag) and
 * gesture profiles are P4+ (TouchpadView parity) once the OSK / controls layer lands.
 */
@Composable
private fun TouchInputOverlay(xServer: XServer, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.pointerInput(xServer) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var lastX = down.position.x
                var lastY = down.position.y
                val downX = down.position.x
                val downY = down.position.y
                val downTime = System.currentTimeMillis()
                var moved = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.first()
                    if (!change.pressed) {
                        // Tap = left click (little movement, short hold). Drag = just move, no click.
                        val dt = System.currentTimeMillis() - downTime
                        val totalMove = Math.abs(change.position.x - downX) + Math.abs(change.position.y - downY)
                        if (!moved && dt < 250 && totalMove < 24f) {
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                        }
                        break
                    }
                    val dx = (change.position.x - lastX).toInt()
                    val dy = (change.position.y - lastY).toInt()
                    if (dx != 0 || dy != 0) {
                        xServer.injectPointerMoveDelta(dx, dy)
                        if (totalMove(change.position.x, change.position.y, downX, downY) > 24f) moved = true
                    }
                    lastX = change.position.x
                    lastY = change.position.y
                }
            }
        },
    )
}

private fun totalMove(x: Float, y: Float, downX: Float, downY: Float): Float =
    Math.abs(x - downX) + Math.abs(y - downY)

@Composable
private fun SessionPlaceholder(sessionState: SessionState?, launchError: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            launchError != null -> {
                Text("Session failed", style = MaterialTheme.typography.titleMedium)
                Text(
                    launchError,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            sessionState == SessionState.STARTING -> Text("Starting session…")
            else -> Text("Initializing…")
        }
    }
}
