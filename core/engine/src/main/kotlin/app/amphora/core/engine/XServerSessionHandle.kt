package app.amphora.core.engine

import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.engine.model.SessionHandle
import app.amphora.core.engine.model.SessionState
import com.winlator.cmod.runtime.display.environment.XEnvironment
import com.winlator.cmod.runtime.display.xserver.XServer
import com.winlator.cmod.runtime.system.ProcessHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SessionHandle] backed by the ported `com.winlator.cmod` kernel (RFC §6 / D9).
 *
 * Lifecycle mapping (mirrors WinNative `XServerDisplayActivity` onPause/onResume/onDestroy
 * + `performForcedSessionCleanup`):
 * - [pause] -> `XEnvironment.onPause()` (suspends ALSA output / PulseAudio). The render
 *   thread pause (`XServerSurfaceView.onPause`) is owned by the GameSession UI.
 * - [resume] -> `XEnvironment.onResume()` + `ProcessHelper.resumeAllWineProcesses()`.
 * - [stop] -> `XEnvironment.stopEnvironmentComponents()` (reverse-order teardown: guest
 *   launcher first, then audio / XServer / shm) + `ProcessHelper.terminateAllWineProcesses()`
 *   with a `forceKillAllWineProcesses()` fallback.
 *
 * [awaitReady] completes once [markRunning] is called (after `startEnvironmentComponents()`
 * returns). State transitions are guarded by [mutex] so a concurrent [stop] during launch
 * can't tear down half-started components.
 */
internal class XServerSessionHandle(
    private val environment: XEnvironment,
    private val xServer: XServer,
    private val dispatchers: DispatcherProvider,
    private val processCleaner: SessionProcessCleaner = DefaultSessionProcessCleaner,
) : SessionHandle {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SessionState.CREATED)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val readiness = CompletableDeferred<Unit>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var teardownDone = false

    /** Called by [WineEngineImpl] just before `startEnvironmentComponents()`. */
    fun markStarting() {
        _state.value = SessionState.STARTING
    }

    /** Called by [WineEngineImpl] once the environment components have started. */
    fun markRunning() {
        _state.value = SessionState.RUNNING
        readiness.complete(Unit)
    }

    /** Called by [WineEngineImpl] if launch fails before running. */
    fun markFailed(cause: Throwable) {
        _state.value = SessionState.FAILED
        readiness.completeExceptionally(cause)
    }

    /** Guest exit requests teardown; STOPPED is published only after the full barrier. */
    fun requestStop() {
        cleanupScope.launch { stop() }
    }

    override suspend fun awaitReady() = readiness.await()

    override suspend fun pause() = mutex.withLock {
        if (_state.value == SessionState.RUNNING) {
            environment.onPause()
            _state.value = SessionState.PAUSED
        }
    }

    override suspend fun resume() = mutex.withLock {
        if (_state.value == SessionState.PAUSED) {
            environment.onResume()
            ProcessHelper.resumeAllWineProcesses()
            _state.value = SessionState.RUNNING
        }
    }

    override suspend fun stop() = mutex.withLock {
        if (teardownDone) return@withLock
        teardownDone = true
        val terminalState =
            if (_state.value == SessionState.FAILED) SessionState.FAILED else SessionState.STOPPED
        if (terminalState != SessionState.FAILED) {
            _state.value = SessionState.STOPPING
        }
        try {
            // Match WinNative's safe order: drain Wine clients while X/ALSA/SHM endpoints
            // still exist, then tear down the infrastructure they were using.
            processCleaner.terminateAndWait(PROCESS_EXIT_TIMEOUT_MS)
        } catch (e: Exception) {
            // The dedicated :session process performs a final defensive sweep before exit.
        }
        try {
            environment.stopEnvironmentComponents()
        } catch (e: Exception) {
            // stopEnvironmentComponents already swallows per-component failures; this is
            // a belt-and-braces guard so a teardown exception never escapes stop().
        }
        try {
            xServer.stop()
        } catch (e: Exception) {
            // ignore
        }
        _state.value = terminalState
        // Release anyone awaiting readiness so coroutines don't hang on a failed/stopped session.
        readiness.complete(Unit)
    }

    private companion object {
        const val PROCESS_EXIT_TIMEOUT_MS = 2_000L
    }
}
