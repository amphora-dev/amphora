package app.amphora.gamesession

import app.amphora.core.engine.model.LaunchTarget
import app.amphora.core.engine.model.SessionHandle
import app.amphora.core.engine.model.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class GameSessionLaunchRequest(
    val exePath: String,
    val width: Int,
    val height: Int,
    val target: LaunchTarget,
    val graphicsDiag: Boolean,
)

/**
 * Owns deterministic session lifecycle decisions without depending on Android or a ViewModel.
 *
 * The caller supplies the lifecycle scope, blocking-action dispatcher, and launch boundary. This
 * keeps launch validation, pending actions, state forwarding, and teardown independently testable.
 */
internal class GameSessionCoordinator(
    private val scope: CoroutineScope,
    private val actionDispatcher: CoroutineDispatcher,
    private val launchSession: suspend (GameSessionLaunchRequest) -> SessionHandle,
) {
    private val mutableSessionState = MutableStateFlow<SessionState?>(null)
    val sessionState: StateFlow<SessionState?> = mutableSessionState.asStateFlow()

    private val mutableLaunchError = MutableStateFlow<String?>(null)
    val launchError: StateFlow<String?> = mutableLaunchError.asStateFlow()

    private val sessionActions = PendingSessionActions()

    fun start(request: GameSessionLaunchRequest) {
        if (request.target == LaunchTarget.PROGRAM && request.exePath.isEmpty()) {
            mutableLaunchError.value = "No game selected"
            mutableSessionState.value = SessionState.FAILED
            return
        }

        scope.launch {
            mutableSessionState.value = SessionState.STARTING
            try {
                val handle = launchSession(request)
                val pendingAction = sessionActions.attach(handle)
                launch { handle.state.collect { mutableSessionState.value = it } }
                withContext(actionDispatcher) {
                    when (pendingAction) {
                        PendingSessionAction.STOP -> handle.stop()
                        PendingSessionAction.PAUSE -> handle.pause()
                        PendingSessionAction.NONE -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                mutableLaunchError.value = e.message ?: e.javaClass.simpleName
                mutableSessionState.value = SessionState.FAILED
            }
        }
    }

    fun stop() {
        sessionActions.requestStop()?.let { handle ->
            scope.launch(actionDispatcher) { handle.stop() }
        }
    }

    fun resume() {
        sessionActions.requestResume()?.let { handle ->
            scope.launch(actionDispatcher) { handle.resume() }
        }
    }

    fun pause() {
        sessionActions.requestPause()?.let { handle ->
            scope.launch(actionDispatcher) { handle.pause() }
        }
    }

    fun clear(cleanupScope: CoroutineScope) {
        sessionActions.requestStop()?.let { handle ->
            cleanupScope.launch(actionDispatcher) { handle.stop() }
        }
    }
}

internal enum class PendingSessionAction {
    NONE,
    PAUSE,
    STOP,
}

/**
 * Records lifecycle requests before the session launch has returned its handle.
 *
 * Calls are synchronized because UI events race the launch coroutine. STOP is terminal and takes
 * precedence over a queued PAUSE.
 */
internal class PendingSessionActions {
    private var handle: SessionHandle? = null
    private var pausePending = false
    private var stopRequested = false

    @Synchronized
    fun attach(newHandle: SessionHandle): PendingSessionAction {
        handle = newHandle
        return when {
            stopRequested -> PendingSessionAction.STOP
            pausePending -> {
                pausePending = false
                PendingSessionAction.PAUSE
            }
            else -> PendingSessionAction.NONE
        }
    }

    @Synchronized
    fun requestStop(): SessionHandle? {
        stopRequested = true
        pausePending = false
        return handle
    }

    @Synchronized
    fun requestPause(): SessionHandle? {
        if (stopRequested) return null
        val current = handle
        if (current == null) pausePending = true
        return current
    }

    @Synchronized
    fun requestResume(): SessionHandle? {
        if (stopRequested) return null
        val current = handle
        if (current == null) pausePending = false
        return current
    }
}
