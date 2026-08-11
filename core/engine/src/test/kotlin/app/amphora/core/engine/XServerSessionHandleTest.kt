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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XServerSessionHandleTest {
    @Test
    fun stopUsesBoundedProcessCleanupExactlyOnce(): Unit = runBlocking {
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
        handle.markStarting()
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
    fun guestExitRequestsCleanupBeforePublishingStopped(): Unit = runBlocking {
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
        handle.markStarting()
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
    fun guestExitPublishesStoppingBeforeProcessCleanupCompletes(): Unit = runBlocking {
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
        handle.markStarting()
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
    fun failedLaunchKeepsFailedStateAfterTeardown(): Unit = runBlocking {
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

    @Test
    fun markRunningCannotReviveStoppedSession(): Unit = runBlocking {
        val processCleaner = mockk<SessionProcessCleaner>()
        every { processCleaner.terminateAndWait(any()) } returns emptyList()
        val handle =
            XServerSessionHandle(
                mockk(relaxed = true),
                mockk(relaxed = true),
                DefaultDispatcherProvider(),
                processCleaner,
            )
        handle.markStarting()

        handle.stop()
        handle.markRunning()

        assertEquals(SessionState.STOPPED, handle.state.value)
    }

    @Test
    fun markFailedCompletesReadinessWithOriginalCause(): Unit = runBlocking {
        val expected = IllegalStateException("startup failed")
        val handle =
            XServerSessionHandle(
                mockk(relaxed = true),
                mockk(relaxed = true),
                DefaultDispatcherProvider(),
                mockk(relaxed = true),
            )

        handle.markStarting()
        handle.markFailed(expected)

        val actual =
            try {
                handle.awaitReady()
                AssertionError("Expected readiness to fail")
            } catch (failure: Throwable) {
                failure
            }
        assertSame(expected, actual)
        assertEquals(SessionState.FAILED, handle.state.value)
    }

    @Test
    fun stopBeforeRunningReleasesReadinessWithoutResurrection(): Unit = runBlocking {
        val processCleaner = mockk<SessionProcessCleaner>()
        every { processCleaner.terminateAndWait(any()) } returns emptyList()
        val handle =
            XServerSessionHandle(
                mockk(relaxed = true),
                mockk(relaxed = true),
                DefaultDispatcherProvider(),
                processCleaner,
            )
        handle.markStarting()

        handle.stop()
        handle.awaitReady()
        handle.markRunning()

        assertEquals(SessionState.STOPPED, handle.state.value)
    }

    @Test
    fun teardownExceptionsStillPublishStoppedAndInvokeCallback(): Unit = runBlocking {
        val environment = mockk<XEnvironment>()
        val processCleaner = mockk<SessionProcessCleaner>()
        val xServer = mockk<XServer>()
        every { environment.stopEnvironmentComponents() } throws IllegalStateException("component stop")
        every { processCleaner.terminateAndWait(any()) } throws IllegalStateException("process stop")
        every { xServer.stop() } throws IllegalStateException("xserver stop")
        var stoppedCallbacks = 0
        val handle =
            XServerSessionHandle(
                environment,
                xServer,
                DefaultDispatcherProvider(),
                processCleaner,
                onStopped = { stoppedCallbacks++ },
            )
        handle.markStarting()
        handle.markRunning()

        handle.stop()

        assertEquals(1, stoppedCallbacks)
        assertEquals(SessionState.STOPPED, handle.state.value)
    }
}
