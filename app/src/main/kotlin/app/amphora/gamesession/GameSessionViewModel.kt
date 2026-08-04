package app.amphora.gamesession

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.container.model.DEFAULT_CONTAINER_ID
import app.amphora.core.engine.AdvancedRuntimePreferences
import app.amphora.core.engine.GameSessionSurface
import app.amphora.core.engine.GameSessionSurfaceProvider
import app.amphora.core.engine.GraphicsDiag
import app.amphora.core.engine.WineEngine
import app.amphora.core.engine.model.DisplaySize
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.SessionHandle
import app.amphora.core.engine.model.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val surface: StateFlow<GameSessionSurface?> = surfaceProvider.surface
    val provisionProgress = wineEngine.provisionProgress
    val hostPerformanceHudEnabled =
        AdvancedRuntimePreferences.hostPerformanceHudEnabled(appContext)

    private val _sessionState = MutableStateFlow<SessionState?>(null)
    val sessionState: StateFlow<SessionState?> = _sessionState.asStateFlow()

    private val _launchError = MutableStateFlow<String?>(null)
    val launchError: StateFlow<String?> = _launchError.asStateFlow()

    private var handle: SessionHandle? = null

    // Outlives viewModelScope so onCleared() can still run the suspend teardown.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        val exePath = savedStateHandle.get<String>(EXE_PATH_ARG).orEmpty()
        val width = savedStateHandle.get<Int>(WIDTH_ARG) ?: DEFAULT_WIDTH
        val height = savedStateHandle.get<Int>(HEIGHT_ARG) ?: DEFAULT_HEIGHT
        val graphicsDiag = savedStateHandle.get<Boolean>(GRAPHICS_DIAG_ARG) == true
        if (exePath.isEmpty()) {
            _launchError.value = "No game selected"
            _sessionState.value = SessionState.FAILED
        } else {
            launch(exePath, width, height, graphicsDiag)
        }
    }

    private fun launch(exePath: String, width: Int, height: Int, graphicsDiag: Boolean) {
        viewModelScope.launch {
            _sessionState.value = SessionState.STARTING
            try {
                val diagEnv =
                    if (graphicsDiag) {
                        GraphicsDiag.clearStateCache(appContext)
                        val env = GraphicsDiag.launchEnv(appContext)
                        Log.i(
                            GraphicsDiag.TAG,
                            "Graphics diag ON; DXVK logs → ${env["DXVK_LOG_PATH"]}",
                        )
                        env
                    } else {
                        emptyMap()
                    }
                val spec =
                    LaunchSpec(
                        exePath = exePath,
                        containerId = DEFAULT_CONTAINER_ID,
                        displaySize = DisplaySize(width, height),
                        env = diagEnv,
                    )
                val h = wineEngine.launch(spec)
                handle = h
                // Forward handle state to the screen.
                viewModelScope.launch { h.state.collect { _sessionState.value = it } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Launch boundary: never let a session-start failure crash the app - surface it.
                _launchError.value = e.message ?: e.javaClass.simpleName
                _sessionState.value = SessionState.FAILED
            }
        }
    }

    fun stop() {
        // Teardown joins native X connector threads — never block Main (ANR).
        viewModelScope.launch(Dispatchers.IO) { handle?.stop() }
    }

    fun resume() {
        viewModelScope.launch(Dispatchers.IO) { handle?.resume() }
    }

    fun pause() {
        viewModelScope.launch(Dispatchers.IO) { handle?.pause() }
    }

    override fun onCleared() {
        // ViewModel.onCleared() is empty; viewModelScope is already cancelled here,
        // so teardown runs on a scope that outlives it.
        cleanupScope.launch { handle?.stop() }
    }

    private companion object {
        const val EXE_PATH_ARG = "exePath"
        const val WIDTH_ARG = "width"
        const val HEIGHT_ARG = "height"
        const val GRAPHICS_DIAG_ARG = "graphicsDiag"
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
    }
}
