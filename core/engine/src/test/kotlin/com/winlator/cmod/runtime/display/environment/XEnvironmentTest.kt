package com.winlator.cmod.runtime.display.environment

import android.content.Context
import com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertSame
import org.junit.Test

class XEnvironmentTest {
    @Test
    fun componentFailureRollsBackServicesAndDoesNotStartLauncher() {
        val files = Files.createTempDirectory("xenvironment-").toFile()
        try {
            val context = mockk<Context>()
            every { context.filesDir } returns files
            val first = mockk<EnvironmentComponent>(relaxed = true)
            val failing = mockk<EnvironmentComponent>(relaxed = true)
            val launcher = mockk<GuestProgramLauncherComponent>(relaxed = true)
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            every { first.start() } answers {
                firstStarted.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
            val failure = IllegalStateException("socket startup failed")
            every { failing.start() } answers {
                firstStarted.await(5, TimeUnit.SECONDS)
                releaseFirst.countDown()
                throw failure
            }
            val environment = XEnvironment(context, mockk(relaxed = true))
            environment.addComponent(first)
            environment.addComponent(failing)
            environment.addComponent(launcher)

            val thrown =
                try {
                    environment.startEnvironmentComponents()
                    null
                } catch (error: IllegalStateException) {
                    error
                }

            assertSame(failure, thrown)
            verify(exactly = 0) { launcher.start() }
            verifyOrder {
                failing.stop()
                first.stop()
            }
        } finally {
            files.deleteRecursively()
        }
    }
}
