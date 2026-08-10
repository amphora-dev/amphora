package app.amphora.core.engine

import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import app.amphora.core.engine.model.SessionState
import com.winlator.cmod.runtime.display.environment.XEnvironment
import com.winlator.cmod.runtime.display.xserver.XServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XServerSessionHandleTest {
    @Test
    fun stopUsesBoundedProcessCleanupExactlyOnce() = runBlocking {
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
            environment.stopEnvironmentComponents()
            processCleaner.terminateAndWait(2_000L)
            xServer.stop()
        }
        assertEquals(SessionState.STOPPED, handle.state.value)
    }

    @Test
    fun guestExitRequestsCleanupBeforePublishingStopped() = runBlocking {
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
            environment.stopEnvironmentComponents()
            processCleaner.terminateAndWait(2_000L)
            xServer.stop()
        }
    }

    @Test
    fun guestExitPublishesStoppingBeforeProcessCleanupCompletes() = runBlocking {
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val processCleaner = mockk<SessionProcessCleaner>()
        every { processCleaner.terminateAndWait(any()) } answers {
            cleanupStarted.countDown()
            releaseCleanup.await(5, TimeUnit.SECONDS)
            emptyList()
        }
        val handle =
            XServerSessionHandle(
                mockk(relaxed = true),
                mockk(relaxed = true),
                DefaultDispatcherProvider(),
                processCleaner,
            )
        handle.markRunning()

        handle.requestStop()

        assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
        assertEquals(SessionState.STOPPING, handle.state.value)
        releaseCleanup.countDown()
        withTimeout(5_000L) {
            handle.state.first { it == SessionState.STOPPED }
        }
    }

    @Test
    fun failedLaunchKeepsFailedStateAfterTeardown() = runBlocking {
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
