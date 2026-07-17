package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amphora.core.container.model.ContainerId
import app.amphora.core.engine.WineEngine
import app.amphora.core.engine.model.DisplaySize
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.SessionState
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * Real-device end-to-end launch verification (RFC §8): `WineEngine.launch` ->
 * rootfs -> container -> preparer -> `XEnvironment` -> `box64 wine explorer`.
 *
 * Replaces the manual SAF-pick + Launch UI flow with a single command:
 * ```
 * ./gradlew :app:stageBundledContent        # one-time: bundle real assets into the APK
 * ./gradlew :app:connectedDebugAndroidTest --tests "app.amphora.GameSessionLaunchTest"
 * ```
 * On failure the full exception stack surfaces in the test report -- the UI path
 * (`GameSessionViewModel`'s `Throwable` boundary) only keeps `message`, which hid
 * the root cause during hand testing.
 *
 * The test exe (`notepad.exe`, Wine's own PE from the Proton `.wcp`) is staged in
 * `androidTest/assets/` and copied to `filesDir/exe/` before launch (mirrors
 * `LauncherViewModel`). Real runtime assets (`imagefs.tzst`, the `.wcp`s, the
 * preparer `.tzst`s) must be bundled in the *app* APK via `stageBundledContent`.
 *
 * Device: Lenovo TB322FC, arm64-v8a, API 36, Adreno 830.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class GameSessionLaunchTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var wineEngine: WineEngine

    private val appCtx = ApplicationProvider.getApplicationContext<Context>()
    private val testCtx get() = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun launch_notepad_startsWineSession() = runBlocking {
        // Real assets must be bundled in the app APK (stageBundledContent).
        val topAssets = appCtx.assets.list("").orEmpty().toList()
        assumeTrue(
            "imagefs.tzst not bundled in app assets (have ${topAssets.size} entries: $topAssets); " +
                "run ./gradlew :app:stageBundledContent first.",
            "imagefs.tzst" in topAssets,
        )

        val exe = stageExe("notepad.exe")
        val spec = LaunchSpec(
            exePath = exe.absolutePath,
            containerId = ContainerId("1"),
            displaySize = DisplaySize(1280, 720),
        )

        println("LAUNCH_START exe=${exe.name} size=${exe.length()} container=${spec.containerId.value}")
        // On exec failure (e.g. SELinux execute_no_trans) this throws with the FULL
        // stack: GuestProgramLauncherComponent.start throws when pid == -1
        // (ProcessHelper.exec swallows pb.start() IOException), so WineEngineImpl.launch
        // -> markFailed propagates instead of silently marking RUNNING. On success this
        // returns a handle whose awaitReady reflects real component startup.
        val handle = wineEngine.launch(spec)
        try {
            // First run takes ~30-60s (rootfs extract + Proton/Box64 install + prefix);
            // allow 120s so slow devices/cold caches don't flake.
            val ready = withTimeoutOrNull(120_000) { handle.awaitReady() }
            println("LAUNCH_RESULT state=${handle.state.value} awaitReady=${ready != null}")
            assertTrue(
                "awaitReady timed out (state=${handle.state.value}); see logcat for box64/wine errors",
                ready != null,
            )
            // Liveness: give the guest a few seconds to boot. If box64/wine crashed
            // immediately, the termination callback fires markStopped() and state leaves
            // RUNNING -- the real "did the guest stay up" signal. This test does NOT
            // create an XServerSurfaceView, so there's no on-screen frame to capture here;
            // surface creation is covered by XServerSurfaceViewInitTest, visual e2e by the
            // UI path. awaitReady staying RUNNING proves box64 exec'd and is alive.
            kotlinx.coroutines.delay(5_000)
            println("LIVENESS_CHECK state=${handle.state.value}")
            assertTrue(
                "guest did not stay running (state=${handle.state.value}); see logcat for box64/wine crash",
                handle.state.value == SessionState.RUNNING,
            )
        } finally {
            handle.stop()
        }
    }

    /** Copy the test exe from androidTest/assets into filesDir/exe/<name> (mirrors LauncherViewModel). */
    private fun stageExe(name: String): File {
        val out = File(appCtx.filesDir, "exe/$name").apply { parentFile?.mkdirs() }
        if (!out.exists()) {
            testCtx.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out
    }
}
