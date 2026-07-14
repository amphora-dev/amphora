package app.amphora.core.engine

import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.engine.model.SessionHandle
import app.amphora.core.engine.model.SessionState
import com.winlator.cmod.runtime.display.environment.XEnvironment
import com.winlator.cmod.runtime.display.xserver.XServer
import com.winlator.cmod.runtime.system.ProcessHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : SessionHandle {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(SessionState.CREATED)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val readiness = CompletableDeferred<Unit>()
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

    /**
     * Called from `GuestProgramLauncherComponent`'s termination callback when the guest
     * process exits on its own (game closed). Signals the UI to tear down; the actual
     * component teardown happens in [stop] (called by the VM on dispose / state change).
     * Does NOT run teardown so [stop] remains free to do it exactly once.
     */
    fun markStopped() {
        _state.value = SessionState.STOPPED
        readiness.complete(Unit)
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
        try {
            environment.stopEnvironmentComponents()
        } catch (e: Exception) {
            // stopEnvironmentComponents already swallows per-component failures; this is
            // a belt-and-braces guard so a teardown exception never escapes stop().
        }
        try {
            ProcessHelper.terminateAllWineProcesses()
        } catch (e: Exception) {
            // Best-effort; the kernel reaps stragglers via forceKillAllWineProcesses below.
        }
        try {
            if (ProcessHelper.listRunningWineProcesses().isNotEmpty()) {
                ProcessHelper.forceKillAllWineProcesses()
            }
        } catch (e: Exception) {
            // ignore
        }
        try {
            xServer.stop()
        } catch (e: Exception) {
            // ignore
        }
        _state.value = SessionState.STOPPED
        // Release anyone awaiting readiness so coroutines don't hang on a failed/stopped session.
        readiness.complete(Unit)
    }
}
