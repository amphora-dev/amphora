package app.amphora.core.engine.privileged

import android.os.Process
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
    private val inaccessiblePaths = mutableSetOf<String>()

    override fun readPerformanceSnapshot(): String {
        val result = JSONObject()
        result.put("uid", Process.myUid())
        PERFORMANCE_PATHS.forEach { (name, candidates) ->
            candidates.firstNotNullOfOrNull(::readMetric)?.let { result.put(name, it) }
        }
        return result.toString()
    }

    private fun readMetric(path: String): String? = try {
        if (path in inaccessiblePaths) return null
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            inaccessiblePaths += path
            return null
        }
        file
            .readText()
            .trim()
            .takeIf(String::isNotEmpty)
            ?.take(MAX_METRIC_LENGTH)
    } catch (_: Exception) {
        inaccessiblePaths += path
        null
    }

    private companion object {
        const val MAX_METRIC_LENGTH = 32 * 1024
        val PERFORMANCE_PATHS =
            mapOf(
                "cpuStat" to listOf("/proc/stat"),
                "gpuLoad" to
                    listOf(
                        "/sys/class/kgsl/kgsl-3d0/gpubusy",
                        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                        "/sys/class/misc/mali0/device/utilisation",
                        "/sys/kernel/gpu/gpu_busy",
                    ),
                "gpuCurrentFrequency" to
                    listOf(
                        "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                        "/sys/class/kgsl/kgsl-3d0/gpuclk",
                        "/sys/class/devfreq/gpufreq/cur_freq",
                        "/sys/class/misc/mali0/device/devfreq/cur_freq",
                    ),
                "gpuMaxFrequency" to
                    listOf(
                        "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
                        "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies",
                        "/sys/class/devfreq/gpufreq/max_freq",
                        "/sys/class/misc/mali0/device/devfreq/max_freq",
                    ),
            )
    }
}
