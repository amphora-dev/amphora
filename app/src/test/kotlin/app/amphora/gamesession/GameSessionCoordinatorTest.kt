package app.amphora.gamesession

import app.amphora.core.engine.model.LaunchTarget
import app.amphora.core.engine.model.SessionHandle
import app.amphora.core.engine.model.SessionState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameSessionCoordinatorTest {
    @Test
    fun emptyProgramPathFailsWithoutCallingLaunchBoundary() = runTest {
        var launchCount = 0
        val coordinator =
            coordinator {
                launchCount += 1
                sessionHandle()
            }

        coordinator.start(REQUEST.copy(exePath = ""))

        assertEquals(SessionState.FAILED, coordinator.sessionState.value)
        assertEquals("No game selected", coordinator.launchError.value)
        assertEquals(0, launchCount)
    }

    @Test
    fun emptyNonProgramTargetIsLaunched() = runTest {
        var launchedRequest: GameSessionLaunchRequest? = null
        val handle = sessionHandle()
        val coordinator =
            coordinator {
                launchedRequest = it
                handle
            }
        val request = REQUEST.copy(exePath = "", target = LaunchTarget.EXPLORER)

        coordinator.start(request)
        runCurrent()

        assertEquals(request, launchedRequest)
        assertNull(coordinator.launchError.value)
    }

    @Test
    fun launchExceptionIsPublishedAsFailure() = runTest {
        val coordinator =
            coordinator {
                throw IllegalStateException("launch exploded")
            }

        coordinator.start(REQUEST)
        runCurrent()

        assertEquals(SessionState.FAILED, coordinator.sessionState.value)
        assertEquals("launch exploded", coordinator.launchError.value)
    }

    @Test
    fun stopWhileLaunchIsPendingStopsHandleImmediatelyAfterAttach() = runTest {
        val pendingHandle = CompletableDeferred<SessionHandle>()
        val handle = sessionHandle()
        val coordinator = coordinator { pendingHandle.await() }

        coordinator.start(REQUEST)
        runCurrent()
        assertEquals(SessionState.STARTING, coordinator.sessionState.value)

        coordinator.stop()
        coVerify(exactly = 0) { handle.stop() }

        pendingHandle.complete(handle)
        runCurrent()

        coVerify(exactly = 1) { handle.stop() }
    }

    @Test
    fun pauseWhileLaunchIsPendingIsDeliveredAfterAttach() = runTest {
        val pendingHandle = CompletableDeferred<SessionHandle>()
        val handle = sessionHandle()
        val coordinator = coordinator { pendingHandle.await() }

        coordinator.start(REQUEST)
        runCurrent()
        coordinator.pause()
        pendingHandle.complete(handle)
        runCurrent()

        coVerify(exactly = 1) { handle.pause() }
    }

    @Test
    fun resumeWhileLaunchIsPendingCancelsQueuedPause() = runTest {
        val pendingHandle = CompletableDeferred<SessionHandle>()
        val handle = sessionHandle()
        val coordinator = coordinator { pendingHandle.await() }

        coordinator.start(REQUEST)
        runCurrent()
        coordinator.pause()
        coordinator.resume()
        pendingHandle.complete(handle)
        runCurrent()

        coVerify(exactly = 0) { handle.pause() }
        coVerify(exactly = 0) { handle.resume() }
    }

    @Test
    fun attachedHandleReceivesActionsAndForwardsTerminalState() = runTest {
        val state = MutableStateFlow(SessionState.RUNNING)
        val handle = sessionHandle(state)
        val coordinator = coordinator { handle }

        coordinator.start(REQUEST)
        runCurrent()
        assertEquals(SessionState.RUNNING, coordinator.sessionState.value)

        coordinator.pause()
        runCurrent()
        coordinator.resume()
        runCurrent()

        state.value = SessionState.STOPPED
        runCurrent()

        assertEquals(SessionState.STOPPED, coordinator.sessionState.value)
        coVerify(exactly = 1) { handle.pause() }
        coVerify(exactly = 1) { handle.resume() }
    }

    @Test
    fun clearStopsAnAttachedHandleOnCleanupScope() = runTest {
        val handle = sessionHandle()
        val coordinator = coordinator { handle }

        coordinator.start(REQUEST)
        runCurrent()
        coordinator.clear(backgroundScope)
        runCurrent()

        coVerify(exactly = 1) { handle.stop() }
    }

    private fun TestScope.coordinator(launchSession: suspend (GameSessionLaunchRequest) -> SessionHandle) =
        GameSessionCoordinator(
            scope = backgroundScope,
            actionDispatcher = StandardTestDispatcher(testScheduler),
            launchSession = launchSession,
        )

    private fun sessionHandle(
        state: MutableStateFlow<SessionState> = MutableStateFlow(SessionState.CREATED),
    ): SessionHandle = mockk<SessionHandle>().also { handle ->
        every { handle.state } returns state
        coEvery { handle.pause() } just Runs
        coEvery { handle.resume() } just Runs
        coEvery { handle.stop() } just Runs
    }

    private companion object {
        val REQUEST =
            GameSessionLaunchRequest(
                exePath = "/games/example.exe",
                width = 1280,
                height = 720,
                target = LaunchTarget.PROGRAM,
                graphicsDiag = false,
            )
    }
}
