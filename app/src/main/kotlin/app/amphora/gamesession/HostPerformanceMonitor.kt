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
import app.amphora.core.engine.HostMetricPathDiscovery
import app.amphora.core.engine.PerformanceMetricsClient
import app.amphora.core.engine.PrivilegedPerformanceSnapshot
import com.winlator.cmod.runtime.display.renderer.VulkanRenderer
import com.winlator.cmod.runtime.display.xserver.Atom
import com.winlator.cmod.runtime.display.xserver.Window
import com.winlator.cmod.runtime.display.xserver.WindowManager
import com.winlator.cmod.runtime.display.xserver.XServer
import com.winlator.cmod.runtime.system.ProcessHelper
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
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
    // Constructor-injected so JVM tests can drive sampling on a test dispatcher.
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WindowManager.OnWindowModificationListener {
    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val privilegedReader = PerformanceMetricsClient(appContext)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
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
    private var thermalSources: List<HostMetricPathDiscovery.ThermalPath>? = null
    private var lastThermalDiscoveryMs = 0L
    private var detailStats = DetailStats()
    private var lastHostPssSampleMs = 0L
    private var cachedHostMemoryMb = 0
    private var lastThermalHeadroomSampleMs = 0L
    private var cachedThermalHeadroom: Float? = null
    private var lastSystemHeadroomSampleMs = 0L
    private var cachedCpuHeadroom: Float? = null
    private var cachedGpuHeadroom: Float? = null
    private var lastPrivilegedSampleMs = 0L
    private var lastPrivilegedSuccessMs = 0L
    private var privilegedSnapshot: PrivilegedPerformanceSnapshot? = null
    private val telemetryBindingLock = Any()
    private var telemetryRenderer: VulkanRenderer? = null
    private val retryAfterMs = mutableMapOf<String, Long>()
    private val gpuLoadSampler = GpuLoadSampler()
    private var gpuPaths = HostMetricPathDiscovery.discoverGpuPaths()
    private var lastGpuDiscoveryMs = SystemClock.elapsedRealtime()

    @Volatile private var detailsEnabled = false

    fun setDetailsEnabled(enabled: Boolean) {
        if (enabled && !detailsEnabled) {
            previousGuestCpuTicks = emptyMap()
            previousDetailWallMs = 0L
        }
        detailsEnabled = enabled
        lastDetailSampleMs = 0L
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        xServer.windowManager.addOnWindowModificationListener(this)
        updateRendererTelemetryBinding()
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
        updateRendererTelemetryBinding()
        xServer.windowManager.removeOnWindowModificationListener(this)
        samplingJob?.cancel()
        privilegedReader.close()
        scope.cancel()
    }

    override fun onFramePresented(window: Window, source: WindowManager.FrameSource, serial: Int) {
        tracker.record(window, source, serial)
    }

    private fun sample(): HostPerformanceStats {
        updateRendererTelemetryBinding()
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
        if (nowWall - lastPrivilegedSampleMs >= PRIVILEGED_SAMPLE_INTERVAL_MS) {
            privilegedReader.read()?.let {
                privilegedSnapshot = it
                lastPrivilegedSuccessMs = nowWall
            }
            lastPrivilegedSampleMs = nowWall
        }
        if (lastPrivilegedSuccessMs > 0L &&
            nowWall - lastPrivilegedSuccessMs >= PRIVILEGED_STALE_TIMEOUT_MS
        ) {
            privilegedSnapshot = null
        }
        val compositorFps =
            rendererTelemetry?.let { telemetry ->
                telemetry.compositorPresentFps
                    .finiteFloat()
                    ?.takeIf { telemetry.compositorPresentSampleCount > 1 }
            }
        val compositorIntervalMs =
            rendererTelemetry?.let { telemetry ->
                telemetry.compositorPresentIntervalMs
                    .finiteFloat()
                    ?.takeIf { telemetry.compositorPresentSampleCount > 1 }
            }
        val actualDisplayFps =
            rendererTelemetry?.let { telemetry ->
                telemetry.displayFps
                    .finiteFloat()
                    ?.takeIf { telemetry.displaySampleCount > 1 }
            }
        val actualPresentIntervalMs =
            rendererTelemetry?.let { telemetry ->
                telemetry.presentIntervalMs
                    .finiteFloat()
                    ?.takeIf { telemetry.displaySampleCount > 1 }
            }
        val localGpuLoad = readFirstMetric(gpuPaths.load)
        if (localGpuLoad == null && nowWall - lastGpuDiscoveryMs >= DISCOVERY_RETRY_INTERVAL_MS) {
            gpuPaths = HostMetricPathDiscovery.discoverGpuPaths()
            lastGpuDiscoveryMs = nowWall
        }
        val gpuLoadPath = localGpuLoad?.path ?: privilegedSnapshot?.gpuLoadPath
        val gpuLoadRaw = localGpuLoad?.value ?: privilegedSnapshot?.gpuLoad
        val gpuPercent =
            if (gpuLoadPath != null && gpuLoadRaw != null) {
                gpuLoadSampler.sample(gpuLoadPath, gpuLoadRaw, nowWall)
            } else {
                null
            }
        if (nowWall - lastHostPssSampleMs >= PSS_SAMPLE_INTERVAL_MS) {
            cachedHostMemoryMb = (Debug.getPss() / 1024).toInt()
            lastHostPssSampleMs = nowWall
        }

        if (nowWall - lastDetailSampleMs >= DETAIL_SAMPLE_INTERVAL_MS) {
            detailStats = sampleDetails(nowWall, includeGuest = detailsEnabled)
            lastDetailSampleMs = nowWall
        }

        return HostPerformanceStats(
            fps = compositorFps ?: frames.fps,
            frameTimeP95Ms =
            if (compositorFps != null) {
                rendererTelemetry?.compositorFrameP95Ms?.finiteFloat()
            } else {
                frames.p95FrameTimeMs
            },
            onePercentLowFps =
            if (compositorFps != null) {
                rendererTelemetry?.compositorOnePercentLowFps?.finiteFloat()
            } else {
                frames.onePercentLowFps
            },
            compositorGpuMs = rendererTelemetry?.gpuRenderMs?.finiteFloat(),
            displayFps = actualDisplayFps ?: compositorFps,
            presentIntervalMs = actualPresentIntervalMs ?: compositorIntervalMs,
            presentMarginMs = rendererTelemetry?.presentMarginMs?.finiteFloat(),
            refreshCycleMs = rendererTelemetry?.refreshCycleMs?.finiteFloat(),
            gpuTimingSupported = rendererTelemetry?.gpuTimingSupported == true,
            displayTimingSupported = actualDisplayFps != null || compositorFps != null,
            displayTimingSource =
            when {
                actualDisplayFps != null -> DisplayTimingSource.ACTUAL
                compositorFps != null -> DisplayTimingSource.COMPOSITOR
                else -> DisplayTimingSource.UNAVAILABLE
            },
            hostCpuPercent = hostCpuPercent,
            systemCpuPercent = detailStats.systemCpuPercent,
            guestCpuPercent = detailStats.guestCpuPercent,
            cpuCores = detailStats.cpuCores,
            gpuPercent = gpuPercent,
            gpuMetricsAccess =
            when {
                localGpuLoad != null -> MetricsAccess.APP
                privilegedSnapshot?.gpuLoad != null ->
                    if (privilegedSnapshot?.hasRootAccess == true) {
                        MetricsAccess.SHIZUKU_ROOT
                    } else {
                        MetricsAccess.SHIZUKU_SHELL
                    }
                else -> MetricsAccess.RESTRICTED
            },
            systemMetricsAccess = detailStats.systemMetricsAccess,
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

    private fun updateRendererTelemetryBinding() {
        synchronized(telemetryBindingLock) {
            val current = xServer.renderer.takeIf { started.get() }
            if (current === telemetryRenderer) return
            telemetryRenderer?.setPerformanceTelemetryEnabled(false)
            current?.setPerformanceTelemetryEnabled(true)
            telemetryRenderer = current
        }
    }

    private fun sampleDetails(nowWall: Long, includeGuest: Boolean): DetailStats {
        val localCpuStat = readText(PROC_STAT_PATH)
        val currentCpuTimes =
            HostPerformanceParser.parseCpuTimes(
                localCpuStat ?: privilegedSnapshot?.cpuStat,
            )
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
                    currentMhz =
                    readCpuFrequency(index, maximum = false)
                        ?: privilegedSnapshot
                            ?.cpuCurrentFrequencies
                            ?.get(index)
                            ?.let(HostPerformanceParser::parseFrequencyMhz),
                    maxMhz =
                    readCpuFrequency(index, maximum = true)
                        ?: privilegedSnapshot
                            ?.cpuMaxFrequencies
                            ?.get(index)
                            ?.let(HostPerformanceParser::parseFrequencyMhz),
                )
            }
        previousCpuTimes = currentCpuTimes

        val guestPids =
            if (includeGuest) {
                collectGuestProcesses(Process.myPid(), guestProcessId.value)
            } else {
                emptyList()
            }
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
        val guestCpuPercent =
            if (includeGuest) {
                val elapsedDetailMs = nowWall - previousDetailWallMs
                val tickDelta =
                    currentGuestTicks.entries.sumOf { (pid, ticks) ->
                        (ticks - (previousGuestCpuTicks[pid] ?: ticks)).coerceAtLeast(0)
                    }
                val result =
                    if (previousDetailWallMs > 0 && elapsedDetailMs > 0) {
                        (tickDelta * 100_000.0 / clockTicksPerSecond / elapsedDetailMs / coreCount)
                            .roundToInt()
                            .coerceIn(0, 100)
                    } else {
                        null
                    }
                previousGuestCpuTicks = currentGuestTicks
                previousDetailWallMs = nowWall
                result
            } else {
                null
            }
        if (nowWall - lastThermalHeadroomSampleMs >= THERMAL_HEADROOM_INTERVAL_MS) {
            cachedThermalHeadroom =
                runCatching { powerManager.getThermalHeadroom(THERMAL_FORECAST_SECONDS) }
                    .getOrNull()
                    ?.takeUnless(Float::isNaN)
            lastThermalHeadroomSampleMs = nowWall
        }
        if (Build.VERSION.SDK_INT >= 36) updateSystemHeadroom(nowWall)
        val localGpuCurrentFrequency = readFirstMetric(gpuPaths.currentFrequency)
        val gpuCurrentFrequencyPath =
            localGpuCurrentFrequency?.path ?: privilegedSnapshot?.gpuCurrentFrequencyPath
        val gpuCurrentFrequencyRaw =
            localGpuCurrentFrequency?.value ?: privilegedSnapshot?.gpuCurrentFrequency
        val localGpuMaxFrequency = readFirstMetric(gpuPaths.maxFrequency)
        val gpuMaxFrequencyRaw = localGpuMaxFrequency?.value ?: privilegedSnapshot?.gpuMaxFrequency

        return DetailStats(
            systemCpuPercent = systemCpuPercent,
            systemMetricsAccess =
            when {
                localCpuStat != null -> MetricsAccess.APP
                privilegedSnapshot?.cpuStat != null ->
                    if (privilegedSnapshot?.hasRootAccess == true) {
                        MetricsAccess.SHIZUKU_ROOT
                    } else {
                        MetricsAccess.SHIZUKU_SHELL
                    }
                else -> MetricsAccess.RESTRICTED
            },
            guestCpuPercent = guestCpuPercent,
            cpuCores = cpuCores,
            gpuCurrentMhz =
            if (gpuCurrentFrequencyPath != null && gpuCurrentFrequencyRaw != null) {
                HostPerformanceParser.parseFrequencyMhz(
                    gpuCurrentFrequencyPath,
                    gpuCurrentFrequencyRaw,
                )
            } else {
                null
            },
            gpuMaxMhz =
            gpuMaxFrequencyRaw?.let(HostPerformanceParser::parseMaxFrequencyMhz),
            guestMemoryMb = (guestMemoryKb / 1024).toInt(),
            guestProcessCount = guestPids.size,
            guestThreadCount = guestThreads,
            socTemperatureC =
            readSocTemperature()
                ?: HostPerformanceParser.parseTemperatureC(privilegedSnapshot?.socTemperature),
            thermalStatus = runCatching { powerManager.currentThermalStatus }.getOrNull(),
            thermalHeadroom = cachedThermalHeadroom,
            cpuHeadroom = cachedCpuHeadroom,
            gpuHeadroom = cachedGpuHeadroom,
            detectedBackend =
            detectWindowBackend()
                ?: if (includeGuest) detectGuestBackend(guestPids) else detailStats.detectedBackend,
        )
    }

    /**
     * Wine's exec interceptor reparents processes to the Android session host, so the launcher PID
     * is only one sibling rather than the root of the whole guest tree. Walk every descendant of
     * this :session process, then retain ProcessHelper's Wine/Box64 process set.
     */
    private fun collectGuestProcesses(sessionHostPid: Int, launcherPid: Int?): List<Int> {
        val pending = ArrayDeque<Int>()
        val descendants = linkedSetOf<Int>()
        readChildPids(sessionHostPid).forEach(pending::addLast)
        if (pending.isEmpty() && launcherPid != null && launcherPid > 0) {
            pending.add(launcherPid)
        }
        while (pending.isNotEmpty() && descendants.size < MAX_GUEST_PROCESSES) {
            val pid = pending.removeFirst()
            if (!descendants.add(pid)) continue
            readChildPids(pid).forEach(pending::addLast)
        }
        val winePids =
            ProcessHelper.listRunningWineProcesses()
                .mapNotNull(String::toIntOrNull)
                .toSet()
        return HostPerformanceParser.selectSessionGuestPids(descendants, winePids, launcherPid)
    }

    private fun readChildPids(pid: Int): List<Int> = File("/proc/$pid/task")
        .listFiles { file -> file.isDirectory && file.name.all(Char::isDigit) }
        .orEmpty()
        .flatMap { task ->
            HostPerformanceParser.parseChildPids(readText(File(task, "children").path))
        }
        .distinct()

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
        val frameworkVoltageMv =
            intent
                ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                ?.takeIf { it > 0 }
        val frameworkCurrentUa =
            runCatching { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
                .getOrNull()
                ?.takeIf { it != Int.MIN_VALUE && it != 0 }
        val voltageMv =
            frameworkVoltageMv
                ?: readFirstMetric(BATTERY_VOLTAGE_PATHS)
                    ?.value
                    ?.trim()
                    ?.toLongOrNull()
                    ?.let { abs(it) / 1_000L }
                    ?.takeIf { it in 1_000L..20_000L }
                    ?.toInt()
        val currentUa =
            frameworkCurrentUa
                ?: readFirstMetric(BATTERY_CURRENT_PATHS)
                    ?.value
                    ?.trim()
                    ?.toLongOrNull()
                    ?.let(::abs)
                    ?.takeIf { it > 0L }
        val powerW =
            if (voltageMv != null && currentUa != null) {
                abs(currentUa.toDouble()) * voltageMv / 1_000_000_000.0
            } else {
                readFirstMetric(BATTERY_POWER_PATHS)
                    ?.value
                    ?.trim()
                    ?.toLongOrNull()
                    ?.let { abs(it.toDouble()) / 1_000_000.0 }
            }
        return BatteryStats(level, temperature, powerW?.takeIf { it in 0.0..100.0 }?.toFloat())
    }

    private fun readSocTemperature(): Float? {
        val now = SystemClock.elapsedRealtime()
        val sources =
            thermalSources
                ?.takeIf { it.isNotEmpty() || now - lastThermalDiscoveryMs < DISCOVERY_RETRY_INTERVAL_MS }
                ?: discoverThermalSources().also {
                    thermalSources = it
                    lastThermalDiscoveryMs = now
                }
        return sources
            .mapNotNull { HostPerformanceParser.parseTemperatureC(readText(it.path)) }
            .maxOrNull()
    }

    private fun discoverThermalSources(): List<HostMetricPathDiscovery.ThermalPath> =
        HostMetricPathDiscovery.discoverThermalPaths()

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

    private fun readFirstMetric(paths: List<String>): MetricReading? =
        paths.firstNotNullOfOrNull { path -> readText(path)?.let { MetricReading(path, it) } }

    private fun readText(path: String): String? = try {
        val now = SystemClock.elapsedRealtime()
        if ((retryAfterMs[path] ?: 0L) > now) return null
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            if (path == PROC_STAT_PATH || path.startsWith("/sys/")) {
                retryAfterMs[path] = now + PATH_RETRY_INTERVAL_MS
            }
            return null
        }
        file
            .readText()
            .trim()
            .also { retryAfterMs.remove(path) }
    } catch (_: Exception) {
        if (path == PROC_STAT_PATH || path.startsWith("/sys/")) {
            retryAfterMs[path] = SystemClock.elapsedRealtime() + PATH_RETRY_INTERVAL_MS
        }
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
        val systemMetricsAccess: MetricsAccess = MetricsAccess.RESTRICTED,
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

    private data class MetricReading(val path: String, val value: String)

    private companion object {
        const val SAMPLE_INTERVAL_MS = 500L
        const val DETAIL_SAMPLE_INTERVAL_MS = 1_000L
        const val PRIVILEGED_SAMPLE_INTERVAL_MS = 1_000L
        const val PRIVILEGED_STALE_TIMEOUT_MS = 5_000L
        const val PSS_SAMPLE_INTERVAL_MS = 5_000L
        const val PATH_RETRY_INTERVAL_MS = 30_000L
        const val DISCOVERY_RETRY_INTERVAL_MS = 60_000L
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
        val BATTERY_CURRENT_PATHS =
            listOf(
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/bms/current_now",
                "/sys/class/power_supply/main/current_now",
            )
        val BATTERY_VOLTAGE_PATHS =
            listOf(
                "/sys/class/power_supply/battery/voltage_now",
                "/sys/class/power_supply/bms/voltage_now",
                "/sys/class/power_supply/main/voltage_now",
            )
        val BATTERY_POWER_PATHS =
            listOf(
                "/sys/class/power_supply/battery/power_now",
                "/sys/class/power_supply/bms/power_now",
                "/sys/class/power_supply/main/power_now",
            )
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
    val displayTimingSource: DisplayTimingSource = DisplayTimingSource.UNAVAILABLE,
    val hostCpuPercent: Int = 0,
    val systemCpuPercent: Int? = null,
    val guestCpuPercent: Int? = null,
    val cpuCores: List<CpuCoreStats> = emptyList(),
    val gpuPercent: Int? = null,
    val gpuCurrentMhz: Int? = null,
    val gpuMaxMhz: Int? = null,
    val gpuMetricsAccess: MetricsAccess = MetricsAccess.RESTRICTED,
    val systemMetricsAccess: MetricsAccess = MetricsAccess.RESTRICTED,
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

internal enum class DisplayTimingSource {
    ACTUAL,
    COMPOSITOR,
    UNAVAILABLE,
}

internal enum class MetricsAccess {
    APP,
    SHIZUKU_SHELL,
    SHIZUKU_ROOT,
    RESTRICTED,
}

private fun Long.bytesToMb(): Int = (this / (1024L * 1024L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private fun Double.finiteFloat(): Float? = takeIf { it.isFinite() && it >= 0.0 }?.toFloat()
