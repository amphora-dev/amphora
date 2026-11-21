package app.amphora

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.amphora.sdk.AmphoraSdk
import app.amphora.sdk.engine.RuntimeState
import app.amphora.sdk.library.BuiltinTool
import app.amphora.sdk.session.LaunchTarget
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class RuntimeProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val started = SystemClock.elapsedRealtime()

    private fun record(message: String) {
        message.chunked(3000).forEach { Log.i("FTLProbe", "${SystemClock.elapsedRealtime() - started}ms $it") }
    }

    private fun shell(command: String) {
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        }.onSuccess { record("shell [$command]: $it") }.onFailure { record("shell [$command]: $it") }
    }

    private fun screenshot(name: String) {
        val directory = File("/sdcard/Download/amphora-probe").apply { mkdirs() }
        runCatching {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }.onFailure { record("screenshot: $it") }
    }

    @Test
    fun runtimeAndNotepad() = runBlocking {
        val context = instrumentation.targetContext
        val sdk = AmphoraSdk.get(context)
        val watcher = launch { sdk.sessions.state.collectLatest { record("session=$it") } }
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            record("device=${sdk.device()}")
            for (path in listOf("/dev/kgsl-3d0", "/sys/class/kgsl", "/sys/class/kgsl/kgsl-3d0/gpu_model")) {
                runCatching {
                    val stat = Os.stat(path)
                    record(
                        "app stat $path mode=${Integer.toOctalString(
                            stat.st_mode,
                        )} uid=${stat.st_uid} gid=${stat.st_gid}",
                    )
                    if (path == "/dev/kgsl-3d0") {
                        Os.close(Os.open(path, OsConstants.O_RDWR, 0))
                        record("app KGSL O_RDWR open succeeded")
                    }
                }.onFailure { record("app access $path: $it") }
            }
            shell("dumpsys SurfaceFlinger | grep -i GLES")
            shell("cmd gpu vkjson")
            shell("ls -ld /dev/kgsl-3d0 /sys/class/kgsl /sys/class/kgsl/kgsl-3d0")
            shell("run-as app.amphora id")
            shell(
                "run-as app.amphora sh -c 'cp /system/bin/echo files/ftl-echo; chmod 700 files/ftl-echo; files/ftl-echo PRIVATE_EXEC_OK; /system/bin/linker64 files/ftl-echo LINKER_EXEC_OK; rm files/ftl-echo'",
            )
            val executable = File(context.filesDir, "probe-echo")
            try {
                File("/system/bin/echo").copyTo(executable, overwrite = true)
                executable.setExecutable(true)
                for (args in listOf(
                    listOf(executable.path, "APP_EXEC_OK"),
                    listOf("/system/bin/linker64", executable.path, "APP_LINKER_OK"),
                )) {
                    runCatching {
                        val process = ProcessBuilder(args).redirectErrorStream(true).start()
                        check(process.waitFor(10, TimeUnit.SECONDS))
                        record(
                            "app exec $args exit=${process.exitValue()} ${process.inputStream.bufferedReader().readText()}",
                        )
                    }.onFailure { record("app exec $args: $it") }
                }
            } finally {
                executable.delete()
            }
            withTimeout(10_000) { sdk.runtime.state.first { it !is RuntimeState.Checking } }
            val progress = launch {
                while (true) {
                    record("runtime=${sdk.runtime.state.value}")
                    delay(5000)
                }
            }
            try {
                sdk.runtime.install()
                val result = withTimeout(480_000) {
                    sdk.runtime.state.first { it is RuntimeState.Ready || it is RuntimeState.Failed }
                }
                record("runtime result=$result")
                if (result is RuntimeState.Ready) {
                    record(
                        "launch=${withTimeout(180_000) {
                            sdk.sessions.launch(LaunchTarget.Tool(BuiltinTool.Notepad))
                        }}",
                    )
                    delay(60_000)
                    record("session after 60s=${sdk.sessions.state.value}")
                }
            } finally {
                progress.cancel()
            }
        } catch (error: Exception) {
            record("probe exception=${Log.getStackTraceString(error)}")
            throw error
        } finally {
            screenshot("notepad-or-failure")
            shell("ps -A -o USER,PID,PPID,NAME,ARGS | grep -E 'amphora|wine|notepad|fex'")
            sdk.dataDir.walkTopDown().filter { it.isFile && (it.extension == "log" || it.name == "current.json") }
                .forEach { record("file ${it.path}: ${it.readText().takeLast(24000)}") }
            watcher.cancel()
            sdk.sessions.end()
            record("PROBE_FINISHED")
        }
    }
}
