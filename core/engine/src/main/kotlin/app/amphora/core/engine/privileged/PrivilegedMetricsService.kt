package app.amphora.core.engine.privileged

import android.os.Process
import android.os.SystemClock
import app.amphora.core.engine.HostMetricPathDiscovery
import java.io.File
import org.json.JSONObject

/**
 * Read-only Shizuku user service for optional host metrics.
 *
 * The service exposes one fixed snapshot rather than an arbitrary path or shell command. Shizuku
 * commonly runs as shell (UID 2000), which can read /proc/stat but may still be denied vendor GPU
 * sysfs nodes; rooted Shizuku can provide both.
 */
class PrivilegedMetricsService : IPrivilegedMetricsService.Stub() {
    private val retryAfterMs = mutableMapOf<String, Long>()
    private var gpuPaths = HostMetricPathDiscovery.discoverGpuPaths()
    private var lastGpuDiscoveryMs = SystemClock.elapsedRealtime()

    override fun readPerformanceSnapshot(): String {
        val result = JSONObject()
        result.put("uid", Process.myUid())
        readMetric("/proc/stat")?.let { result.put("cpuStat", it.value) }

        val gpuLoad = readFirst(gpuPaths.load)
        val gpuCurrentFrequency = readFirst(gpuPaths.currentFrequency)
        val gpuMaxFrequency = readFirst(gpuPaths.maxFrequency)
        if (gpuLoad == null && gpuCurrentFrequency == null && gpuMaxFrequency == null) {
            refreshGpuPathsIfDue()
        }
        result.putMetric("gpuLoad", gpuLoad)
        result.putMetric("gpuCurrentFrequency", gpuCurrentFrequency)
        result.putMetric("gpuMaxFrequency", gpuMaxFrequency)
        result.put("cpuCurrentFrequencies", readCpuFrequencies(maximum = false))
        result.put("cpuMaxFrequencies", readCpuFrequencies(maximum = true))

        readSocTemperature()?.let { reading ->
            result.put("socTemperature", reading.value)
            result.put("socTemperaturePath", reading.path)
        }
        return result.toString()
    }

    private fun readFirst(paths: List<String>): MetricReading? =
        paths.firstNotNullOfOrNull(::readMetric)

    private fun readMetric(path: String): MetricReading? = try {
        val now = SystemClock.elapsedRealtime()
        if ((retryAfterMs[path] ?: 0L) > now) return null
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            retryAfterMs[path] = now + PATH_RETRY_INTERVAL_MS
            return null
        }
        file
            .readText()
            .trim()
            .takeIf(String::isNotEmpty)
            ?.take(MAX_METRIC_LENGTH)
            ?.let { MetricReading(path, it) }
            .also { if (it != null) retryAfterMs.remove(path) }
    } catch (_: Exception) {
        retryAfterMs[path] = SystemClock.elapsedRealtime() + PATH_RETRY_INTERVAL_MS
        null
    }

    private fun refreshGpuPathsIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGpuDiscoveryMs < DISCOVERY_RETRY_INTERVAL_MS) return
        gpuPaths = HostMetricPathDiscovery.discoverGpuPaths()
        lastGpuDiscoveryMs = now
    }

    private fun readCpuFrequencies(maximum: Boolean): JSONObject {
        val result = JSONObject()
        val cpuRoot = File("/sys/devices/system/cpu")
        cpuRoot
            .listFiles { file -> file.isDirectory && CPU_DIRECTORY.matches(file.name) }
            .orEmpty()
            .sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
            .forEach { cpu ->
                val index = cpu.name.removePrefix("cpu").toIntOrNull() ?: return@forEach
                val names =
                    if (maximum) {
                        listOf("scaling_max_freq", "cpuinfo_max_freq")
                    } else {
                        listOf("scaling_cur_freq", "cpuinfo_cur_freq")
                    }
                names
                    .asSequence()
                    .map { File(cpu, "cpufreq/$it").path }
                    .mapNotNull(::readMetric)
                    .firstOrNull()
                    ?.let { result.put(index.toString(), it.value) }
            }
        return result
    }

    private fun readSocTemperature(): MetricReading? =
        HostMetricPathDiscovery
            .discoverThermalPaths()
            .mapNotNull { readMetric(it.path) }
            .mapNotNull { reading ->
                HostMetricPathDiscovery.normalizeTemperatureC(reading.value)?.let { it to reading }
            }.maxByOrNull { (temperature, _) -> temperature }
            ?.second

    private fun JSONObject.putMetric(name: String, reading: MetricReading?) {
        if (reading == null) return
        put(name, reading.value)
        put("${name}Path", reading.path)
    }

    private data class MetricReading(val path: String, val value: String)

    private companion object {
        const val MAX_METRIC_LENGTH = 32 * 1024
        const val PATH_RETRY_INTERVAL_MS = 30_000L
        const val DISCOVERY_RETRY_INTERVAL_MS = 60_000L
        val CPU_DIRECTORY = Regex("cpu\\d+")
    }
}
