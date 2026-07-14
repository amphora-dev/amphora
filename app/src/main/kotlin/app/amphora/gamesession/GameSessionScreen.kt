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
    AndroidView(
        factory = { ctx ->
            XServerSurfaceView(ctx, xServer).also { view ->
                val renderer = view.getRenderer()
                // TODO(P4): wire preparer graphicsDriverConfig (version / compositorPresentMode).
                renderer.setGraphicsDriver("System")
                renderer.setCursorVisible(false)
                renderer.setNativeMode(true) // dri3
                renderer.setPresentMode(VulkanRenderer.parsePresentMode(null))
                renderer.setSwapRB(false)
                renderer.setUnviewableWMClasses("explorer.exe")
                xServer.setRenderer(renderer)
            }
        },
        modifier = modifier,
    )
}

/**
 * Minimal touch overlay (D9 rewrite of `TouchpadView`). MVP is direct-touch: a touch becomes a
 * left-button drag - down presses + moves to the mapped position, move tracks, up releases.
 * Trackpad-mode (relative), tap-to-click tuning, and gesture profiles are P4+ (TouchpadView
 * parity) once the OSK / controls layer lands.
 */
@Composable
private fun TouchInputOverlay(xServer: XServer, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.pointerInput(xServer) {
            val screenW = xServer.screenInfo.width.toInt().coerceAtLeast(1)
            val screenH = xServer.screenInfo.height.toInt().coerceAtLeast(1)
            awaitEachGesture {
                awaitFirstDown().also { down ->
                    val (sx, sy) = mapToScreen(down.position, size, screenW, screenH)
                    xServer.injectPointerMove(sx, sy)
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.first()
                    if (!change.pressed) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                        break
                    }
                    val (sx, sy) = mapToScreen(change.position, size, screenW, screenH)
                    xServer.injectPointerMove(sx, sy)
                }
            }
        },
    )
}

private fun mapToScreen(position: Offset, viewSize: androidx.compose.ui.unit.IntSize, screenW: Int, screenH: Int): Pair<Int, Int> {
    val vw = viewSize.width.coerceAtLeast(1)
    val vh = viewSize.height.coerceAtLeast(1)
    val sx = (position.x / vw * screenW).toInt().coerceIn(0, screenW - 1)
    val sy = (position.y / vh * screenH).toInt().coerceIn(0, screenH - 1)
    return sx to sy
}

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
