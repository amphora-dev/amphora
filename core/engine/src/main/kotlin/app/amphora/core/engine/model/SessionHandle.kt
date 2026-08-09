package app.amphora.core.engine.model

import kotlinx.coroutines.flow.StateFlow

enum class SessionState { CREATED, STARTING, RUNNING, PAUSED, STOPPING, STOPPED, FAILED }

/**
 * Handle to a running Wine session (RFC §6 / D9). Backed by the ported XEnvironment
 * component lifecycle: [pause] / [resume] map to `XEnvironment.onPause/onResume` plus
 * `ProcessHelper.pauseAllWineProcesses/resumeAllWineProcesses`; [stop] maps to
 * `stopEnvironmentComponents` + `ProcessHelper.terminateAllWineProcesses` (RFC D9).
 * Render-surface and input pause/resume are owned by the GameSession UI layer, not here.
 */
interface SessionHandle {
    val state: StateFlow<SessionState>

    suspend fun awaitReady()

    suspend fun pause()

    suspend fun resume()

    suspend fun stop()
}
