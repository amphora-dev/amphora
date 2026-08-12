package app.amphora.gamesession

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.CpuHeadroomParams
import android.os.Debug
import android.os.GpuHeadroomParams
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.health.SystemHealthManager
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.winlator.cmod.runtime.display.xserver.Atom
import com.winlator.cmod.runtime.display.xserver.Window
import com.winlator.cmod.runtime.display.xserver.WindowManager
import com.winlator.cmod.runtime.display.xserver.XServer
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
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
 * Host-side performance sampler shared by every guest graphics API.
 *
 * The compact HUD keeps the original low-cost 500 ms sample. Per-core CPU, frequencies, process
 * RSS/maps and thermal-zone reads run only while details are expanded.
 */
internal class HostPerformanceMonitor(
    context: Context,
    private val xServer: XServer,
    private val configuredBackend: String,
    private val guestProcessId: StateFlow<Int?>,
) : WindowManager.OnWindowModificationListener {
    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val tracker = FrameTracker(xServer.screenInfo.width * xServer.screenInfo.height)
    private val _stats = MutableStateFlow(HostPerformanceStats(configuredBackend = configuredBackend))
    val stats: StateFlow<HostPerformanceStats> = _stats.asStateFlow()
    private var samplingJob: Job? = null
    private var previousHostCpuMs = Process.getElapsedCpuTime()
    private var previousWallMs = SystemClock.elapsedRealtime()
    private var previousCpuTimes: Map<Int, CpuTimes> = emptyMap()
    private var previousGuestCpuTicks: Map<Int, Long> = emptyMap()
    private var previousDetailWallMs = 0L
    private var lastDetailSampleMs = 0L
    private var thermalSources: List<ThermalSource>? = null
    private var detailStats = DetailStats()
    private var lastHostPssSampleMs = 0L
    private var cachedHostMemoryMb = 0
    private var lastThermalHeadroomSampleMs = 0L
    private var cachedThermalHeadroom: Float? = null
    private var lastSystemHeadroomSampleMs = 0L
    private var cachedCpuHeadroom: Float? = null
    private var cachedGpuHeadroom: Float? = null

    @Volatile private var detailsEnabled = false

    fun setDetailsEnabled(enabled: Boolean) {
        if (enabled && !detailsEnabled) {
            previousCpuTimes = emptyMap()
            previousGuestCpuTicks = emptyMap()
            previousDetailWallMs = 0L
        }
        detailsEnabled = enabled
        if (enabled) lastDetailSampleMs = 0L
    }

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
        val nowHostCpu = Process.getElapsedCpuTime()
        val elapsed = (nowWall - previousWallMs).coerceAtLeast(1)
        val hostCpuDelta = (nowHostCpu - previousHostCpuMs).coerceAtLeast(0)
        previousWallMs = nowWall
        previousHostCpuMs = nowHostCpu
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val hostCpuPercent =
            (hostCpuDelta * 100.0 / elapsed / cores).coerceIn(0.0, 100.0).roundToInt()

        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val ramPercent =
            if (memoryInfo.totalMem > 0) {
                ((memoryInfo.totalMem - memoryInfo.availMem) * 100.0 / memoryInfo.totalMem)
                    .roundToInt()
                    .coerceIn(0, 100)
            } else {
                0
            }
        val battery = readBattery()
        val frames = tracker.metrics()
        val rendererTelemetry = xServer.renderer?.performanceTelemetry
        if (nowWall - lastHostPssSampleMs >= PSS_SAMPLE_INTERVAL_MS) {
            cachedHostMemoryMb = (Debug.getPss() / 1024).toInt()
            lastHostPssSampleMs = nowWall
        }

        if (detailsEnabled && nowWall - lastDetailSampleMs >= DETAIL_SAMPLE_INTERVAL_MS) {
            detailStats = sampleDetails(nowWall)
            lastDetailSampleMs = nowWall
        } else if (!detailsEnabled) {
            detailStats = DetailStats()
        }

        return HostPerformanceStats(
            fps = frames.fps,
            frameTimeP95Ms = frames.p95FrameTimeMs,
            onePercentLowFps = frames.onePercentLowFps,
            compositorGpuMs = rendererTelemetry?.gpuRenderMs?.finiteFloat(),
            displayFps = rendererTelemetry?.displayFps?.finiteFloat(),
            presentIntervalMs = rendererTelemetry?.presentIntervalMs?.finiteFloat(),
            presentMarginMs = rendererTelemetry?.presentMarginMs?.finiteFloat(),
            refreshCycleMs = rendererTelemetry?.refreshCycleMs?.finiteFloat(),
            gpuTimingSupported = rendererTelemetry?.gpuTimingSupported == true,
            displayTimingSupported = rendererTelemetry?.displayTimingSupported == true,
            hostCpuPercent = hostCpuPercent,
            systemCpuPercent = detailStats.systemCpuPercent,
            guestCpuPercent = detailStats.guestCpuPercent,
            cpuCores = detailStats.cpuCores,
            gpuPercent = readFirst(GPU_LOAD_PATHS)?.let(HostPerformanceParser::parseGpuPercent),
            gpuCurrentMhz = detailStats.gpuCurrentMhz,
            gpuMaxMhz = detailStats.gpuMaxMhz,
            ramPercent = ramPercent,
            availableMemoryMb = memoryInfo.availMem.bytesToMb(),
            totalMemoryMb = memoryInfo.totalMem.bytesToMb(),
            hostMemoryMb = cachedHostMemoryMb,
            guestMemoryMb = detailStats.guestMemoryMb,
            guestProcessCount = detailStats.guestProcessCount,
            guestThreadCount = detailStats.guestThreadCount,
            batteryLevelPercent = battery.levelPercent,
            batteryTemperatureC = battery.temperatureC,
            batteryPowerW = battery.powerW,
            socTemperatureC = detailStats.socTemperatureC,
            thermalStatus = detailStats.thermalStatus,
            thermalHeadroom = detailStats.thermalHeadroom,
            cpuHeadroom = detailStats.cpuHeadroom,
            gpuHeadroom = detailStats.gpuHeadroom,
            configuredBackend = configuredBackend,
            detectedBackend = detailStats.detectedBackend,
        )
    }

    private fun sampleDetails(nowWall: Long): DetailStats {
        val currentCpuTimes =
            HostPerformanceParser.parseCpuTimes(readText(PROC_STAT_PATH))
        val systemCpuPercent =
            HostPerformanceParser.usagePercent(previousCpuTimes[-1], currentCpuTimes[-1])
        val coreCount =
            maxOf(
                Runtime.getRuntime().availableProcessors(),
                (currentCpuTimes.keys.filter { it >= 0 }.maxOrNull() ?: -1) + 1,
            )
        val cpuCores =
            (0 until coreCount).map { index ->
                CpuCoreStats(
                    index = index,
                    usagePercent =
                    HostPerformanceParser.usagePercent(
                        previousCpuTimes[index],
                        currentCpuTimes[index],
                    ),
                    currentMhz = readCpuFrequency(index, maximum = false),
                    maxMhz = readCpuFrequency(index, maximum = true),
                )
            }
        previousCpuTimes = currentCpuTimes

        val guestPids = collectGuestProcessTree(guestProcessId.value)
        var guestMemoryKb = 0L
        var guestThreads = 0
        val currentGuestTicks = mutableMapOf<Int, Long>()
        guestPids.forEach { pid ->
            val status =
                HostPerformanceParser.parseProcessStatus(readText("/proc/$pid/status"))
            guestMemoryKb += status.residentMemoryKb
            guestThreads += status.threads
            HostPerformanceParser.parseProcessCpuTicks(readText("/proc/$pid/stat"))
                ?.let { currentGuestTicks[pid] = it }
        }
        val elapsedDetailMs = nowWall - previousDetailWallMs
        val tickDelta =
            currentGuestTicks.entries.sumOf { (pid, ticks) ->
                (ticks - (previousGuestCpuTicks[pid] ?: ticks)).coerceAtLeast(0)
            }
        val guestCpuPercent =
            if (previousDetailWallMs > 0 && elapsedDetailMs > 0) {
                (tickDelta * 100_000.0 / clockTicksPerSecond / elapsedDetailMs / coreCount)
                    .roundToInt()
                    .coerceIn(0, 100)
            } else {
                null
            }
        previousGuestCpuTicks = currentGuestTicks
        previousDetailWallMs = nowWall
        if (nowWall - lastThermalHeadroomSampleMs >= THERMAL_HEADROOM_INTERVAL_MS) {
            cachedThermalHeadroom =
                runCatching { powerManager.getThermalHeadroom(THERMAL_FORECAST_SECONDS) }
                    .getOrNull()
                    ?.takeUnless(Float::isNaN)
            lastThermalHeadroomSampleMs = nowWall
        }
        if (Build.VERSION.SDK_INT >= 36) updateSystemHeadroom(nowWall)

        return DetailStats(
            systemCpuPercent = systemCpuPercent,
            guestCpuPercent = guestCpuPercent,
            cpuCores = cpuCores,
            gpuCurrentMhz =
            readFirst(GPU_CURRENT_FREQUENCY_PATHS)
                ?.let(HostPerformanceParser::parseFrequencyMhz),
            gpuMaxMhz =
            readFirst(GPU_MAX_FREQUENCY_PATHS)
                ?.let(HostPerformanceParser::parseMaxFrequencyMhz),
            guestMemoryMb = (guestMemoryKb / 1024).toInt(),
            guestProcessCount = guestPids.size,
            guestThreadCount = guestThreads,
            socTemperatureC = readSocTemperature(),
            thermalStatus = runCatching { powerManager.currentThermalStatus }.getOrNull(),
            thermalHeadroom = cachedThermalHeadroom,
            cpuHeadroom = cachedCpuHeadroom,
            gpuHeadroom = cachedGpuHeadroom,
            detectedBackend = detectWindowBackend() ?: detectGuestBackend(guestPids),
        )
    }

    private fun collectGuestProcessTree(rootPid: Int?): List<Int> {
        if (rootPid == null || rootPid <= 0) return emptyList()
        val pending = ArrayDeque<Int>()
        val processes = linkedSetOf<Int>()
        pending.add(rootPid)
        while (pending.isNotEmpty() && processes.size < MAX_GUEST_PROCESSES) {
            val pid = pending.removeFirst()
            if (!processes.add(pid)) continue
            HostPerformanceParser.parseChildPids(
                readText("/proc/$pid/task/$pid/children"),
            )
                .forEach(pending::addLast)
        }
        return processes.toList()
    }

    @RequiresApi(36)
    private fun updateSystemHeadroom(nowWall: Long) {
        val manager =
            appContext.getSystemService(SystemHealthManager::class.java) ?: return
        val minimumInterval =
            runCatching {
                maxOf(
                    manager.cpuHeadroomMinIntervalMillis,
                    manager.gpuHeadroomMinIntervalMillis,
                    MIN_SYSTEM_HEADROOM_INTERVAL_MS,
                )
            }.getOrDefault(MIN_SYSTEM_HEADROOM_INTERVAL_MS)
        if (nowWall - lastSystemHeadroomSampleMs < minimumInterval) return
        cachedCpuHeadroom =
            runCatching {
                manager.getCpuHeadroom(CpuHeadroomParams.Builder().build())
            }.getOrNull()?.takeUnless(Float::isNaN)
        cachedGpuHeadroom =
            runCatching {
                manager.getGpuHeadroom(GpuHeadroomParams.Builder().build())
            }.getOrNull()?.takeUnless(Float::isNaN)
        lastSystemHeadroomSampleMs = nowWall
    }

    private fun readCpuFrequency(index: Int, maximum: Boolean): Int? {
        val names =
            if (maximum) {
                listOf("scaling_max_freq", "cpuinfo_max_freq")
            } else {
                listOf("scaling_cur_freq", "cpuinfo_cur_freq")
            }
        for (name in names) {
            HostPerformanceParser.parseFrequencyMhz(
                readText("/sys/devices/system/cpu/cpu$index/cpufreq/$name"),
            )?.let { return it }
        }
        return null
    }

    private fun readBattery(): BatteryStats {
        val intent =
            runCatching {
                appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
        val temperature =
            intent
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.div(10f)
        val level =
            intent
                ?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                ?.takeIf { it >= 0 }
        val voltageMv =
            intent
                ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                ?.takeIf { it > 0 }
        val currentUa =
            runCatching { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
                .getOrNull()
                ?.takeIf { it != Int.MIN_VALUE && it != 0 }
        val powerW =
            if (voltageMv != null && currentUa != null) {
                abs(currentUa.toDouble()) * voltageMv / 1_000_000_000.0
            } else {
                null
            }
        return BatteryStats(level, temperature, powerW?.toFloat())
    }

    private fun readSocTemperature(): Float? {
        val sources = thermalSources ?: discoverThermalSources().also { thermalSources = it }
        return sources.mapNotNull { HostPerformanceParser.parseTemperatureC(readText(it.path)) }.maxOrNull()
    }

    private fun discoverThermalSources(): List<ThermalSource> = File(THERMAL_ROOT)
        .listFiles { file -> file.isDirectory && file.name.startsWith("thermal_zone") }
        .orEmpty()
        .mapNotNull { zone ->
            val type = readText(File(zone, "type").path)?.lowercase() ?: return@mapNotNull null
            if (THERMAL_TYPE_HINTS.none { it in type }) return@mapNotNull null
            ThermalSource(File(zone, "temp").path)
        }

    private fun detectWindowBackend(): String? {
        val values =
            xServer.lock(XServer.Lockable.WINDOW_MANAGER).use {
                xServer.windowManager.windows
                    .asSequence()
                    .filter { it.attributes.isMapped }
                    .flatMap { window ->
                        rendererAtomIds.asSequence().mapNotNull { atom ->
                            window.getProperty(atom)?.toString()?.takeIf(String::isNotBlank)
                        }
                    }.map(String::lowercase)
                    .toList()
            }
        val detected = linkedSetOf<String>()
        values.forEach { value ->
            if ("vkd3d" in value) detected += "VKD3D"
            if ("dxvk" in value) detected += "DXVK"
            if ("wined3d" in value) detected += "WineD3D"
            if ("zink" in value) detected += "Zink"
            if ("turnip" in value) detected += "Turnip"
        }
        return detected.takeIf { it.isNotEmpty() }?.joinToString(" + ")
    }

    private fun detectGuestBackend(pids: List<Int>): String? {
        val detected = linkedSetOf<String>()
        pids.forEach { pid ->
            runCatching {
                File("/proc/$pid/maps").useLines { lines ->
                    lines.take(MAX_MAP_LINES).forEach { line ->
                        val mapping = line.lowercase()
                        if ("vkd3d" in mapping) detected += "VKD3D"
                        if ("dxvk" in mapping) detected += "DXVK"
                        if ("d8vk" in mapping) detected += "D8VK"
                        if ("wined3d" in mapping) detected += "WineD3D"
                    }
                }
            }
        }
        return detected.takeIf { it.isNotEmpty() }?.joinToString(" + ")
    }

    private fun readFirst(paths: List<String>): String? = paths.firstNotNullOfOrNull(::readText)

    private fun readText(path: String): String? = try {
        File(path).takeIf { it.isFile && it.canRead() }?.readText()?.trim()
    } catch (_: Exception) {
        null
    }

    private val clockTicksPerSecond: Long by lazy {
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
            .getOrDefault(DEFAULT_CLOCK_TICKS)
            .coerceAtLeast(1L)
    }
    private val rendererAtomIds: List<Int> by lazy {
        listOf(
            Atom.getId("_MESA_DRV_ENGINE_NAME"),
            Atom.getId("_MESA_DRV_RENDERER"),
            Atom.getId("_UTIL_LAYER"),
        )
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
        fun metrics(): FrameMetrics {
            if (count < 2) return FrameMetrics()
            val now = System.nanoTime()
            val newest = timestamps[(writeIndex - 1 + timestamps.size) % timestamps.size]
            if (now - newest > IDLE_TIMEOUT_NS) return FrameMetrics()
            val recent =
                buildList {
                    for (offset in 0 until count) {
                        val index = (writeIndex - 1 - offset + timestamps.size) % timestamps.size
                        val timestamp = timestamps[index]
                        if (now - timestamp > FRAME_METRIC_WINDOW_NS) break
                        add(timestamp)
                    }
                }.asReversed()
            if (recent.size < 2) return FrameMetrics()

            val fpsSamples = recent.filter { newest - it <= FPS_WINDOW_NS }
            val fps =
                if (fpsSamples.size >= 2) {
                    (
                        (fpsSamples.size - 1) * 1_000_000_000.0 /
                            (fpsSamples.last() - fpsSamples.first())
                        ).toFloat()
                } else {
                    0f
                }
            val intervalsMs =
                recent.zipWithNext { first, second -> (second - first) / 1_000_000f }.sorted()
            if (intervalsMs.size < MIN_PERCENTILE_SAMPLES) return FrameMetrics(fps = fps)
            val p95 = percentile(intervalsMs, 0.95f)
            val p99 = percentile(intervalsMs, 0.99f)
            return FrameMetrics(
                fps = fps,
                p95FrameTimeMs = p95,
                onePercentLowFps = if (p99 > 0f) 1_000f / p99 else null,
            )
        }

        private fun percentile(sorted: List<Float>, percentile: Float): Float {
            val index = ((sorted.lastIndex) * percentile).roundToInt().coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
    }

    private data class DetailStats(
        val systemCpuPercent: Int? = null,
        val guestCpuPercent: Int? = null,
        val cpuCores: List<CpuCoreStats> = emptyList(),
        val gpuCurrentMhz: Int? = null,
        val gpuMaxMhz: Int? = null,
        val guestMemoryMb: Int = 0,
        val guestProcessCount: Int = 0,
        val guestThreadCount: Int = 0,
        val socTemperatureC: Float? = null,
        val thermalStatus: Int? = null,
        val thermalHeadroom: Float? = null,
        val cpuHeadroom: Float? = null,
        val gpuHeadroom: Float? = null,
        val detectedBackend: String? = null,
    )

    private data class FrameMetrics(
        val fps: Float = 0f,
        val p95FrameTimeMs: Float? = null,
        val onePercentLowFps: Float? = null,
    )

    private data class BatteryStats(val levelPercent: Int?, val temperatureC: Float?, val powerW: Float?)

    private data class ThermalSource(val path: String)

    private companion object {
        const val SAMPLE_INTERVAL_MS = 500L
        const val DETAIL_SAMPLE_INTERVAL_MS = 1_000L
        const val PSS_SAMPLE_INTERVAL_MS = 5_000L
        const val THERMAL_HEADROOM_INTERVAL_MS = 10_000L
        const val THERMAL_FORECAST_SECONDS = 10
        const val MIN_SYSTEM_HEADROOM_INTERVAL_MS = 2_000L
        const val MAX_FRAME_SAMPLES = 512
        const val MIN_PERCENTILE_SAMPLES = 8
        const val MIN_WINDOW_AREA_DIVISOR = 16
        const val FPS_WINDOW_NS = 1_000_000_000L
        const val FRAME_METRIC_WINDOW_NS = 3_000_000_000L
        const val IDLE_TIMEOUT_NS = 1_500_000_000L
        const val FALLBACK_SUPPRESSION_NS = 2_000_000_000L
        const val MAX_GUEST_PROCESSES = 32
        const val MAX_MAP_LINES = 8_192
        const val DEFAULT_CLOCK_TICKS = 100L
        const val PROC_STAT_PATH = "/proc/stat"
        const val THERMAL_ROOT = "/sys/class/thermal"

        val GPU_LOAD_PATHS =
            listOf(
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                "/sys/class/misc/mali0/device/utilisation",
                "/sys/kernel/gpu/gpu_busy",
            )
        val GPU_CURRENT_FREQUENCY_PATHS =
            listOf(
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/devfreq/gpufreq/cur_freq",
                "/sys/class/misc/mali0/device/devfreq/cur_freq",
            )
        val GPU_MAX_FREQUENCY_PATHS =
            listOf(
                "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
                "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies",
                "/sys/class/devfreq/gpufreq/max_freq",
                "/sys/class/misc/mali0/device/devfreq/max_freq",
            )
        val THERMAL_TYPE_HINTS = listOf("soc", "cpu", "gpu", "ap", "skin")
    }
}

internal data class CpuCoreStats(
    val index: Int,
    val usagePercent: Int? = null,
    val currentMhz: Int? = null,
    val maxMhz: Int? = null,
)

internal data class HostPerformanceStats(
    val fps: Float = 0f,
    val frameTimeP95Ms: Float? = null,
    val onePercentLowFps: Float? = null,
    val compositorGpuMs: Float? = null,
    val displayFps: Float? = null,
    val presentIntervalMs: Float? = null,
    val presentMarginMs: Float? = null,
    val refreshCycleMs: Float? = null,
    val gpuTimingSupported: Boolean = false,
    val displayTimingSupported: Boolean = false,
    val hostCpuPercent: Int = 0,
    val systemCpuPercent: Int? = null,
    val guestCpuPercent: Int? = null,
    val cpuCores: List<CpuCoreStats> = emptyList(),
    val gpuPercent: Int? = null,
    val gpuCurrentMhz: Int? = null,
    val gpuMaxMhz: Int? = null,
    val ramPercent: Int = 0,
    val availableMemoryMb: Int = 0,
    val totalMemoryMb: Int = 0,
    val hostMemoryMb: Int = 0,
    val guestMemoryMb: Int = 0,
    val guestProcessCount: Int = 0,
    val guestThreadCount: Int = 0,
    val batteryLevelPercent: Int? = null,
    val batteryTemperatureC: Float? = null,
    val batteryPowerW: Float? = null,
    val socTemperatureC: Float? = null,
    val thermalStatus: Int? = null,
    val thermalHeadroom: Float? = null,
    val cpuHeadroom: Float? = null,
    val gpuHeadroom: Float? = null,
    val configuredBackend: String = "WineD3D / auto",
    val detectedBackend: String? = null,
)

private fun Long.bytesToMb(): Int = (this / (1024L * 1024L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private fun Double.finiteFloat(): Float? = takeIf { it.isFinite() && it >= 0.0 }?.toFloat()
