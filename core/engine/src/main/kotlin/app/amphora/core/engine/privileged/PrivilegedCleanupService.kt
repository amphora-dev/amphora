package app.amphora.core.engine.privileged

import android.os.ParcelFileDescriptor
import android.util.Log
import app.amphora.core.engine.SessionProcessController
import java.io.FileInputStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * One-shot Shizuku user service.
 *
 * This runs as Shizuku's shell/root identity, outside Amphora's process. Its
 * only operation is an explicit emergency force-stop of Amphora itself; normal
 * Wine cleanup remains an own-UID operation in [SessionProcessController].
 */
class PrivilegedCleanupService : IPrivilegedCleanupService.Stub() {
    override fun scheduleForceStop(packageName: String, delayMillis: Int) {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name" }
        val delay = delayMillis.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        thread(name = "AmphoraEmergencyStop", isDaemon = false) {
            try {
                Thread.sleep(delay.toLong())
                val status =
                    ProcessBuilder("am", "force-stop", packageName)
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                Log.i(TAG, "am force-stop $packageName exited with status $status")
            } catch (error: Throwable) {
                Log.e(TAG, "Emergency force-stop failed", error)
            } finally {
                exitProcess(0)
            }
        }
    }

    override fun installPackage(apk: ParcelFileDescriptor, apkSize: Long, packageName: String): String {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name" }
        require(apkSize > 0L) { "APK size must be positive" }
        return try {
            val process =
                ProcessBuilder("pm", "install", "-r", "-S", apkSize.toString())
                    .redirectErrorStream(true)
                    .start()
            FileInputStream(apk.fileDescriptor).use { input ->
                process.outputStream.use { output -> input.copyTo(output) }
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val status = process.waitFor()
            val result = "status=$status ${output.ifEmpty { "<no output>" }}"
            Log.i(TAG, "Shizuku package install: $result")
            if (status == 0 && output.contains("Success", ignoreCase = true)) {
                relaunchAfterInstall(packageName)
            }
            result
        } catch (error: Throwable) {
            Log.e(TAG, "Shizuku package install failed", error)
            "error=${error.message ?: error.javaClass.simpleName}"
        } finally {
            apk.close()
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    private fun relaunchAfterInstall(packageName: String) {
        thread(name = "AmphoraUpdateRelaunch", isDaemon = false) {
            try {
                Thread.sleep(RELAUNCH_DELAY_MS)
                ProcessBuilder(
                    "monkey",
                    "-p",
                    packageName,
                    "-c",
                    "android.intent.category.LAUNCHER",
                    "1",
                ).start().waitFor()
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to relaunch updated app", error)
            } finally {
                exitProcess(0)
            }
        }
    }

    private companion object {
        const val TAG = "PrivilegedCleanup"
        const val MIN_DELAY_MS = 250
        const val MAX_DELAY_MS = 5_000
        const val RELAUNCH_DELAY_MS = 1_000L
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_.]*")
    }
}
