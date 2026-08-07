package app.amphora.gamesession

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.engine.GameSessionSurface
import app.amphora.core.engine.model.SessionState
import app.amphora.gamesession.input.TouchpadView
import com.winlator.cmod.runtime.display.renderer.VulkanRenderer
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView
import com.winlator.cmod.runtime.display.xserver.XServer
import kotlin.math.roundToInt

/**
 * The Wine game-session screen (RFC §8 / D9): the Compose rewrite of WinNative's 10,995-line
 * `XServerDisplayActivity`. Once the engine has built the [XServer] (exposed via the VM's
 * `surface`), it renders into an [XServerSurfaceView] (Vulkan) with a [TouchpadView] overlay
 * (WinNative trackpad/touchscreen/stylus/external-mouse gestures, X-protocol inject only —
 * no WinHandler). Lifecycle (pause/resume/stop) delegates to the VM, which forwards to the
 * [app.amphora.core.engine.model.SessionHandle] (XEnvironment + ProcessHelper).
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

    BackHandler {
        when (sessionState) {
            SessionState.STARTING, SessionState.RUNNING, SessionState.PAUSED -> viewModel.stop()
            SessionState.STOPPING -> Unit
            else -> onExit()
        }
    }

    // Game content owns the whole physical display. System bars remain
    // transiently reachable with an edge swipe and are restored on exit.
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val controller =
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                WindowCompat.getInsetsController(it, it.decorView)
            }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // STOPPED is emitted only after Wine and XEnvironment teardown completes.
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

    val running =
        sessionState in
            setOf(
                SessionState.STARTING,
                SessionState.RUNNING,
                SessionState.PAUSED,
                SessionState.STOPPING,
            )

    Box(modifier = Modifier.fillMaxSize()) {
        val sessionSurface = surface
        if (sessionSurface != null) {
            GameSurface(surface = sessionSurface, modifier = Modifier.fillMaxSize())
            TouchpadOverlay(xServer = sessionSurface.xServer, modifier = Modifier.fillMaxSize())
            if (viewModel.hostPerformanceHudEnabled) {
                HostPerformanceOverlay(xServer = sessionSurface.xServer)
            }
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
            enabled = sessionState != SessionState.STOPPING,
            modifier = Modifier.padding(12.dp).align(Alignment.TopStart),
        ) { Text(if (sessionState == SessionState.STOPPING) "Closing…" else "Exit") }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

@Composable
private fun BoxScope.HostPerformanceOverlay(xServer: XServer) {
    val context = LocalContext.current
    val monitor = remember(xServer) { HostPerformanceMonitor(context, xServer) }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.stop() }
    }
    val stats by monitor.stats.collectAsState()
    Surface(
        modifier =
        Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp)
            .zIndex(2f),
        color = Color.Black.copy(alpha = 0.76f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "HOST · ALL APIs",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF80CBC4),
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${stats.fps.roundToInt()} FPS  ${frameTimeLabel(stats.fps)}",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                buildString {
                    append("CPU ${stats.appCpuPercent}%")
                    stats.gpuPercent?.let { append("  GPU $it%") }
                    append("  RAM ${stats.ramPercent}%")
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                buildString {
                    append("APP ${stats.appMemoryMb} MB")
                    stats.batteryTemperatureC?.let { append("  BAT %.1f°C".format(it)) }
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun frameTimeLabel(fps: Float): String = if (fps > 0.1f) "%.1f ms".format(1000f / fps) else "-- ms"

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
 * WinNative-parity mouse/touch overlay. Defaults to trackpad mode (relative delta +
 * tap/LMB, two-finger RMB/scroll, long-press RMB). External mouse and stylus are handled
 * on the same View via generic motion. Touchscreen absolute mode is available via
 * [TouchpadView.setScreenTouchMode].
 */
@Composable
private fun TouchpadOverlay(xServer: XServer, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            TouchpadView(ctx, xServer).also { pad ->
                // Keep relativeMouseMovement=false: without WinHandler, buttons must stay on
                // the X-protocol path. Trackpad still feels relative via injectPointerMoveDelta.
                xServer.setRelativeMouseMovement(false)
                pad.setScreenTouchMode(TouchpadView.MODE_TRACKPAD)
            }
        },
        modifier = modifier,
        onRelease = { pad -> pad.resetInputState() },
    )
}

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
