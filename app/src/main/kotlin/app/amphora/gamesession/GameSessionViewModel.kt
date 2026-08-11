package app.amphora.gamesession

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.model.DEFAULT_CONTAINER_ID
import app.amphora.core.engine.GameSessionSurface
import app.amphora.core.engine.GameSessionSurfaceProvider
import app.amphora.core.engine.WineEngine
import app.amphora.core.engine.model.DisplaySize
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.LaunchTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates the Wine session lifecycle for the GameSession screen (RFC §6 / D9).
 *
 * On init it builds a [LaunchSpec] from the navigation args and asks [WineEngine] to launch.
 * The returned [SessionHandle.state] is forwarded to [sessionState] so the screen can react to
 * RUNNING / PAUSED / STOPPED / FAILED. [GameSessionSurfaceProvider.surface] (the [XServer]) is
 * forwarded so the screen can construct `XServerSurfaceView` + the touch overlay once the
 * engine has built the render target.
 *
 * The container id is the MVP single shared container (`"1"`, created on first
 * launch by [app.amphora.core.engine.WinlatorContainerManager]); multi-prefix /
 * container management is v0.2 (RFC §9). If launch fails, the error is surfaced
 * via [launchError] for the screen to display.
 */
@HiltViewModel
class GameSessionViewModel
@Inject
constructor(
    private val wineEngine: WineEngine,
    private val surfaceProvider: GameSessionSurfaceProvider,
    private val hostEnvironment: GameSessionHostEnvironment,
    private val dispatchers: DispatcherProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val surface: StateFlow<GameSessionSurface?> = surfaceProvider.surface
    val provisionProgress = wineEngine.provisionProgress
    val hostPerformanceHudEnabled = hostEnvironment.hostPerformanceHudEnabled
    private val audioSink = wineEngine.audioSink()
    val audioVolume = audioSink.volume

    private val coordinator =
        GameSessionCoordinator(
            scope = viewModelScope,
            actionDispatcher = dispatchers.io,
            launchSession = { request ->
                val diagEnv =
                    if (request.graphicsDiag) {
                        hostEnvironment.prepareGraphicsDiagnostics()
                    } else {
                        emptyMap()
                    }
                wineEngine.launch(
                    LaunchSpec(
                        exePath = request.exePath,
                        containerId = DEFAULT_CONTAINER_ID,
                        displaySize = DisplaySize(request.width, request.height),
                        target = request.target,
                        env = diagEnv,
                    ),
                )
            },
        )

    val sessionState = coordinator.sessionState
    val launchError = coordinator.launchError

    // Outlives viewModelScope so onCleared() can still run the suspend teardown.
    private val cleanupScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    init {
        val exePath = savedStateHandle.get<String>(EXE_PATH_ARG).orEmpty()
        val width = savedStateHandle.get<Int>(WIDTH_ARG) ?: DEFAULT_WIDTH
        val height = savedStateHandle.get<Int>(HEIGHT_ARG) ?: DEFAULT_HEIGHT
        val target =
            savedStateHandle
                .get<String>(TARGET_ARG)
                ?.let { runCatching { LaunchTarget.valueOf(it) }.getOrNull() }
                ?: LaunchTarget.PROGRAM
        val graphicsDiag = savedStateHandle.get<Boolean>(GRAPHICS_DIAG_ARG) == true
        coordinator.start(
            GameSessionLaunchRequest(
                exePath = exePath,
                width = width,
                height = height,
                target = target,
                graphicsDiag = graphicsDiag,
            ),
        )
    }

    fun stop() {
        coordinator.stop()
    }

    fun resume() {
        coordinator.resume()
    }

    fun pause() {
        coordinator.pause()
    }

    fun setAudioVolume(volume: Float) {
        viewModelScope.launch(dispatchers.io) {
            audioSink.setVolume(volume)
        }
    }

    fun setAudioMuted(muted: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            audioSink.setMuted(muted)
        }
    }

    override fun onCleared() {
        // ViewModel.onCleared() is empty; viewModelScope is already cancelled here,
        // so teardown runs on a scope that outlives it.
        coordinator.clear(cleanupScope)
    }

    private companion object {
        const val EXE_PATH_ARG = "exePath"
        const val WIDTH_ARG = "width"
        const val HEIGHT_ARG = "height"
        const val TARGET_ARG = "target"
        const val GRAPHICS_DIAG_ARG = "graphicsDiag"
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
    }
}
