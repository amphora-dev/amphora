package app.amphora.gamesession

import androidx.lifecycle.SavedStateHandle
import app.amphora.core.common.testing.MainDispatcherRule
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.engine.GameSessionSurface
import app.amphora.core.engine.GameSessionSurfaceProvider
import app.amphora.core.engine.WineEngine
import app.amphora.core.engine.model.AudioSink
import app.amphora.core.engine.model.DisplaySize
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.core.engine.model.SessionHandle
import app.amphora.core.engine.model.SessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameSessionViewModelTest {
    @get:Rule
    val dispatchers = MainDispatcherRule()

    @Test
    fun savedStateIsMappedToLaunchSpecWithoutTouchingDiagnostics() = runTest(dispatchers.testDispatcher) {
        val fixture =
            fixture(
                savedState =
                mapOf(
                    "exePath" to "/games/example.exe",
                    "width" to 1920,
                    "height" to 1080,
                    "target" to LaunchTarget.PROGRAM.name,
                    "graphicsDiag" to false,
                ),
            )

        runCurrent()

        val spec = slot<LaunchSpec>()
        coVerify(exactly = 1) { fixture.engine.launch(capture(spec)) }
        assertEquals("/games/example.exe", spec.captured.exePath)
        assertEquals(DisplaySize(1920, 1080), spec.captured.displaySize)
        assertEquals(LaunchTarget.PROGRAM, spec.captured.target)
        assertTrue(spec.captured.env.isEmpty())
        assertEquals(0, fixture.hostEnvironment.diagnosticRequests)
    }

    @Test
    fun graphicsDiagnosticsArePreparedOnceAndForwardedToEngine() = runTest(dispatchers.testDispatcher) {
        val diagnosticEnv = mapOf("DXVK_LOG_PATH" to "/logs", "VK_LOADER_DEBUG" to "all")
        val fixture =
            fixture(
                savedState =
                mapOf(
                    "exePath" to "",
                    "target" to LaunchTarget.EXPLORER.name,
                    "graphicsDiag" to true,
                ),
                diagnosticEnv = diagnosticEnv,
            )

        runCurrent()

        val spec = slot<LaunchSpec>()
        coVerify(exactly = 1) { fixture.engine.launch(capture(spec)) }
        assertEquals(LaunchTarget.EXPLORER, spec.captured.target)
        assertEquals(diagnosticEnv, spec.captured.env)
        assertEquals(1, fixture.hostEnvironment.diagnosticRequests)
    }

    @Test
    fun hostPerformancePreferenceIsReadThroughHostBoundary() = runTest(dispatchers.testDispatcher) {
        val disabled = fixture(hostPerformanceHudEnabled = false)
        val enabled = fixture(hostPerformanceHudEnabled = true)

        assertFalse(disabled.viewModel.hostPerformanceHudEnabled)
        assertTrue(enabled.viewModel.hostPerformanceHudEnabled)
    }

    @Test
    fun audioControlsAreForwardedToTheEngineSink() = runTest(dispatchers.testDispatcher) {
        val fixture = fixture()

        fixture.viewModel.setAudioVolume(0.4f)
        fixture.viewModel.setAudioMuted(true)
        runCurrent()

        coVerify(exactly = 1) { fixture.audioSink.setVolume(0.4f) }
        coVerify(exactly = 1) { fixture.audioSink.setMuted(true) }
    }

    private fun fixture(
        savedState: Map<String, Any?> =
            mapOf(
                "exePath" to "/games/example.exe",
                "target" to LaunchTarget.PROGRAM.name,
            ),
        diagnosticEnv: Map<String, String> = emptyMap(),
        hostPerformanceHudEnabled: Boolean = false,
    ): Fixture {
        val engine = mockk<WineEngine>()
        val handle = mockk<SessionHandle>(relaxed = true)
        val audioSink = mockk<AudioSink>(relaxed = true)
        every { engine.provisionProgress } returns MutableStateFlow<ProvisionProgress?>(null)
        every { engine.audioSink() } returns audioSink
        every { audioSink.volume } returns MutableStateFlow(1f)
        every { handle.state } returns MutableStateFlow(SessionState.RUNNING)
        coEvery { engine.launch(any()) } returns handle
        val surfaceProvider = mockk<GameSessionSurfaceProvider>()
        every { surfaceProvider.surface } returns MutableStateFlow<GameSessionSurface?>(null)
        val hostEnvironment =
            FakeGameSessionHostEnvironment(
                hostPerformanceHudEnabled = hostPerformanceHudEnabled,
                diagnosticEnv = diagnosticEnv,
            )
        val viewModel =
            GameSessionViewModel(
                wineEngine = engine,
                surfaceProvider = surfaceProvider,
                hostEnvironment = hostEnvironment,
                dispatchers = dispatchers,
                savedStateHandle = SavedStateHandle(savedState),
            )
        return Fixture(viewModel, engine, audioSink, hostEnvironment)
    }

    private data class Fixture(
        val viewModel: GameSessionViewModel,
        val engine: WineEngine,
        val audioSink: AudioSink,
        val hostEnvironment: FakeGameSessionHostEnvironment,
    )

    private class FakeGameSessionHostEnvironment(
        override val hostPerformanceHudEnabled: Boolean,
        private val diagnosticEnv: Map<String, String>,
    ) : GameSessionHostEnvironment {
        var diagnosticRequests = 0
            private set

        override fun prepareGraphicsDiagnostics(): Map<String, String> {
            diagnosticRequests += 1
            return diagnosticEnv
        }
    }
}
