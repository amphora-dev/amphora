package app.amphora.core.engine

import java.io.File
import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.abs

/**
 * Bounded discovery of Android's non-standard host metric nodes.
 *
 * GPU sysfs names are vendor/OEM ABI rather than an Android API. Keep discovery centralized so the
 * app process and the Shizuku user service probe the same candidates without hard-coding one device.
 */
object HostMetricPathDiscovery {
    data class GpuPaths(
        val load: List<String>,
        val currentFrequency: List<String>,
        val maxFrequency: List<String>,
    )

    data class ThermalPath(val type: String, val path: String)

    fun discoverGpuPaths(
        roots: List<File> = DEFAULT_GPU_ROOTS.map(::File),
        includeStatic: Boolean = true,
    ): GpuPaths {
        val load = LinkedHashSet<String>()
        val current = LinkedHashSet<String>()
        val maximum = LinkedHashSet<String>()
        if (includeStatic) {
            load += STATIC_GPU_LOAD_PATHS
            current += STATIC_GPU_CURRENT_FREQUENCY_PATHS
            maximum += STATIC_GPU_MAX_FREQUENCY_PATHS
        }

        discoverGpuNodes(roots).forEach { node ->
            addCandidates(node, GPU_LOAD_FILES, load)
            addCandidates(node, GPU_CURRENT_FREQUENCY_FILES, current)
            addCandidates(node, GPU_MAX_FREQUENCY_FILES, maximum)
            val devfreq = File(node, "devfreq")
            addCandidates(devfreq, GPU_LOAD_FILES, load)
            addCandidates(devfreq, GPU_CURRENT_FREQUENCY_FILES, current)
            addCandidates(devfreq, GPU_MAX_FREQUENCY_FILES, maximum)
            devfreq.listFiles { child -> child.isDirectory }.orEmpty().forEach { child ->
                addCandidates(child, GPU_LOAD_FILES, load)
                addCandidates(child, GPU_CURRENT_FREQUENCY_FILES, current)
                addCandidates(child, GPU_MAX_FREQUENCY_FILES, maximum)
            }
        }

        return GpuPaths(load.toList(), current.toList(), maximum.toList())
    }

    fun discoverThermalPaths(root: File = File(DEFAULT_THERMAL_ROOT)): List<ThermalPath> =
        root
            .listFiles { file -> file.isDirectory && file.name.startsWith("thermal_zone") }
            .orEmpty()
            .mapNotNull { zone ->
                val type =
                    runCatching { File(zone, "type").readText().trim().lowercase(Locale.US) }
                        .getOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                if (THERMAL_TYPE_TOKENS.none(type::contains)) return@mapNotNull null
                ThermalPath(type, File(zone, "temp").path)
            }.sortedWith(compareBy<ThermalPath> { thermalRank(it.type) }.thenBy { it.path })

    fun normalizeTemperatureC(raw: String?): Float? {
        val value = Regex("-?\\d+(?:\\.\\d+)?").find(raw.orEmpty())?.value?.toFloatOrNull() ?: return null
        val celsius =
            when {
                abs(value) >= 10_000f -> value / 1_000f
                abs(value) >= 1_000f -> value / 100f
                abs(value) >= 200f -> value / 10f
                else -> value
            }
        return celsius.takeIf { it in -40f..150f }
    }

    private fun discoverGpuNodes(roots: List<File>): Set<File> {
        val result = LinkedHashSet<File>()
        val pending = ArrayDeque<Pair<File, Int>>()
        roots.filter(File::isDirectory).forEach { pending.addLast(it to 0) }
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_DISCOVERY_NODES) {
            val (directory, depth) = pending.removeFirst()
            visited++
            val path =
                runCatching { directory.canonicalPath.lowercase(Locale.US) }
                    .getOrDefault(directory.path.lowercase(Locale.US))
            val looksLikeGpu = GPU_NODE_TOKENS.any(path::contains)
            if (looksLikeGpu) result += directory
            if (depth >= MAX_DISCOVERY_DEPTH) continue

            directory.listFiles { child -> child.isDirectory }.orEmpty().forEach { child ->
                val childName = child.name.lowercase(Locale.US)
                if (depth == 0 ||
                    looksLikeGpu ||
                    childName == "devfreq" ||
                    GPU_NODE_TOKENS.any(childName::contains)
                ) {
                    pending.addLast(child to depth + 1)
                }
            }
        }
        return result
    }

    private fun addCandidates(directory: File, names: List<String>, output: MutableSet<String>) {
        if (!directory.isDirectory) return
        names.forEach { name ->
            val file = File(directory, name)
            if (file.isFile) output += file.path
        }
    }

    private fun thermalRank(type: String): Int = when {
        "cpu-silicon" in type || "gpu-silicon" in type -> 0
        "cpuss" in type || "gpuss" in type -> 1
        "cpu" in type || "gpu" in type -> 2
        "soc" in type || "ap" in type -> 3
        "skin" in type -> 4
        else -> 5
    }

    private const val DEFAULT_THERMAL_ROOT = "/sys/class/thermal"
    private const val MAX_DISCOVERY_DEPTH = 3
    private const val MAX_DISCOVERY_NODES = 512

    private val DEFAULT_GPU_ROOTS =
        listOf(
            "/sys/class/devfreq",
            "/sys/devices/virtual/devfreq",
            "/sys/devices/platform",
        )
    private val GPU_NODE_TOKENS =
        listOf("gpu", "mali", "g3d", "kgsl", "panfrost", "pvr", "powervr", "xclipse", "sgpu")
    private val THERMAL_TYPE_TOKENS =
        listOf("cpu", "gpu", "soc", "ap", "skin", "tsens", "cluster", "mali", "kgsl", "g3d")
    private val GPU_LOAD_FILES =
        listOf(
            "gpubusy",
            "gpu_busy_percentage",
            "gpu_busy_percent",
            "gpu_load",
            "utilisation",
            "utilization",
            "gpu_busy",
            "gpuinfo",
            "load",
        )
    private val GPU_CURRENT_FREQUENCY_FILES =
        listOf("cur_freq", "gpuclk", "clock_mhz", "gpu_clock", "clock")
    private val GPU_MAX_FREQUENCY_FILES =
        listOf("max_freq", "max_gpuclk", "available_frequencies", "gpu_available_frequencies", "dvfs_table")

    val STATIC_GPU_LOAD_PATHS =
        listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/gpuinfo",
            "/sys/devices/platform/gpusysfs/gpu_busy",
            "/sys/class/misc/pvrsrvkm/device/utilisation",
            "/sys/class/pvr/utilisation",
            "/sys/class/pvr/gpu_utilisation",
            "/sys/class/drm/card0/device/gpu_busy_percent",
            "/sys/kernel/gpu/gpu_busy",
        )
    val STATIC_GPU_CURRENT_FREQUENCY_PATHS =
        listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/clock_mhz",
            "/sys/class/devfreq/gpufreq/cur_freq",
            "/sys/class/misc/mali0/device/devfreq/cur_freq",
            "/sys/class/misc/mali0/device/clock",
            "/sys/devices/platform/gpusysfs/gpu_clock",
            "/sys/class/drm/card0/device/pp_dpm_sclk",
        )
    val STATIC_GPU_MAX_FREQUENCY_PATHS =
        listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies",
            "/sys/class/devfreq/gpufreq/max_freq",
            "/sys/class/misc/mali0/device/devfreq/max_freq",
            "/sys/class/misc/mali0/device/dvfs_table",
        )
}
