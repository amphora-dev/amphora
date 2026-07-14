package app.amphora.core.engine.model

import kotlinx.coroutines.flow.StateFlow

enum class SessionState { CREATED, STARTING, RUNNING, PAUSED, STOPPED, FAILED }

/**
 * Handle to a running Wine session (RFC §6 / D9). Backed by the ported XEnvironment
 * component lifecycle: [pause] / [resume] map to `XEnvironment.onPause/onResume` (+
 * `ProcessHelper.resumeAllWineProcesses`); [stop] maps to `stopEnvironmentComponents` +
 * `ProcessHelper.terminateAllWineProcesses` (RFC D9). The render-surface pause/resume
 * (`XServerSurfaceView.onPause/onResume`) is owned by the GameSession UI layer, not here.
 */
interface SessionHandle {
    val state: StateFlow<SessionState>
    suspend fun awaitReady()
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
}
