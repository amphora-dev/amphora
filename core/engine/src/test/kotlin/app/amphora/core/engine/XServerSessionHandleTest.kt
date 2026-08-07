package app.amphora.core.engine

import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import app.amphora.core.engine.model.SessionState
import com.winlator.cmod.runtime.display.environment.XEnvironment
import com.winlator.cmod.runtime.display.xserver.XServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class XServerSessionHandleTest {
    @Test
    fun stopUsesBoundedProcessCleanupExactlyOnce() = runTest {
        val environment = mockk<XEnvironment>(relaxed = true)
        val xServer = mockk<XServer>(relaxed = true)
        val processCleaner = mockk<SessionProcessCleaner>()
        every { processCleaner.terminateAndWait(any()) } returns emptyList()
        val handle =
            XServerSessionHandle(
                environment,
                xServer,
                DefaultDispatcherProvider(),
                processCleaner,
            )
        handle.markRunning()

        handle.stop()
        handle.stop()

        verify(exactly = 1) { environment.stopEnvironmentComponents() }
        verify(exactly = 1) { processCleaner.terminateAndWait(2_000L) }
        verify(exactly = 1) { xServer.stop() }
        verifyOrder {
            processCleaner.terminateAndWait(2_000L)
            environment.stopEnvironmentComponents()
            xServer.stop()
        }
        assertEquals(SessionState.STOPPED, handle.state.value)
    }

    @Test
    fun guestExitRequestsCleanupBeforePublishingStopped() = runTest {
        val environment = mockk<XEnvironment>(relaxed = true)
        val xServer = mockk<XServer>(relaxed = true)
        val processCleaner = mockk<SessionProcessCleaner>()
        every { processCleaner.terminateAndWait(any()) } returns emptyList()
        val handle =
            XServerSessionHandle(
                environment,
                xServer,
                DefaultDispatcherProvider(),
                processCleaner,
            )
        handle.markRunning()

        handle.requestStop()
        withTimeout(5_000L) {
            handle.state.first { it == SessionState.STOPPED }
        }

        verifyOrder {
            processCleaner.terminateAndWait(2_000L)
            environment.stopEnvironmentComponents()
            xServer.stop()
        }
    }

    @Test
    fun failedLaunchKeepsFailedStateAfterTeardown() = runTest {
        val processCleaner = mockk<SessionProcessCleaner>()
        every { processCleaner.terminateAndWait(any()) } returns emptyList()
        val handle =
            XServerSessionHandle(
                mockk(relaxed = true),
                mockk(relaxed = true),
                DefaultDispatcherProvider(),
                processCleaner,
            )

        handle.markFailed(IllegalStateException("component failed"))
        handle.stop()

        assertEquals(SessionState.FAILED, handle.state.value)
        verify(exactly = 1) { processCleaner.terminateAndWait(2_000L) }
    }
}
