package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.core.container.model.ContainerId
import app.amphora.core.engine.WineEngine
import app.amphora.core.engine.model.DisplaySize
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.SessionState
import app.amphora.ui.DebugWineFixture
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * Real-device verification of remote provisioning and a live Wine session.
 *
 * The launcher and this test share [DebugWineFixture], so the visible debug
 * button exercises the same executable as this automated path.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class GameSessionLaunchTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var wineEngine: WineEngine

    private val appContext = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun launch_smokeFixture_startsWineSession() = runBlocking {
        val executable = DebugWineFixture.stage(appContext)
        val spec = LaunchSpec(
            exePath = executable.absolutePath,
            containerId = ContainerId("1"),
            displaySize = DisplaySize(1280, 720),
        )

        println(
            "LAUNCH_START exe=${executable.name} size=${executable.length()} " +
                "container=${spec.containerId.value}",
        )
        val handle = withTimeout(15 * 60_000L) { wineEngine.launch(spec) }
        try {
            val ready = withTimeoutOrNull(120_000L) { handle.awaitReady() }
            println("LAUNCH_RESULT state=${handle.state.value} awaitReady=${ready != null}")
            assertTrue(
                "awaitReady timed out (state=${handle.state.value}); see logcat",
                ready != null,
            )
            val graphicsTestDir = File(
                appContext.filesDir,
                "imagefs/home/xuser-1/.wine/drive_c/ProgramData/Microsoft/Windows",
            )
            assertTrue(
                "32-bit AIO Graphics Test was not staged",
                File(graphicsTestDir, "Graphics-Test-32bit.exe").length() == 2_344_186L,
            )
            assertTrue(
                "64-bit AIO Graphics Test was not staged",
                File(graphicsTestDir, "Graphics-Test-64bit.exe").length() == 2_372_292L,
            )

            delay(5_000L)
            println("LIVENESS_CHECK state=${handle.state.value}")
            assertTrue(
                "guest did not stay running (state=${handle.state.value}); see logcat",
                handle.state.value == SessionState.RUNNING,
            )
        } finally {
            handle.stop()
        }
    }
}
