package app.amphora.core.engine.model

import kotlinx.coroutines.flow.StateFlow

enum class SessionState { CREATED, STARTING, RUNNING, PAUSED, STOPPED, FAILED }

/**
 * Handle to a running Wine session (RFC §6). Backed by the ported XEnvironment
 * component lifecycle: pauseComponents / resumeComponents / stopComponents plus
 * ProcessHelper kill (RFC D9).
 */
interface SessionHandle {
    val state: StateFlow<SessionState>
    suspend fun awaitReady()
    suspend fun stop()
}
