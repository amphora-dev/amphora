package app.amphora.gamesession

import android.net.Uri
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.engine.GameSessionSurface
import app.amphora.core.engine.model.SessionState
import com.winlator.cmod.runtime.display.renderer.VulkanRenderer
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView
import com.winlator.cmod.runtime.display.xserver.Pointer
import com.winlator.cmod.runtime.display.xserver.XServer

const val GAME_SESSION_ROUTE = "game_session"

private const val GAME_SESSION_ROUTE_WITH_ARGS =
    "$GAME_SESSION_ROUTE?exePath={exePath}&width={width}&height={height}&graphicsDiag={graphicsDiag}"

/** Builds the navigation URL for the game-session route (exe path URL-encoded). */
fun gameSessionRoute(exePath: String, width: Int = 1280, height: Int = 720, graphicsDiag: Boolean = false): String =
    "$GAME_SESSION_ROUTE?exePath=${Uri.encode(exePath)}&width=$width&height=$height" +
        "&graphicsDiag=$graphicsDiag"

fun NavGraphBuilder.gameSessionScreen(onExit: () -> Unit) {
    composable(
        route = GAME_SESSION_ROUTE_WITH_ARGS,
        arguments =
        listOf(
            navArgument("exePath") {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument("width") {
                type = NavType.IntType
                defaultValue = 1280
            },
            navArgument("height") {
                type = NavType.IntType
                defaultValue = 720
            },
            navArgument("graphicsDiag") {
                type = NavType.BoolType
                defaultValue = false
            },
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
    val provisionProgress by viewModel.provisionProgress.collectAsState()

    // Auto-exit when the guest process terminates normally (SessionHandle.markStopped).
    LaunchedEffect(sessionState) {
        if (sessionState == SessionState.STOPPED) onExit()
    }

    // Tie the session pause/resume to the host lifecycle (XEnvironment.onPause/onResume +
    // ProcessHelper.resumeAllWineProcesses - the render-thread pause is handled by the
    // SurfaceView's own SurfaceHolder lifecycle).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
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
        val sessionSurface = surface
        if (sessionSurface != null) {
            GameSurface(surface = sessionSurface, modifier = Modifier.fillMaxSize())
            TouchInputOverlay(xServer = sessionSurface.xServer, modifier = Modifier.fillMaxSize())
        } else {
            SessionPlaceholder(
                sessionState = sessionState,
                launchError = launchError,
                provisionProgress = provisionProgress,
            )
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
private fun GameSurface(surface: GameSessionSurface, modifier: Modifier = Modifier) {
    val xServer = surface.xServer
    val graphicsDriver = surface.graphicsDriver
    val presentMode = surface.presentMode
    AndroidView(
        factory = { ctx ->
            XServerSurfaceView(ctx, xServer).also { view ->
                val renderer = view.getRenderer()
                // Host must match guest ICD: container graphicsDriverConfig `version=`
                // (adrenotools id, typically "wrapper"). "System" = host Adreno ≠ Turnip.
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
            }
        },
        modifier = modifier,
        update = { view ->
            // Re-apply if the surface config changes without recreating the AndroidView.
            val renderer = view.getRenderer()
            renderer.setGraphicsDriver(graphicsDriver)
            renderer.setPresentMode(VulkanRenderer.parsePresentMode(presentMode))
        },
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
        modifier =
        modifier.pointerInput(xServer) {
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

private fun totalMove(x: Float, y: Float, downX: Float, downY: Float): Float = Math.abs(x - downX) + Math.abs(y - downY)

@Composable
private fun SessionPlaceholder(
    sessionState: SessionState?,
    launchError: String?,
    provisionProgress: ProvisionProgress?,
) {
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
            provisionProgress != null -> {
                Text("Updating content…", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        provisionProgress.stage,
                        provisionProgress.detail.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val fraction = provisionProgress.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                    provisionProgress.totalBytes?.let { total ->
                        Text(
                            "${formatBytes(provisionProgress.bytesDownloaded)} / ${formatBytes(total)}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                }
            }
            sessionState == SessionState.STARTING -> Text("Starting session…")
            else -> Text("Initializing…")
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
