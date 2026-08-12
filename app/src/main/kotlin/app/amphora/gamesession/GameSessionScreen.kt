package app.amphora.gamesession

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.amphora.core.engine.model.SessionState
import app.amphora.gamesession.input.TouchpadView
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView
import com.winlator.cmod.runtime.display.xserver.XServer
import com.winlator.cmod.shared.android.RefreshRateUtils
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
    val audioVolume by viewModel.audioVolume.collectAsState()
    var rendererView by remember { mutableStateOf<XServerSurfaceView?>(null) }
    var touchpadView by remember { mutableStateOf<TouchpadView?>(null) }
    var inputMode by rememberSaveable { mutableStateOf(TouchpadView.MODE_TRACKPAD) }
    var pointerSensitivity by rememberSaveable { mutableStateOf(1f) }
    var tapToClick by rememberSaveable { mutableStateOf(true) }
    var audioMuted by rememberSaveable { mutableStateOf(false) }
    var fpsLimit by rememberSaveable { mutableStateOf(0) }
    var stretchToFill by rememberSaveable { mutableStateOf(false) }
    var performanceHudVisible by rememberSaveable {
        mutableStateOf(viewModel.hostPerformanceHudEnabled)
    }
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    var firstGuestFrameRendered by remember { mutableStateOf(false) }
    var exitRequested by rememberSaveable { mutableStateOf(false) }
    var manuallyPaused by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    BackHandler {
        when {
            exitRequested -> Unit
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

    // Keep the physical display cadence aligned with the guest FPS limiter. For
    // example, 30 FPS prefers a 60/90/120 Hz mode instead of an incompatible
    // cadence, while "Off" follows the configured global rate (maximum by default).
    LaunchedEffect(activity, fpsLimit) {
        activity?.let {
            RefreshRateUtils.applyPreferredRefreshRate(
                it,
                RefreshRateUtils.getSavedGlobalRefreshRateOverride(it),
                fpsLimit,
            )
        }
    }
    DisposableEffect(activity, fpsLimit) {
        if (activity == null) {
            onDispose {}
        } else {
            val displayManager = activity.getSystemService(DisplayManager::class.java)
            val reapplyRefreshRate = {
                RefreshRateUtils.applyPreferredRefreshRate(
                    activity,
                    RefreshRateUtils.getSavedGlobalRefreshRateOverride(activity),
                    fpsLimit,
                )
                Unit
            }
            val listener =
                object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = reapplyRefreshRate()

                    override fun onDisplayRemoved(displayId: Int) = reapplyRefreshRate()

                    override fun onDisplayChanged(displayId: Int) = reapplyRefreshRate()
                }
            displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            onDispose { displayManager.unregisterDisplayListener(listener) }
        }
    }

    // STOPPED is emitted only after Wine and XEnvironment teardown completes.
    LaunchedEffect(sessionState) {
        when (sessionState) {
            SessionState.STOPPING -> {
                touchpadView?.apply {
                    resetInputState()
                    setMouseEnabled(false)
                }
                rendererView?.onPause()
            }
            SessionState.STOPPED -> {
                val closed =
                    withContext(Dispatchers.IO) {
                        rendererView?.closeAndJoin(RENDERER_CLOSE_TIMEOUT_MS) ?: true
                    }
                if (!closed) {
                    Log.w(TAG, "Renderer teardown timed out; session process exit will reclaim it")
                }
                onExit()
            }
            else -> Unit
        }
    }

    // Tie the session pause/resume to the host lifecycle (XEnvironment.onPause/onResume +
    // ProcessHelper.resumeAllWineProcesses - the render-thread pause is handled by the
    // SurfaceView's own SurfaceHolder lifecycle).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, rendererView, manuallyPaused, activity, fpsLimit) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        activity?.let {
                            RefreshRateUtils.applyPreferredRefreshRate(
                                it,
                                RefreshRateUtils.getSavedGlobalRefreshRateOverride(it),
                                fpsLimit,
                            )
                        }
                        rendererView?.onResume()
                        if (!manuallyPaused) viewModel.resume()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        touchpadView?.resetInputState()
                        rendererView?.onPause()
                        if (!manuallyPaused) viewModel.pause()
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
        } else {
            touchpadView?.requestFocus()
        }
    }
    LaunchedEffect(surface) {
        firstGuestFrameRendered = false
    }
    LaunchedEffect(exitRequested) {
        if (exitRequested) {
            touchpadView?.apply {
                resetInputState()
                setMouseEnabled(false)
            }
            rendererView?.onPause()
            drawerState.close()
            viewModel.stop()
        }
    }
    LaunchedEffect(inputMode, pointerSensitivity, tapToClick, sessionState, touchpadView) {
        touchpadView?.apply {
            setScreenTouchMode(inputMode)
            setSensitivity(pointerSensitivity)
            tapToClickEnabled = tapToClick
            setMouseEnabled(sessionState != SessionState.PAUSED)
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

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled =
            drawerState.currentValue == DrawerValue.Open ||
                drawerState.targetValue == DrawerValue.Open,
            drawerContent = {
                RuntimeSessionDrawer(
                    sessionState = sessionState,
                    controlsEnabled =
                    surface != null &&
                        sessionState != SessionState.STOPPING &&
                        !exitRequested,
                    inputMode = inputMode,
                    onInputModeChange = { inputMode = it },
                    pointerSensitivity = pointerSensitivity,
                    onPointerSensitivityChange = { pointerSensitivity = it },
                    tapToClick = tapToClick,
                    onTapToClickChange = { tapToClick = it },
                    audioVolume = audioVolume,
                    onAudioVolumeChange = viewModel::setAudioVolume,
                    audioMuted = audioMuted,
                    onAudioMutedChange = {
                        audioMuted = it
                        viewModel.setAudioMuted(it)
                    },
                    fpsLimit = fpsLimit,
                    onFpsLimitChange = { fpsLimit = it },
                    stretchToFill = stretchToFill,
                    onStretchToFillChange = { stretchToFill = it },
                    performanceHudVisible = performanceHudVisible,
                    onPerformanceHudVisibleChange = { performanceHudVisible = it },
                    onPauseToggle = {
                        if (sessionState == SessionState.PAUSED) {
                            manuallyPaused = false
                            viewModel.resume()
                        } else {
                            manuallyPaused = true
                            viewModel.pause()
                        }
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
                        onFirstGuestFrameRendered = { firstGuestFrameRendered = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                    TouchpadOverlay(
                        xServer = sessionSurface.xServer,
                        onViewReady = { touchpadView = it },
                        onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (performanceHudVisible && firstGuestFrameRendered) {
                        HostPerformanceOverlay(surface = sessionSurface)
                    }
                    if (sessionState == SessionState.PAUSED && firstGuestFrameRendered) {
                        PausedSessionOverlay()
                    }
                }
                if (
                    sessionSurface == null ||
                    (
                        !firstGuestFrameRendered &&
                            sessionState !in setOf(SessionState.STOPPING, SessionState.STOPPED)
                        )
                ) {
                    SessionPlaceholder(
                        sessionState = sessionState,
                        launchError = launchError,
                        provisionProgress = provisionProgress,
                        waitingForFirstFrame =
                        sessionSurface != null &&
                            sessionState == SessionState.RUNNING,
                        modifier = Modifier.fillMaxSize().zIndex(4f),
                    )
                }
            }
        }
        if (exitRequested || sessionState == SessionState.STOPPING) {
            SessionEndingOverlay(modifier = Modifier.fillMaxSize().zIndex(10f))
        }
    }

    if (showExitConfirmation) {
        ExitSessionConfirmationDialog(
            onDismiss = { showExitConfirmation = false },
            onConfirm = {
                showExitConfirmation = false
                if (running) exitRequested = true else onExit()
            },
        )
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

private const val TAG = "GameSessionScreen"
private const val RENDERER_CLOSE_TIMEOUT_MS = 5_000L
