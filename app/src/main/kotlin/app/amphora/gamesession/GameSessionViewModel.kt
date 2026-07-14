package app.amphora.gamesession

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amphora.core.container.model.ContainerId
import app.amphora.core.engine.GameSessionSurface
import app.amphora.core.engine.GameSessionSurfaceProvider
import app.amphora.core.engine.WineEngine
import app.amphora.core.engine.model.DisplaySize
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.SessionHandle
import app.amphora.core.engine.model.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Orchestrates the Wine session lifecycle for the GameSession screen (RFC §6 / D9).
 *
 * On init it builds a [LaunchSpec] from the navigation args and asks [WineEngine] to launch.
 * The returned [SessionHandle.state] is forwarded to [sessionState] so the screen can react to
 * RUNNING / PAUSED / STOPPED / FAILED. [GameSessionSurfaceProvider.surface] (the [XServer]) is
 * forwarded so the screen can construct `XServerSurfaceView` + the touch overlay once the
 * engine has built the render target.
 *
 * The container id is a fixed placeholder until P4 ships the real [app.amphora.core.container.ContainerManager]
 * + the launcher exe picker (the launch chain throws at the P4 container stub either way, so
 * this is compile-only wiring until then - mirrors the P2 `XServerWineSessionPreparer` graduation).
 */
@HiltViewModel
class GameSessionViewModel @Inject constructor(
    private val wineEngine: WineEngine,
    private val surfaceProvider: GameSessionSurfaceProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val surface: StateFlow<GameSessionSurface?> = surfaceProvider.surface

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
        if (exePath.isEmpty()) {
            _launchError.value = "No game selected (exe picker is P4)"
            _sessionState.value = SessionState.FAILED
        } else {
            launch(exePath, width, height)
        }
    }

    private fun launch(exePath: String, width: Int, height: Int) {
        viewModelScope.launch {
            _sessionState.value = SessionState.STARTING
            try {
                val spec = LaunchSpec(
                    exePath = exePath,
                    containerId = ContainerId("default"), // TODO(P4): real container from launcher.
                    displaySize = DisplaySize(width, height),
                )
                val h = wineEngine.launch(spec)
                handle = h
                // Forward handle state to the screen.
                viewModelScope.launch { h.state.collect { _sessionState.value = it } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Launch boundary: never let a session-start failure (incl. the P4
                // ContainerManager NotImplementedError stub) crash the app - surface it.
                _launchError.value = e.message ?: e.javaClass.simpleName
                _sessionState.value = SessionState.FAILED
            }
        }
    }

    fun stop() {
        viewModelScope.launch { handle?.stop() }
    }

    fun resume() {
        viewModelScope.launch { handle?.resume() }
    }

    fun pause() {
        viewModelScope.launch { handle?.pause() }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is already cancelled here; use a surviving scope for teardown.
        cleanupScope.launch { handle?.stop() }
    }

    private companion object {
        const val EXE_PATH_ARG = "exePath"
        const val WIDTH_ARG = "width"
        const val HEIGHT_ARG = "height"
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
    }
}
