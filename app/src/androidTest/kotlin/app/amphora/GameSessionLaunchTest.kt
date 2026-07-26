package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.core.container.model.ContainerId
import app.amphora.core.engine.WineEngine
import app.amphora.core.engine.model.DisplaySize
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.SessionState
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import javax.inject.Inject

/**
 * Real-device end-to-end launch verification (RFC §8): `WineEngine.launch` ->
 * rootfs -> container -> preparer -> `XEnvironment` -> `box64 wine explorer`.
 *
 * Replaces the manual SAF-pick + Launch UI flow with a single command:
 * ```
 * ./gradlew :app:connectedDebugAndroidTest --tests "app.amphora.GameSessionLaunchTest"
 * ```
 * On failure the full exception stack surfaces in the test report -- the UI path
 * (`GameSessionViewModel`'s `Throwable` boundary) only keeps `message`, which hid
 * the root cause during hand testing.
 *
 * A tiny x86-64 PE liveness fixture is decoded into `filesDir/exe/` before launch
 * (mirrors `LauncherViewModel`). Keeping it inline avoids relying on ignored,
 * machine-local `.exe` assets. Runtime assets are downloaded, SHA-verified and
 * installed on first run; subsequent runs exercise the no-network installed fast
 * path.
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

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun launch_notepad_startsWineSession() = runBlocking {
        val exe = stageExe()
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
        // A cold device downloads roughly 450 MB before launch. Bound the whole
        // provisioning phase without imposing the short warm-cache timeout.
        val handle = withTimeout(15 * 60_000L) { wineEngine.launch(spec) }
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

    /** Decode a freestanding PE that stays alive until Wine is stopped by the test. */
    private fun stageExe(): File {
        val out = File(appCtx.filesDir, "exe/wine-liveness.exe").apply { parentFile?.mkdirs() }
        if (!out.exists()) {
            out.writeBytes(Base64.getDecoder().decode(LIVENESS_EXE_BASE64))
        }
        return out
    }

    private companion object {
        // Built from: void mainCRTStartup(void) { for (;;) { __asm__ volatile("pause"); } }
        // clang --target=x86_64-pc-windows-msvc -c -nostdlib fixture.c
        // ld -mi386pep --entry mainCRTStartup --subsystem console fixture.obj
        // strip --strip-all wine-liveness.exe
        const val LIVENESS_EXE_BASE64 =
            "TVqQAAMAAAAEAAAA//8AALgAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAA4fug4AtAnNIbgBTM0hVGhpcyBwcm9ncmFtIGNhbm5vdCBiZSBydW4gaW4gRE9TIG1vZGUuDQ0KJAAAAAAAAABQRQAAZIYCAEFyZWoAAAAAAAAAAPAALwILAgIqAAIAAAACAAAAAAAAABAAAAAQAAAAAABAAQAAAAAQAAAAAgAABAAAAAAAAAAFAAIAAAAAAAAwAAAAAgAAWpAAAAMAAAEAACAAAAAAAAAQAAAAAAAAAAAQAAAAAAAAEAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAIAAAGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAudGV4dAAAADAAAAAAEAAAAAIAAAACAAAAAAAAAAAAAAAAAAAgAABgLmlkYXRhAAAYAAAAACAAAAACAAAABAAAAAAAAAAAAAAAAAAAQAAAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADpAAAAAPOQ6fn///8PH0AA//////////8AAAAAAAAAAP//////////AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
