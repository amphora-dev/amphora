package app.amphora.gamesession

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.winlator.cmod.runtime.display.xserver.Window
import com.winlator.cmod.runtime.display.xserver.WindowManager
import com.winlator.cmod.runtime.display.xserver.XServer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * API-independent host performance sampler.
 *
 * FPS comes from X11 frame submissions, so it covers DXVK, VKD3D, Zink,
 * WineD3D and software blits without injecting a guest Vulkan layer.
 */
internal class HostPerformanceMonitor(context: Context, private val xServer: XServer) :
    WindowManager.OnWindowModificationListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val tracker = FrameTracker(xServer.screenInfo.width * xServer.screenInfo.height)
    private val _stats = MutableStateFlow(HostPerformanceStats())
    val stats: StateFlow<HostPerformanceStats> = _stats.asStateFlow()
    private var samplingJob: Job? = null
    private var previousCpuMs = Process.getElapsedCpuTime()
    private var previousWallMs = SystemClock.elapsedRealtime()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        xServer.windowManager.addOnWindowModificationListener(this)
        samplingJob =
            scope.launch {
                while (isActive) {
                    _stats.value = sample()
                    delay(SAMPLE_INTERVAL_MS)
                }
            }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        xServer.windowManager.removeOnWindowModificationListener(this)
        samplingJob?.cancel()
        scope.cancel()
    }

    override fun onFramePresented(window: Window, source: WindowManager.FrameSource, serial: Int) {
        tracker.record(window, source, serial)
    }

    private fun sample(): HostPerformanceStats {
        val nowWall = SystemClock.elapsedRealtime()
        val nowCpu = Process.getElapsedCpuTime()
        val elapsed = (nowWall - previousWallMs).coerceAtLeast(1)
        val cpuDelta = (nowCpu - previousCpuMs).coerceAtLeast(0)
        previousWallMs = nowWall
        previousCpuMs = nowCpu
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpuPercent = (cpuDelta * 100.0 / elapsed / cores).coerceIn(0.0, 100.0).roundToInt()

        val memoryInfo =
            ActivityManager.MemoryInfo().also {
                (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
            }
        val ramPercent =
            if (memoryInfo.totalMem > 0) {
                ((memoryInfo.totalMem - memoryInfo.availMem) * 100.0 / memoryInfo.totalMem)
                    .roundToInt()
                    .coerceIn(0, 100)
            } else {
                0
            }

        return HostPerformanceStats(
            fps = tracker.fps(),
            appCpuPercent = cpuPercent,
            gpuPercent = readGpuPercent(),
            ramPercent = ramPercent,
            appMemoryMb = Debug.getPss() / 1024,
            batteryTemperatureC = readBatteryTemperature(),
        )
    }

    private fun readGpuPercent(): Int? {
        for (path in GPU_LOAD_PATHS) {
            val raw =
                try {
                    File(path).takeIf { it.canRead() }?.readText()?.trim()
                } catch (_: Exception) {
                    null
                } ?: continue
            val values = Regex("\\d+").findAll(raw).mapNotNull { it.value.toLongOrNull() }.toList()
            val percent =
                when {
                    values.size >= 2 && values[1] > 0 -> values[0] * 100.0 / values[1]
                    values.isNotEmpty() -> values[0].toDouble()
                    else -> continue
                }
            return percent.roundToInt().coerceIn(0, 100)
        }
        return null
    }

    private fun readBatteryTemperature(): Float? {
        val battery =
            try {
                appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            } catch (_: Exception) {
                null
            } ?: return null
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    private class FrameTracker(private val screenArea: Int) {
        private val timestamps = LongArray(MAX_FRAME_SAMPLES)
        private var writeIndex = 0
        private var count = 0
        private var lastPrimaryNs = 0L
        private var lastPrimarySerial = Int.MIN_VALUE

        @Synchronized
        fun record(window: Window, source: WindowManager.FrameSource, serial: Int) {
            if (source == WindowManager.FrameSource.UNKNOWN ||
                source == WindowManager.FrameSource.DRI3_BUFFER
            ) {
                return
            }
            val windowArea = window.width.toInt() * window.height.toInt()
            if (windowArea < screenArea / MIN_WINDOW_AREA_DIVISOR) return

            val now = System.nanoTime()
            val primary = source == WindowManager.FrameSource.PRESENT
            if (primary) {
                if (serial != 0 && serial == lastPrimarySerial) return
                lastPrimarySerial = serial
                lastPrimaryNs = now
            } else if (now - lastPrimaryNs < FALLBACK_SUPPRESSION_NS) {
                return
            }
            timestamps[writeIndex] = now
            writeIndex = (writeIndex + 1) % timestamps.size
            if (count < timestamps.size) count++
        }

        @Synchronized
        fun fps(): Float {
            if (count < 2) return 0f
            val now = System.nanoTime()
            val newest = timestamps[(writeIndex - 1 + timestamps.size) % timestamps.size]
            if (now - newest > IDLE_TIMEOUT_NS) return 0f
            val cutoff = now - FPS_WINDOW_NS
            var samples = 0
            var oldest = newest
            for (offset in 0 until count) {
                val index = (writeIndex - 1 - offset + timestamps.size) % timestamps.size
                val timestamp = timestamps[index]
                if (timestamp < cutoff) break
                oldest = timestamp
                samples++
            }
            if (samples < 2 || newest <= oldest) return 0f
            return ((samples - 1) * 1_000_000_000.0 / (newest - oldest)).toFloat()
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 500L
        const val MAX_FRAME_SAMPLES = 512
        const val MIN_WINDOW_AREA_DIVISOR = 16
        const val FPS_WINDOW_NS = 1_000_000_000L
        const val IDLE_TIMEOUT_NS = 1_500_000_000L
        const val FALLBACK_SUPPRESSION_NS = 2_000_000_000L
        val GPU_LOAD_PATHS =
            listOf(
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                "/sys/class/misc/mali0/device/utilisation",
                "/sys/kernel/gpu/gpu_busy",
            )
    }
}

internal data class HostPerformanceStats(
    val fps: Float = 0f,
    val appCpuPercent: Int = 0,
    val gpuPercent: Int? = null,
    val ramPercent: Int = 0,
    val appMemoryMb: Int = 0,
    val batteryTemperatureC: Float? = null,
)
