package app.amphora.gamesession

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var rendererView by remember { mutableStateOf<XServerSurfaceView?>(null) }
    var touchpadView by remember { mutableStateOf<TouchpadView?>(null) }
    var inputMode by rememberSaveable { mutableStateOf(TouchpadView.MODE_TRACKPAD) }
    var pointerSensitivity by rememberSaveable { mutableStateOf(1f) }
    var tapToClick by rememberSaveable { mutableStateOf(true) }
    var fpsLimit by rememberSaveable { mutableStateOf(0) }
    var stretchToFill by rememberSaveable { mutableStateOf(false) }
    var performanceHudVisible by rememberSaveable {
        mutableStateOf(viewModel.hostPerformanceHudEnabled)
    }
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    BackHandler {
        when {
            drawerState.isOpen -> drawerScope.launch { drawerState.close() }
            sessionState in
                setOf(
                    SessionState.STARTING,
                    SessionState.RUNNING,
                    SessionState.PAUSED,
                ) -> drawerScope.launch { drawerState.open() }
            sessionState == SessionState.STOPPING -> Unit
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
        if (sessionState == SessionState.STOPPED) {
            val closed =
                withContext(Dispatchers.IO) {
                    rendererView?.closeAndJoin(RENDERER_CLOSE_TIMEOUT_MS) ?: true
                }
            if (!closed) {
                Log.w(TAG, "Renderer teardown timed out; session process exit will reclaim it")
            }
            onExit()
        }
    }

    // Tie the session pause/resume to the host lifecycle (XEnvironment.onPause/onResume +
    // ProcessHelper.resumeAllWineProcesses - the render-thread pause is handled by the
    // SurfaceView's own SurfaceHolder lifecycle).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, rendererView) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        rendererView?.onResume()
                        viewModel.resume()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        rendererView?.onPause()
                        viewModel.pause()
                    }
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

    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) {
            touchpadView?.resetInputState()
        }
    }
    LaunchedEffect(inputMode, pointerSensitivity, tapToClick, touchpadView) {
        touchpadView?.apply {
            setScreenTouchMode(inputMode)
            setSensitivity(pointerSensitivity)
            tapToClickEnabled = tapToClick
        }
    }
    LaunchedEffect(fpsLimit, rendererView) {
        rendererView?.renderer?.setFpsLimit(fpsLimit)
    }
    LaunchedEffect(stretchToFill, rendererView, touchpadView) {
        val renderer = rendererView?.renderer ?: return@LaunchedEffect
        if (renderer.isFullscreen != stretchToFill) {
            renderer.toggleFullscreen()
            touchpadView?.toggleFullscreen()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            RuntimeSessionDrawer(
                sessionState = sessionState,
                controlsEnabled = surface != null && sessionState != SessionState.STOPPING,
                inputMode = inputMode,
                onInputModeChange = { inputMode = it },
                pointerSensitivity = pointerSensitivity,
                onPointerSensitivityChange = { pointerSensitivity = it },
                tapToClick = tapToClick,
                onTapToClickChange = { tapToClick = it },
                fpsLimit = fpsLimit,
                onFpsLimitChange = { fpsLimit = it },
                stretchToFill = stretchToFill,
                onStretchToFillChange = { stretchToFill = it },
                performanceHudVisible = performanceHudVisible,
                onPerformanceHudVisibleChange = { performanceHudVisible = it },
                onPauseToggle = {
                    if (sessionState == SessionState.PAUSED) viewModel.resume() else viewModel.pause()
                },
                onClose = { drawerScope.launch { drawerState.close() } },
                onExit = { showExitConfirmation = true },
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val sessionSurface = surface
            if (sessionSurface != null) {
                GameSurface(
                    surface = sessionSurface,
                    onViewReady = { rendererView = it },
                    modifier = Modifier.fillMaxSize(),
                )
                TouchpadOverlay(
                    xServer = sessionSurface.xServer,
                    onViewReady = { touchpadView = it },
                    onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                    modifier = Modifier.fillMaxSize(),
                )
                if (performanceHudVisible) {
                    HostPerformanceOverlay(xServer = sessionSurface.xServer)
                }
            } else {
                SessionPlaceholder(
                    sessionState = sessionState,
                    launchError = launchError,
                    provisionProgress = provisionProgress,
                )
            }
        }
    }

    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("End this session?") },
            text = { Text("The Windows program and all processes in this session will be closed.") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirmation = false
                        if (running) viewModel.stop() else onExit()
                    },
                ) {
                    Text("End session")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RuntimeSessionDrawer(
    sessionState: SessionState?,
    controlsEnabled: Boolean,
    inputMode: Int,
    onInputModeChange: (Int) -> Unit,
    pointerSensitivity: Float,
    onPointerSensitivityChange: (Float) -> Unit,
    tapToClick: Boolean,
    onTapToClickChange: (Boolean) -> Unit,
    fpsLimit: Int,
    onFpsLimitChange: (Int) -> Unit,
    stretchToFill: Boolean,
    onStretchToFillChange: (Boolean) -> Unit,
    performanceHudVisible: Boolean,
    onPerformanceHudVisibleChange: (Boolean) -> Unit,
    onPauseToggle: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Session controls", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        runtimeStatusLabel(sessionState),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onClose) { Text("Close") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onPauseToggle,
                    enabled =
                    controlsEnabled &&
                        sessionState in setOf(SessionState.RUNNING, SessionState.PAUSED),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (sessionState == SessionState.PAUSED) "Resume" else "Pause")
                }
                OutlinedButton(
                    onClick = onExit,
                    enabled = sessionState != SessionState.STOPPING,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("End session")
                }
            }

            RuntimeDrawerSection(title = "Input") {
                Text("Touch mode", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = inputMode == TouchpadView.MODE_TRACKPAD,
                        onClick = { onInputModeChange(TouchpadView.MODE_TRACKPAD) },
                        enabled = controlsEnabled,
                        label = { Text("Trackpad") },
                    )
                    FilterChip(
                        selected = inputMode == TouchpadView.MODE_TOUCHSCREEN,
                        onClick = { onInputModeChange(TouchpadView.MODE_TOUCHSCREEN) },
                        enabled = controlsEnabled,
                        label = { Text("Direct touch") },
                    )
                }
                Text(
                    "Pointer speed · ${"%.1f".format(pointerSensitivity)}×",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = pointerSensitivity,
                    onValueChange = onPointerSensitivityChange,
                    enabled = controlsEnabled && inputMode == TouchpadView.MODE_TRACKPAD,
                    valueRange = 0.5f..2f,
                )
                RuntimeToggleRow(
                    title = "Tap to click",
                    subtitle = "One-finger tap sends a left click",
                    checked = tapToClick,
                    enabled = controlsEnabled,
                    onCheckedChange = onTapToClickChange,
                )
            }

            RuntimeDrawerSection(title = "Display") {
                Text("Frame limit", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FPS_LIMITS.forEach { limit ->
                        FilterChip(
                            selected = fpsLimit == limit,
                            onClick = { onFpsLimitChange(limit) },
                            enabled = controlsEnabled,
                            label = { Text(if (limit == 0) "Off" else "$limit") },
                        )
                    }
                }
                RuntimeToggleRow(
                    title = "Stretch to fill",
                    subtitle = "Fill the display instead of preserving aspect ratio",
                    checked = stretchToFill,
                    enabled = controlsEnabled,
                    onCheckedChange = onStretchToFillChange,
                )
                RuntimeToggleRow(
                    title = "Performance overlay",
                    subtitle = "FPS, CPU, GPU, memory and temperature",
                    checked = performanceHudVisible,
                    enabled = controlsEnabled,
                    onCheckedChange = onPerformanceHudVisibleChange,
                )
            }

            Text(
                "Press Back during play to open this panel. A four-finger tap also works in trackpad mode.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun RuntimeDrawerSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RuntimeToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

private fun runtimeStatusLabel(sessionState: SessionState?): String = when (sessionState) {
    SessionState.CREATED -> "Preparing session"
    SessionState.STARTING -> "Starting Windows session"
    SessionState.RUNNING -> "Running"
    SessionState.PAUSED -> "Paused"
    SessionState.STOPPING -> "Closing session"
    SessionState.STOPPED -> "Session ended"
    SessionState.FAILED -> "Session failed"
    null -> "Preparing session"
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
private fun GameSurface(
    surface: GameSessionSurface,
    onViewReady: (XServerSurfaceView) -> Unit,
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
                onViewReady(view)
            }
        },
        modifier = modifier,
        update = { view ->
            // Re-apply if the surface config changes without recreating the AndroidView.
            val renderer = view.getRenderer()
            renderer.setGraphicsDriver(graphicsDriver)
            renderer.setPresentMode(VulkanRenderer.parsePresentMode(presentMode))
        },
        onRelease = { view -> view.onPause() },
    )
}

/**
 * WinNative-parity mouse/touch overlay. Defaults to trackpad mode (relative delta +
 * tap/LMB, two-finger RMB/scroll, long-press RMB). External mouse and stylus are handled
 * on the same View via generic motion. Touchscreen absolute mode is available via
 * [TouchpadView.setScreenTouchMode].
 */
@Composable
private fun TouchpadOverlay(
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

@Composable
private fun SessionPlaceholder(
    sessionState: SessionState?,
    launchError: String?,
    provisionProgress: ProvisionProgress?,
) {
    val title =
        when {
            launchError != null -> "Session failed"
            provisionProgress != null -> "Updating content…"
            sessionState == SessionState.STARTING -> "Starting session…"
            else -> "Initializing…"
        }
    val detail =
        when {
            launchError != null -> launchError
            provisionProgress != null ->
                listOfNotNull(
                    provisionProgress.stage,
                    provisionProgress.detail.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
            else -> ""
        }
    val showProgress = launchError == null && provisionProgress != null
    val bytesLabel =
        provisionProgress
            ?.totalBytes
            ?.let { total ->
                "${formatBytes(provisionProgress.bytesDownloaded)} / ${formatBytes(total)}"
            }.orEmpty()
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (showProgress && provisionProgress != null) {
                val fraction = provisionProgress.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (bytesLabel.isNotBlank()) {
                    Text(bytesLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

private const val TAG = "GameSessionScreen"
private const val RENDERER_CLOSE_TIMEOUT_MS = 5_000L
private val FPS_LIMITS = listOf(0, 30, 45, 60, 90, 120)
