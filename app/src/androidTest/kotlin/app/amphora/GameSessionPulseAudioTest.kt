package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.core.container.model.ContainerId
import app.amphora.core.engine.AdvancedRuntimePreferences
import app.amphora.core.engine.GraphicsDriverIds
import app.amphora.core.engine.WineEngine
import app.amphora.core.engine.model.DisplaySize
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.SessionHandle
import app.amphora.core.engine.model.SessionState
import app.amphora.ui.DebugWineFixture
import com.winlator.cmod.runtime.display.connector.UnixSocketConfig
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.environment.components.PulseAudioRuntimeSupport
import com.winlator.cmod.runtime.system.ProcessHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device regression for the optional PulseAudio/AAudio path.
 *
 * The production Proton pin already ships `winepulse.so` (`DT_NEEDED=libpulse.so`).
 * This test turns the setting on, asserts the daemon and AAudio sink actually
 * come up, exercises pause/resume (the Java half of call-interrupt recovery),
 * then switches to ALSA and confirms that backend still launches.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class GameSessionPulseAudioTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var wineEngine: WineEngine

    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val prefs
        get() =
            appContext.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
    private var previousAudioDriver: String? = null

    @Before
    fun setUp() {
        hiltRule.inject()
        previousAudioDriver = prefs.getString(AdvancedRuntimePreferences.KEY_AUDIO_DRIVER, null)
    }

    @After
    fun restoreAudioDriver() {
        val editor = prefs.edit()
        if (previousAudioDriver == null) {
            editor.remove(AdvancedRuntimePreferences.KEY_AUDIO_DRIVER)
        } else {
            editor.putString(AdvancedRuntimePreferences.KEY_AUDIO_DRIVER, previousAudioDriver)
        }
        editor.commit()
    }

    @Test
    fun launch_pulseAudio_startsAAudioSinkThenAlsaStillWorks() = runBlocking {
        assumeTrue(
            "PulseAudio AAudio module is 4 KB-page only",
            PulseAudioRuntimeSupport.isSupportedPlatform(),
        )
        val winepulse = winepulseUnixLibrary()
        assumeTrue(
            "production Proton pin must include winepulse.so",
            winepulse != null && winepulse.isFile,
        )

        persistAudioDriver(AdvancedRuntimePreferences.AUDIO_DRIVER_PULSEAUDIO)
        launchAndStop("pulse") { handle ->
            assertPulseStack(handle)
            handle.pause()
            delay(1_000L)
            assertEquals(SessionState.PAUSED, handle.state.value)
            assertTrue(
                "Pulse daemon died while paused",
                pulseDaemonRunning(),
            )
            handle.resume()
            delay(1_000L)
            assertEquals(SessionState.RUNNING, handle.state.value)
            assertPulseStack(handle)
        }

        persistAudioDriver(AdvancedRuntimePreferences.AUDIO_DRIVER_ALSA)
        launchAndStop("alsa") { handle ->
            assertAlsaStack(handle)
        }
    }

    private suspend fun launchAndStop(label: String, body: suspend (SessionHandle) -> Unit) {
        val executable = DebugWineFixture.stage(appContext)
        val spec =
            LaunchSpec(
                exePath = executable.absolutePath,
                containerId = ContainerId("1"),
                displaySize = DisplaySize(1280, 720),
                env =
                mapOf(
                    "WINEDEBUG" to "+err",
                    "AMPHORA_EXEC_DEBUG" to "1",
                ),
            )
        println("PULSE_REGRESSION_$label START exe=${executable.name}")
        val handle = withTimeout(15 * 60_000L) { wineEngine.launch(spec) }
        try {
            val ready = withTimeoutOrNull(120_000L) { handle.awaitReady() }
            println(
                "PULSE_REGRESSION_$label RESULT state=${handle.state.value} awaitReady=${ready != null}",
            )
            assertTrue(
                "$label awaitReady timed out (state=${handle.state.value}); see logcat",
                ready != null,
            )
            delay(2_000L)
            assertEquals(SessionState.RUNNING, handle.state.value)
            body(handle)
        } finally {
            handle.stop()
            val remaining = ProcessHelper.listRunningWineProcessDetails()
            assertTrue(
                "$label teardown left Wine/Box64 processes: $remaining",
                remaining.isEmpty(),
            )
            delay(500L)
        }
    }

    private fun assertPulseStack(handle: SessionHandle) {
        assertEquals(SessionState.RUNNING, handle.state.value)
        val runtime = File(appContext.filesDir, "pulseaudio")
        assertTrue("pactl missing after Pulse launch", File(runtime, "pactl").isFile)
        assertTrue(
            "module-aaudio-sink.so was not installed",
            File(runtime, "modules/module-aaudio-sink.so").isFile,
        )
        assertTrue("Pulse server socket missing", pulseSocket().exists())
        assertTrue("Pulse daemon not running", pulseDaemonRunning())
        val sinks = pactl("list short sinks")
        println("PULSE_SINKS\n$sinks")
        assertTrue(
            "AAudioSink not listed by pactl:\n$sinks",
            sinks.contains("AAudioSink"),
        )
        assertEquals("pulseaudio", containerAudioDriver())
        assertEquals("pulse", wineRegistryAudioDriver())
    }

    private fun assertAlsaStack(handle: SessionHandle) {
        assertEquals(SessionState.RUNNING, handle.state.value)
        assertTrue("ALSA server socket missing", alsaSocket().exists())
        assertFalse("Pulse daemon leaked into ALSA session", pulseDaemonRunning())
        assertEquals("alsa", containerAudioDriver())
        assertEquals("alsa", wineRegistryAudioDriver())
    }

    private fun persistAudioDriver(value: String) {
        assertTrue(
            prefs.edit().putString(AdvancedRuntimePreferences.KEY_AUDIO_DRIVER, value).commit(),
        )
        assertEquals(value, AdvancedRuntimePreferences.audioDriver(appContext))
    }

    private fun winepulseUnixLibrary(): File? {
        val protonRoot = File(appContext.filesDir, "contents/Proton")
        return protonRoot.walkTopDown()
            .firstOrNull { it.name == "winepulse.so" && it.isFile }
    }

    private fun pulseSocket(): File =
        File(appContext.filesDir, "imagefs${UnixSocketConfig.PULSE_SERVER_PATH}")

    private fun alsaSocket(): File =
        File(appContext.filesDir, "imagefs${UnixSocketConfig.ALSA_SERVER_PATH}")

    private fun containerAudioDriver(): String {
        val container = File(appContext.filesDir, "imagefs/home/xuser-1/.container")
        val text = container.takeIf { it.isFile }?.readText().orEmpty()
        val match = Regex("\"audioDriver\"\\s*:\\s*\"([^\"]+)\"").find(text)
        return match?.groupValues?.get(1).orEmpty()
    }

    private fun wineRegistryAudioDriver(): String {
        val userReg =
            File(appContext.filesDir, "imagefs${ImageFs.WINEPREFIX}/user.reg")
        val text = userReg.takeIf { it.isFile }?.readText().orEmpty()
        val match = Regex("\"Audio\"=\"([^\"]+)\"").find(text)
        return match?.groupValues?.get(1).orEmpty()
    }

    private fun pulseDaemonRunning(): Boolean {
        val proc = File("/proc")
        val pids = proc.list { dir, name -> File(dir, name).isDirectory && name.matches(Regex("[0-9]+")) }
            ?: return false
        return pids.any { pid ->
            val cmdline = File("/proc/$pid/cmdline").takeIf { it.isFile }?.readBytes() ?: return@any false
            String(cmdline, Charsets.UTF_8).replace('\u0000', ' ').contains("libpulseaudio.so")
        }
    }

    private fun pactl(command: String): String {
        val workingDir = File(appContext.filesDir, "pulseaudio")
        val pactl = File(workingDir, "pactl")
        if (!pactl.isFile) return ""
        val requested = ArrayList<String>()
        requested.add(pactl.absolutePath)
        command.split(' ').filter { it.isNotEmpty() }.forEach(requested::add)
        val prepared = ProcessHelper.prepareCommandForAppData(requested.toTypedArray())
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val builder = ProcessBuilder(prepared.toList())
        builder.directory(workingDir)
        builder.redirectErrorStream(true)
        builder.environment()["LD_LIBRARY_PATH"] =
            "/system/lib64:$nativeDir:${File(workingDir, "modules").absolutePath}"
        builder.environment()["HOME"] = workingDir.absolutePath
        builder.environment()["PULSE_SERVER"] = pulseSocket().absolutePath
        builder.environment()["TMPDIR"] = File(appContext.filesDir, "imagefs/usr/tmp").absolutePath
        ProcessHelper.configureAppDataExecEnvironment(builder.environment(), pactl.absolutePath)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }
}
